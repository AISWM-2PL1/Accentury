/**
 * 브라우저 녹음 패널 (KAN-56 Stage 3). 음성 문항 화면의 하단 자리에서 녹음 → 검토 → 업로드를
 * 맡는다 — 앱이 아닌 곳(브라우저 단독, KAN-31 웹 단독 응시)에서 유일한 녹음 통로다.
 *
 * 단계와 전이는 전부 [useRecorder]가 가지고, 이 컴포넌트가 더하는 상태는 **업로드 한 겹**뿐이다.
 * 화면 흐름은 API 명세서 §5.7 그대로다: [녹음] → 녹음 중… → [정지] → 확인 [재녹음]/[다음].
 * 재생은 없다 — 그래서 검토 단계가 들고 있는 것도 재생용 오디오가 아니라 업로드용 [Recording]
 * 하나다.
 *
 * ## 업로드는 [다음]에서만 일어난다 (§3.3·§5.7)
 *
 * 정지한다고 올리지 않는다. 로컬 [재녹음]은 횟수 제한이 없고 **시도(attempt)를 만들지 않는다** —
 * 마음에 들 때까지 다시 읽는 것은 사용자의 일이지 서버가 셀 일이 아니다. 서버에 시도가 생기는
 * 순간은 사용자가 [다음]으로 이 녹음을 확정한 때뿐이다.
 *
 * ## 품질 게이트를 클라이언트가 먼저 본다 (FR-AD-08)
 *
 * 너무 짧거나·조용하거나·찢어진 녹음은 [다음] 자체를 그리지 않는다. 서버도 같은 판정을 다시
 * 하지만(권한은 서버에 있다), 왕복을 기다렸다가 "다시 읽어 주세요"를 듣는 것과 정지 직후
 * 바로 듣는 것은 사용자에게 다른 경험이다 — 특히 저속망에서 320KB를 올린 뒤 거절당하면
 * 데이터도 시간도 버린다. 판정 기준이 앱·웹·서버에서 같은 값인 이유가 이것이다
 * (`quality.ts` 헤더).
 */

import { useCallback, useMemo, useRef, useState } from 'react'
import { useRecorder, type CaptureFactory, type QualityStatus, type Recording } from '../audio'
import { PitchTracker, type PitchFrame } from '../audio/pitchTracker'
import { UploadError, type UploadAccepted } from '../audio/uploadRecording'
import type { ItemResult } from '../bridge/itemResult'
import { newIdempotencyKey } from '../net/idempotencyKey'
import { CurveCard } from '../recording/CurveCard'
import { guideCurveDisplayPoints } from '../recording/guideCurve'
import {
  fillShortGaps,
  reviewWindowMs,
  userCurveDisplayPoints,
  userCurveWindowMs,
} from '../recording/userCurve'
import { Button, StatusBlock } from '../ui'
import type { VoiceItem } from './testDefinition'

export interface WebVoiceRecorderProps {
  item: VoiceItem
  /** 녹음 한 건을 서버로 올린다. 같은 attemptId로 다시 부르는 것이 재시도다 */
  upload: (recording: Recording, attemptId: string) => Promise<UploadAccepted>
  /** 접수됨 — 브리지 경로의 `onItemResult`와 같은 모양으로 알린다 */
  onUploaded: (result: ItemResult) => void
  /** 주입용 캡처 (테스트용). 기본값은 실제 Web Audio 캡처다 */
  capture?: CaptureFactory
  /**
   * 목소리 점검(KAN-31 4단계)이 잰 화자의 중심 음높이 (Hz). 곡선의 y축 중심이 된다.
   *
   * 없으면 곡선이 **이 녹음의** 처음 8개 유성 프레임으로 중심을 잡는다 (`userCurve.ts`).
   * 문항마다 축이 조금씩 달라지는 대신 곡선은 반드시 그려진다 — 저장된 중심이 없다는 이유로
   * 레인이 비어 있으면 사용자에게는 녹음이 안 되는 것으로 보인다.
   */
  userCurveCenterHz?: number | null
}

type UploadState =
  | { kind: 'idle' }
  | { kind: 'uploading' }
  | { kind: 'failed'; message: string; retryable: boolean }

/**
 * 품질 판정별 안내. 전부 **다음 행동**을 말한다 — "실패했습니다"는 사용자가 할 일을 알려주지
 * 않아서 같은 실패를 반복하게 만든다 (ux-ui.md 비난 없는 카피).
 */
/**
 * 곡선을 다시 그리는 최소 간격 (ms). 약 30fps — 사람 눈이 곡선의 움직임을 이어진 것으로
 * 읽는 하한이면서, 조각 하나가 만든 프레임 여러 개를 한 번에 그리게 묶는 값이다.
 */
const CURVE_UPDATE_INTERVAL_MS = 33

/**
 * 남은 시간이 이 아래로 내려가면 경고 표시로 바꾼다 (KAN-161 3단계, 시안 2b).
 *
 * 10초 상한에서 8초부터라는 뜻이다. 값을 상한의 비율이 아니라 **남은 시간**으로 잡은 이유는
 * 문항마다 상한이 달라질 수 있기 때문이다(`item.maxDurationMs`) — 비율로 두면 상한이 짧은
 * 문항에서 경고가 시작하자마자 녹음이 끝난다. 사람이 문장을 맺는 데 필요한 시간은 상한과
 * 무관하게 2초쯤이다.
 */
const WARN_REMAINING_MS = 2_000

/**
 * 경과 시간 표기 `00:04` (시안). 초만 적던 것(`4.0초`)을 시계꼴로 바꿨다 — 옆에 붙는 상한이
 * "10초"라 같은 줄에 "초"가 두 번 나오면 어느 쪽이 지금인지 한눈에 안 갈린다.
 *
 * 반올림이 아니라 버림(`floor`)이다. 0.9초에서 `00:01`이 뜨면 아직 1초가 안 됐는데 1초로
 * 보이고, 품질 게이트가 1초 미만을 거절하므로(FR-AD-08) 화면과 판정이 어긋난 것처럼 읽힌다.
 */
function formatElapsed(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

const QUALITY_MESSAGE: Record<Exclude<QualityStatus, 'NORMAL'>, string> = {
  TOO_SHORT: '녹음이 너무 짧아요. 1초 이상 읽어 주세요',
  TOO_QUIET: '목소리가 잘 들리지 않아요. 조금 더 크게 읽어 주세요',
  CLIPPED: '소리가 너무 커요. 마이크에서 조금 떨어져 주세요',
}

export function WebVoiceRecorder({
  item,
  upload,
  onUploaded,
  capture,
  userCurveCenterHz = null,
}: WebVoiceRecorderProps) {
  const [uploadState, setUploadState] = useState<UploadState>({ kind: 'idle' })

  /*
   * 이 녹음의 F0 분석기. 리샘플러·프레이머가 조각 사이에 걸친 이력을 들고 있어 **녹음 1회당
   * 하나**이고, [녹음]에서 새로 만들며 [재녹음]·업로드 뒤에 놓는다 (FR-AD-04 — 오디오를 필요
   * 이상으로 들고 있지 않는다).
   *
   * 캡처 레이트를 만들 때 알 수 없어(권한 프롬프트가 끝나야 정해진다) 첫 조각이 올 때 만든다.
   */
  const trackerRef = useRef<PitchTracker | null>(null)
  const framesRef = useRef<PitchFrame[]>([])
  /*
   * 곡선 갱신용 리렌더 트리거. 프레임은 ref에 쌓고 이 카운터만 올린다 — 프레임 배열을 state로
   * 두면 조각마다 배열 하나가 새로 생기고, 그걸 막으려 mutable하게 쓰면 React가 변화를 못 본다.
   *
   * 값은 읽지 않고 setter만 쓴다: "무엇이 바뀌었는지"는 ref가 알고, state는 "다시 그려라"는
   * 신호만 나른다.
   */
  const [, bumpCurve] = useState(0)
  const lastCurveUpdateRef = useRef(0)

  const handleSamples = useCallback((chunk: Float32Array, sampleRate: number) => {
    const tracker = (trackerRef.current ??= new PitchTracker(sampleRate))
    if (tracker.push(chunk).length === 0) return
    framesRef.current = tracker.frames

    /*
     * 갱신을 33ms(≈30fps)로 묶는다. 조각은 48kHz에서 약 85ms마다 오지만 조각 하나가 프레임
     * 3개를 만들 수 있고, 그때마다 그리면 한 화면에서 곡선이 세 번 다시 그려진다 — 사람 눈에는
     * 한 번과 구분되지 않는데 비용만 세 배다. `useRecorder`가 경과 시간을 100ms로 묶는 것과
     * 같은 판정이고, 곡선은 움직임이 보여야 해서 그보다 촘촘하다.
     */
    const now = performance.now()
    if (now - lastCurveUpdateRef.current < CURVE_UPDATE_INTERVAL_MS) return
    lastCurveUpdateRef.current = now
    bumpCurve((version) => version + 1)
  }, [])

  const { state, start, stop, discard } = useRecorder({
    maxDurationMs: item.maxDurationMs,
    capture,
    onSamples: handleSamples,
  })

  /** 곡선 상태를 놓는다. 새 녹음을 시작할 때와 이 녹음을 버릴 때 둘 다 여기를 지난다 */
  const resetCurve = useCallback(() => {
    trackerRef.current = null
    framesRef.current = []
    lastCurveUpdateRef.current = 0
    bumpCurve((version) => version + 1)
  }, [])

  /*
   * 가이드 레인은 정의가 바뀌지 않는 한 그대로다. 단위가 semitone이 아니면(구버전 정의나
   * 예상 못한 스키마) 빈 레인으로 둔다 — 다른 단위를 semitone 축에 그리면 조용히 틀린 그림이
   * 된다. 앱 `RecordingScreen`의 판정과 같다.
   */
  const guidePoints = useMemo(
    () => (item.guideF0.unit === 'semitone' ? guideCurveDisplayPoints(item.guideF0.values) : []),
    [item.guideF0],
  )
  /** 사용자 레인이 담을 시간. 가이드 길이의 2배다 (`userCurve.ts`) */
  const liveWindowMs = useMemo(
    () => userCurveWindowMs(item.guideF0.frameIntervalMs, item.guideF0.values.length),
    [item.guideF0],
  )

  /*
   * Review에서만 짧은 무성 구멍을 메우고 창을 녹음 전체 길이로 늘린다. 녹음 중에는 곡선이
   * 인과적이어야 해서(뒤 프레임을 보면 이미 그린 과거가 다시 그려진다) 구멍을 앞 값으로
   * 유지하는 수밖에 없고, 창도 최신이 오른쪽 끝에 붙도록 미끄러져야 한다 (`pitch-curve.md` §4).
   */
  const reviewing = state.phase === 'review'
  const curveFrames = reviewing ? fillShortGaps(framesRef.current) : framesRef.current
  const windowMs = reviewing ? reviewWindowMs(curveFrames, liveWindowMs) : liveWindowMs
  const curveCard = (
    <CurveCard
      guidePoints={guidePoints}
      userSegments={userCurveDisplayPoints(curveFrames, windowMs, userCurveCenterHz)}
    />
  )

  /*
   * 이 녹음의 시도 식별자. [다음]을 처음 누를 때 만들고 재시도에서 그대로 다시 쓴다 (§5.1·§5.2).
   *
   * 상태가 아니라 ref인 이유는 렌더에 보이지 않는 값이라서다 — 어휘 문항이 선택지별 멱등 키를
   * ref로 든 것과 같은 판정이다. 수명은 **녹음 하나**다: [재녹음]으로 다른 녹음이 되는 순간
   * 비우고, 그 다음 [다음]은 새 키를 만든다. 물려주면 서버가 첫 녹음의 접수 결과를 그대로
   * 돌려줘 다시 읽은 음성이 채점에서 사라진다.
   */
  const attemptIdRef = useRef<string | null>(null)
  /*
   * 업로드 진행 중 표시의 ref 사본. setState는 비동기라 [다음] 연타의 두 번째 클릭이
   * `uploadState`가 'uploading'으로 바뀌기 전에 핸들러에 닿을 수 있다 — 그 순간 판정에 쓸 값은
   * ref다 (`useRecorder`가 자동 정지 판정에 phaseRef를 쓰는 것과 같은 이유).
   */
  const uploadingRef = useRef(false)

  /** [재녹음] — 녹음도 곡선도 시도 식별자도 버리고 처음으로 돌아간다. 서버에는 아무 일도 없었다 */
  const retake = useCallback(() => {
    attemptIdRef.current = null
    setUploadState({ kind: 'idle' })
    resetCurve()
    discard()
  }, [discard, resetCurve])

  /** [녹음] — 앞 녹음의 곡선을 놓고 새로 시작한다. 분석기는 첫 조각에서 캡처 레이트로 만들어진다 */
  const beginRecording = useCallback(() => {
    resetCurve()
    void start()
  }, [resetCurve, start])

  const send = useCallback(
    async (recording: Recording) => {
      if (uploadingRef.current) return
      uploadingRef.current = true
      setUploadState({ kind: 'uploading' })

      // 키 생성까지 try 안이다 — 여기서 동기로 터지면 rejection이 아무 데도 안 잡혀 버튼이
      // "눌러도 아무 일 없는" 상태가 된다 (crypto.randomUUID 부재로 실제 발생했던 증상).
      try {
        attemptIdRef.current ??= newIdempotencyKey()
        const attemptId = attemptIdRef.current
        const { analysisJobId } = await upload(recording, attemptId)
        /*
         * 접수됨. 부모는 이 통지를 받아 다음 문항으로 넘기므로 이 컴포넌트는 곧 언마운트된다.
         * 여기서 시도 식별자를 놓는 것이 FR-AD-04(오디오를 필요 이상으로 들고 있지 않는다)의
         * 이 화면 몫이다 — [Recording] 자체는 언마운트 전까지 훅 상태에 남지만, 언마운트가
         * 곧바로 따라오고 훅의 cleanup이 버퍼까지 놓는다.
         */
        attemptIdRef.current = null
        // 분석기도 여기서 놓는다 - 접수된 녹음의 F0 프레임을 언마운트까지 들고 있을 이유가 없다.
        trackerRef.current = null
        framesRef.current = []
        onUploaded({
          itemId: item.itemId,
          attemptId,
          analysisJobId,
          durationMs: recording.durationMs,
          qualityStatus: recording.status,
        })
      } catch (error: unknown) {
        // 재시도 가능 여부는 서버가 봉투로 말해 준다. 그 판정이 없는 실패(UploadError가 아닌 값)는
        // 막지 않는다 — 재시도는 같은 키라 무해하고, 여기서 잠가 버리면 사용자가 문항에 갇힌다.
        setUploadState({
          kind: 'failed',
          message: error instanceof Error ? error.message : String(error),
          retryable: error instanceof UploadError ? error.retryable : true,
        })
      } finally {
        uploadingRef.current = false
      }
    },
    [item.itemId, onUploaded, upload],
  )

  /*
   * 조작부는 화면 바닥(.item-screen__footer)에, 곡선 카드는 본문(.item-screen__body)에 놓는다.
   * 두 자리를 한 컴포넌트가 채우므로 조각(fragment)으로 돌려주고, 호출자(VoiceItemScreen)가
   * 이걸 대사 카드 아래에 그대로 편다 — 곡선을 조작부와 같은 자리에 두면 버튼 위에 120px짜리
   * 카드가 두 개 얹혀 [정지]가 화면 밖으로 밀린다.
   */
  const controls = () => {
    if (state.phase === 'error') {
      return (
        <StatusBlock
          tone="error"
          message={state.message}
          action={
            <Button onClick={retake} style={{ width: '100%' }}>
              다시 시도
            </Button>
          }
        />
      )
    }

    if (state.phase === 'review') {
      return (
        <ReviewPanel
          recording={state.recording}
          uploadState={uploadState}
          onRetake={retake}
          onSend={() => void send(state.recording)}
        />
      )
    }

    if (state.phase === 'recording') {
      const ratio = Math.min(1, state.elapsedMs / item.maxDurationMs)
      const limitSec = Math.round(item.maxDurationMs / 1000)
      const remainingMs = Math.max(0, item.maxDurationMs - state.elapsedMs)
      const warning = remainingMs <= WARN_REMAINING_MS
      return (
        <>
          {/*
            경과 시간은 벽시계가 아니라 담긴 샘플 수에서 온다 (RecordingBuffer.durationMs) —
            사용자가 보는 숫자와 서버가 파일에서 재는 길이가 같아야 한다.
            상한에 닿으면 훅이 스스로 멈추므로 [정지]를 못 눌러도 녹음이 잘리지 않는다 (FR-RC-02).

            마지막 2초에는 남은 시간을 잉크 캡슐로 바꿔 단다 (KAN-161 3단계, 시안 2b). 같은
            숫자를 다르게 그리는 것이 아니라 **다른 것을 말한다** — 위 표기는 "얼마나 읽었나",
            캡슐은 "곧 끊긴다"라서, 문장을 맺어야 하는 순간에만 나타나는 편이 읽힌다.

            `role="status"`로 읽히게 두되 `aria-live="polite"`인 이유: 매초 바뀌는 값이라
            assertive면 사용자가 읽던 대사 문장을 스크린 리더가 가로챈다.
          */}
          {warning ? (
            <p className="type-label record-countdown" role="status" aria-live="polite">
              {Math.ceil(remainingMs / 1000)}초 남음
            </p>
          ) : (
            <p className="type-label record-elapsed">
              {formatElapsed(state.elapsedMs)} / {limitSec}초
            </p>
          )}
          <div className="record-meter" aria-hidden="true">
            <div className="record-meter__fill" style={{ width: `${ratio * 100}%` }} />
          </div>
          {/* 자동 종료를 미리 알린다 — 갑자기 멈추면 사용자는 자기가 뭘 잘못 눌렀다고 생각한다 */}
          <p className="type-caption record-hint">{limitSec}초가 되면 자동으로 멈춰요</p>
          <Button onClick={() => void stop()} style={{ width: '100%' }}>
            정지
          </Button>
        </>
      )
    }

    if (state.phase === 'starting') {
      // 권한 프롬프트·컨텍스트 생성 구간. 비활성 버튼을 남기는 이유는 자리를 지키기 위해서다 —
      // 버튼이 사라졌다 나타나면 그 사이에 아래 내용이 위로 올라왔다 내려간다.
      return (
        <Button disabled style={{ width: '100%' }}>
          준비 중…
        </Button>
      )
    }

    return (
      <>
        <p className="type-caption record-hint">버튼을 누르고 문장을 읽어 주세요</p>
        <Button onClick={beginRecording} style={{ width: '100%' }}>
          녹음
        </Button>
      </>
    )
  }

  return (
    <>
      {/*
        곡선 카드는 단계와 무관하게 늘 같은 자리에 있다. 녹음 전에는 가이드만 그려져 "이 억양을
        따라 읽으면 된다"를 먼저 보여 주고, 녹음 중에 아래 레인이 자라며, Review에서 발화 전체가
        남는다. 단계마다 넣었다 뺐다 하면 그때마다 아래 조작부가 120px씩 오르내린다.
      */}
      <div className="item-screen__body">{curveCard}</div>
      <div className="item-screen__footer">{controls()}</div>
    </>
  )
}

/**
 * 정지 뒤의 확인 단계. 세 갈래다 — 보내는 중 / 실패 / 확인.
 *
 * 갈래를 따로 뽑은 이유는 위 컴포넌트의 단계 분기와 성격이 다르기 때문이다. 저쪽은 녹음
 * 상태 기계의 단계이고 여기는 **한 단계 안의 화면 상태**라, 한 함수에 섞어 두면 "review인데
 * uploading이고 failed는 아닌" 조합을 읽는 사람이 매번 머릿속에서 풀어야 한다.
 */
function ReviewPanel({
  recording,
  uploadState,
  onRetake,
  onSend,
}: {
  recording: Recording
  uploadState: UploadState
  onRetake: () => void
  onSend: () => void
}) {
  if (uploadState.kind === 'uploading') {
    // 버튼을 아예 그리지 않는다 — 비활성 버튼을 남기면 눌러 보고 안 눌리는 것을 확인하게 된다.
    return <StatusBlock tone="waiting" message="보내는 중…" />
  }

  if (uploadState.kind === 'failed') {
    return (
      <StatusBlock
        tone="error"
        message={uploadState.message}
        action={
          <div className="record-actions">
            {/* 재시도는 **같은 녹음을 같은 키로** 다시 보낸다 (§5.2) — 중복 접수가 생기지 않는다 */}
            {uploadState.retryable && <Button onClick={onSend}>다시 시도</Button>}
            <Button variant={uploadState.retryable ? 'text' : 'primary'} onClick={onRetake}>
              재녹음
            </Button>
          </div>
        }
      />
    )
  }

  if (recording.status !== 'NORMAL') {
    /*
     * 품질 게이트 (FR-AD-08). [다음]을 비활성으로 두지 않고 **렌더 자체를 하지 않는다** —
     * 비활성 버튼은 "언젠가 눌릴 수 있다"는 신호라, 사용자가 눌러 보다가 왜 안 되는지 묻게 된다.
     * 여기서 나갈 길은 다시 읽는 것 하나뿐이므로 그 하나만 남긴다.
     */
    return (
      <StatusBlock
        tone="error"
        message={QUALITY_MESSAGE[recording.status]}
        action={
          <Button onClick={onRetake} style={{ width: '100%' }}>
            재녹음
          </Button>
        }
      />
    )
  }

  return (
    <>
      {/* 재생이 없으므로 "들어보세요"라고 말하지 않는다 (§5.7) */}
      <p className="type-caption record-hint">녹음이 끝났어요. 다시 녹음하거나 다음으로 넘어가세요</p>
      <div className="record-actions">
        <Button variant="text" onClick={onRetake}>
          재녹음
        </Button>
        <Button onClick={onSend}>다음</Button>
      </div>
    </>
  )
}
