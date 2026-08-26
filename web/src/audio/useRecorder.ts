/**
 * 녹음 상태 기계 훅 (KAN-56 Stage 2).
 *
 * 화면이 알아야 할 것은 "지금 어느 단계인가"뿐이고, 이 훅이 그 단계와 전이를 전부 소유한다.
 * 캡처는 [CaptureFactory]로 주입받아 브라우저 없이도 전이를 검사할 수 있게 한다 —
 * `useAnalysisPolling`이 fetch·시계를 주입받는 것과 같은 구성이다.
 *
 * ## 단계가 다섯인 이유
 *
 * `starting`을 따로 둔 이유는 권한 프롬프트·컨텍스트 생성이 눈에 띄게 걸릴 수 있어서다.
 * 그 구간을 `idle`로 두면 사용자가 [녹음]을 다시 눌러 캡처를 두 개 잡고, `recording`으로
 * 두면 아직 아무것도 안 담기는데 시간이 흐르는 것처럼 보인다.
 *
 * `review`는 KAN-56 계약의 핵심이다 — 정지 뒤 화면은 [재녹음]/[다음]만 준다. 재생은 없다
 * (API 명세서 §5.7). 그래서 이 단계가 들고 있는 것도 재생용 오디오가 아니라 업로드용
 * [Recording] 하나뿐이다.
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { CaptureError, webAudioCapture, type Capture, type CaptureFactory } from './capture'
import { RecordingBuffer, type Recording } from './recordingBuffer'

export type RecorderState =
  | { phase: 'idle' }
  | { phase: 'starting' }
  | { phase: 'recording'; elapsedMs: number }
  | { phase: 'review'; recording: Recording }
  | { phase: 'error'; reason: CaptureError['reason'] | 'unknown'; message: string }

export interface UseRecorderOptions {
  /** 문항 정의의 최대 녹음 길이. 이 값에 닿으면 자동으로 멈춘다 (FR-RC-02) */
  maxDurationMs: number
  /** 주입용 캡처 (테스트용). 기본값은 실제 Web Audio 캡처다 */
  capture?: CaptureFactory
  /** 주입용 시계 (테스트용). 갱신 간격을 재는 데만 쓴다 */
  now?: () => number
  /**
   * 녹음 중 캡처 조각이 도착할 때마다 부른다 (KAN-56 Stage 5). 실시간 피치 곡선이 이 통로로
   * 원본 조각을 받아 자기 분석 파이프라인에 흘린다.
   *
   * **업로드 경로와 갈라져 있다.** 녹음 버퍼는 조각을 모아 한 번에 16kHz로 리샘플하고, 곡선은
   * 같은 조각을 스트리밍으로 줄여 32ms마다 F0를 뽑는다 — 두 경로가 필요한 것(정확한 파일 /
   * 낮은 지연)이 달라 한 계산을 나눠 쓸 수 없다. 대신 같은 커널·같은 문턱을 쓴다
   * (`streamingResampler.ts`, `yin.ts`).
   *
   * 단계가 `recording`일 때만 부른다. 자동 정지 뒤에 도착한 조각은 녹음에 들어가지 않으므로
   * 곡선에도 들어가면 안 된다.
   */
  onSamples?: (chunk: Float32Array, sampleRate: number) => void
}

export interface UseRecorderResult {
  state: RecorderState
  start: () => Promise<void>
  stop: () => Promise<void>
  /** 검토 화면의 [재녹음] — 방금 만든 녹음을 버리고 처음으로 돌아간다. 오류에서도 쓴다 */
  discard: () => void
}

/**
 * 경과 시간 상태를 갱신하는 최소 간격 (ms).
 *
 * 조각은 48kHz에서 약 85ms마다 오는데(4096샘플) 그때마다 setState하면 초당 12번 리렌더한다.
 * 화면에 보이는 것은 0.1초 단위 숫자와 게이지라 그보다 촘촘할 이유가 없다.
 */
const ELAPSED_UPDATE_INTERVAL_MS = 100

/** 실패 사유별 사용자 문구. 비난 없는 톤을 지킨다 (ux-ui.md) */
const FAILURE_MESSAGE: Record<CaptureError['reason'] | 'unknown', string> = {
  permission: '마이크 권한이 필요해요',
  unavailable: '마이크를 사용할 수 없어요',
  'audio-context-suspended': '녹음을 시작하지 못했어요. 다시 시도해 주세요',
  'worklet-load-failed': '녹음 기능을 불러오지 못했어요. 다시 시도해 주세요',
  unknown: '녹음을 시작하지 못했어요',
}

function toErrorState(error: unknown): RecorderState {
  const reason = error instanceof CaptureError ? error.reason : 'unknown'
  // 원인 문자열은 화면에 쓰지 않고 콘솔에만 남긴다 — 사용자에게 필요한 건 다음 행동이지
  // DOMException 이름이 아니다. 실기 진단(Stage 4)은 이 로그를 본다.
  console.warn('[recorder] 캡처 실패', error)
  return { phase: 'error', reason, message: FAILURE_MESSAGE[reason] }
}

export function useRecorder(options: UseRecorderOptions): UseRecorderResult {
  const { maxDurationMs } = options

  // 캡처와 시계는 훅 인스턴스당 한 번만 고정한다. 렌더마다 새 값을 잡으면 콜백 의존성이
  // 흔들려 녹음 도중 콜백이 갈아끼워진다.
  const captureFactoryRef = useRef<CaptureFactory | null>(null)
  if (captureFactoryRef.current === null) captureFactoryRef.current = options.capture ?? webAudioCapture
  const clockRef = useRef<(() => number) | null>(null)
  if (clockRef.current === null) clockRef.current = options.now ?? (() => performance.now())

  /*
   * 조각 통지만은 렌더마다 최신 함수로 갈아 끼운다. 캡처·시계와 달리 이 콜백은 화면의 상태를
   * 건드리는 쪽이라 고정해 두면 낡은 클로저가 남는데, 그렇다고 의존성에 넣으면 녹음 도중
   * handleChunk가 새로 만들어져 워클릿에 걸린 콜백과 훅이 보는 콜백이 갈린다. ref가 그 사이다.
   */
  const onSamplesRef = useRef<UseRecorderOptions['onSamples']>(undefined)
  useEffect(() => {
    onSamplesRef.current = options.onSamples
  }, [options.onSamples])

  const [state, setState] = useState<RecorderState>({ phase: 'idle' })

  /*
   * 단계를 ref로도 들고 있는 이유: 자동 정지 판단이 **워클릿 콜백 안**에서 일어나는데, 그
   * 콜백은 렌더 사이클 밖이라 최신 state를 볼 수 없다. setState는 비동기라 "방금 정지시켰다"는
   * 사실이 state에 반영되기 전에 다음 조각이 도착할 수 있어, 그 순간 판정에 쓸 값은 ref다.
   */
  const phaseRef = useRef<RecorderState['phase']>('idle')
  const captureRef = useRef<Capture | null>(null)
  const bufferRef = useRef<RecordingBuffer | null>(null)
  const lastUpdateRef = useRef(0)
  const stoppingRef = useRef<Promise<void> | null>(null)
  const mountedRef = useRef(true)

  const applyState = useCallback((next: RecorderState) => {
    phaseRef.current = next.phase
    setState(next)
  }, [])

  const stop = useCallback(async (): Promise<void> => {
    // 진행 중인 정지가 있으면 그 약속을 그대로 돌려준다 — 자동 정지와 사용자의 [정지]가
    // 겹쳐도 캡처는 한 번만 닫히고 녹음도 하나만 만들어진다.
    if (stoppingRef.current !== null) return stoppingRef.current
    if (phaseRef.current !== 'recording') return

    const capture = captureRef.current
    const buffer = bufferRef.current

    stoppingRef.current = (async () => {
      try {
        // 캡처가 먼저다. 이 약속이 풀리면 워클릿의 잔여분까지 버퍼에 들어와 있다.
        await capture?.stop()
        captureRef.current = null
        const recording = buffer?.finish() ?? null
        bufferRef.current = null
        if (!mountedRef.current) return
        applyState(recording === null ? { phase: 'idle' } : { phase: 'review', recording })
      } catch (error) {
        captureRef.current = null
        bufferRef.current = null
        if (mountedRef.current) applyState(toErrorState(error))
      } finally {
        stoppingRef.current = null
      }
    })()
    return stoppingRef.current
  }, [applyState])

  /**
   * 조각 하나를 담고, 필요하면 화면을 갱신하거나 자동 정지한다.
   *
   * 경과 시간을 **담긴 샘플 수**에서 뽑는 이유는 벽시계와 어긋나기 때문이다 — 탭이 잠깐
   * 백그라운드로 내려가거나 캡처가 끊기면 시간은 흘렀는데 오디오는 늘지 않는다. 사용자가
   * 보는 숫자와 서버가 파일에서 재는 길이는 같아야 한다 ([RecordingBuffer.durationMs]).
   */
  const handleChunk = useCallback(
    (chunk: Float32Array) => {
      const buffer = bufferRef.current
      if (buffer === null) return

      // 곡선이 먼저 본다. 담기 전에 부르는 이유는 buffer.push가 상한에 걸린 마지막 조각을
      // 잘라 담기 때문이다 - 곡선은 잘리기 전 조각을 보고, 상한을 넘긴 뒤의 조각은 아래
      // 단계 판정에서 걸러진다(자동 정지로 phase가 바뀐다).
      if (phaseRef.current === 'recording') {
        onSamplesRef.current?.(chunk, buffer.sampleRate)
      }

      if (buffer.push(chunk)) {
        void stop()
        return
      }
      if (phaseRef.current !== 'recording') return

      const now = clockRef.current!()
      if (now - lastUpdateRef.current < ELAPSED_UPDATE_INTERVAL_MS) return
      lastUpdateRef.current = now
      applyState({ phase: 'recording', elapsedMs: buffer.durationMs })
    },
    [applyState, stop],
  )

  const start = useCallback(async (): Promise<void> => {
    if (phaseRef.current !== 'idle') return
    applyState({ phase: 'starting' })

    try {
      const capture = await captureFactoryRef.current!(handleChunk)
      if (!mountedRef.current) {
        // 시작하는 사이에 화면이 사라졌다. 열린 캡처를 그대로 두면 마이크가 계속 잡혀 있다.
        void capture.stop()
        return
      }
      captureRef.current = capture
      bufferRef.current = new RecordingBuffer(capture.sampleRate, maxDurationMs)
      // 한 간격 앞선 시각으로 잡아 **첫 조각은 반드시 화면에 반영되게** 한다. 정확히 지금으로
      // 두면 첫 100ms 동안 0초에 멈춰 있어 녹음이 시작되지 않은 것처럼 보인다.
      lastUpdateRef.current = clockRef.current!() - ELAPSED_UPDATE_INTERVAL_MS
      applyState({ phase: 'recording', elapsedMs: 0 })
    } catch (error) {
      if (mountedRef.current) applyState(toErrorState(error))
    }
  }, [applyState, handleChunk, maxDurationMs])

  const discard = useCallback(() => {
    if (phaseRef.current !== 'review' && phaseRef.current !== 'error') return
    applyState({ phase: 'idle' })
  }, [applyState])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      // 화면을 떠나면 마이크를 놓고 담아 둔 오디오도 버린다 (FR-AD-04).
      void captureRef.current?.stop()
      captureRef.current = null
      bufferRef.current = null
    }
  }, [])

  return { state, start, stop, discard }
}
