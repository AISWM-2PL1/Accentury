/**
 * 목소리 점검 화면 — 앱 `VoiceCheckScreen.kt`의 웹 대응 (KAN-31 4단계).
 *
 * 웹 단독 실행 시작 게이트의 세 번째 칸이다: 인트로 [시작하기] → 마이크 권한(KAN-56) → **여기** →
 * 세션 생성(`startStandaloneTest`) → 문항 화면. 앱이 KAN-105 2단계에서 만든 순서를 그대로 옮겼다.
 *
 * "안녕하세요" 한 마디로 두 가지를 끝낸다. 하나는 이 화자의 중심 음높이다 — 이후 모든 문항의
 * '내 억양' 곡선이 이 값을 y축 중심으로 쓰므로, 미리 재 두면 첫 문항의 첫 음절부터 곡선이
 * 제자리에서 그려진다. 문항마다 그 녹음의 앞부분으로 중심을 잡으면 문항끼리 축이 달라져
 * '내 억양'이 문항마다 다른 높이에 놓인다 (`userCurve.ts` 헤더). 다른 하나는 볼륨 확인이다 —
 * 마이크가 멀거나 막혀 소리가 작은 상태를, 결과에 반영되는 첫 문항이 아니라 여기서 알아채게 한다.
 *
 * 화면이 이 자리에 서는 이유: 마이크가 방금 열려 확인할 것이 바로 앞에 있고, 아직 네트워크를
 * 쓰기 전이라 실패할 구석이 없다(전부 브라우저 안에서 끝난다). 세션 뒤로 밀면 이미 발급된
 * 세션을 든 채 점검에 붙들리는 구간이 생긴다.
 *
 * ## 왜 URL 화면이 아니라 인트로와 같은 문서인가
 *
 * 다른 화면 전환(`goToResult`·`goToIntro`)은 문서를 다시 로드하는데, 여기만 그럴 수 없다.
 * 마이크 권한은 이 문서에서 방금 받은 것이라 리로드하면 브라우저에 따라 다시 물어야 하고,
 * 그 프롬프트는 사용자 제스처 없이는 뜨지 않는다. 그래서 상태는 `IntroRoute`가 들고
 * 이 화면은 그 안에서 인트로를 대신 그린다 (App.tsx).
 *
 * 판정은 전부 [VoiceCheckController]가 하고 여기는 그 상태를 그리기만 한다.
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { useRecorder, type CaptureFactory } from '../audio'
import { PitchTracker } from '../audio/pitchTracker'
import { FULL_SCALE, QUIET_RMS_THRESHOLD } from '../audio/quality'
import { CurveLane } from '../recording/CurveLane'
import { userCurveDisplayPoints } from '../recording/userCurve'
import { Button, StatusBlock } from '../ui'
import {
  VoiceCheckController,
  voiceCheckRms,
  type VoiceCheckHint,
  type VoiceCheckState,
} from './voiceCheckController'

export interface VoiceCheckScreenProps {
  /** 잰 중심 음높이를 호출자에게 넘긴다 — 이 값이 문항 화면의 centerHz가 된다 */
  onDone: (centerHz: number) => void
  /**
   * [다음] 뒤에 세션 생성이 막혔을 때의 사용자 문구 (App.tsx).
   *
   * 인트로로 되돌리지 않고 이 화면에 붙이는 것이 덜 침습적이다 — 되돌리면 인트로에 "실패
   * 문구를 안고 다시 열리는" 상태를 새로 만들어야 하고, 사용자는 방금 통과한 점검을 한 번 더
   * 해야 한다. 여기서는 [다음]이 그대로 재시도 버튼으로 남는다 (인트로의 실패 문구와 같은 규칙).
   */
  startFailure?: string | null
  /** 주입용 캡처 (테스트용). 기본값은 실제 Web Audio 캡처다 */
  capture?: CaptureFactory
  /** 주입용 시계 (테스트용) */
  now?: () => number
}

/**
 * 곡선이 담는 시간. 문항 화면과 달리 1초다 — 거기서 창을 가이드 길이의 배수로 넓히는 건
 * 문장 하나를 통째로 보여주기 위해서인데([userCurveWindowMs]), 이 화면은 맞춰야 할 가이드도
 * 남겨야 할 문장도 없다. 창이 짧을수록 곡선이 흐르는 게 눈에 보여서 "지금 내 목소리가
 * 들어오고 있다"가 전해진다.
 */
export const VOICE_CHECK_WINDOW_MS = 1000

/**
 * 듣기 상한. 앱 `RecordingEngine.MAX_DURATION_MS`와 같은 10초다 — 이 안에 판정이 안 나면
 * 마이크를 놓고 [다시 시도]를 준다. 무한정 듣고 있으면 무엇이 잘못됐는지 모른 채 계속 말하게 된다.
 */
export const VOICE_CHECK_MAX_DURATION_MS = 10_000

export function VoiceCheckScreen({ onDone, startFailure = null, capture, now }: VoiceCheckScreenProps) {
  // 판정기는 화면 인스턴스당 하나다. 렌더마다 새로 만들면 누적된 프레임이 매번 사라진다.
  const controllerRef = useRef<VoiceCheckController | null>(null)
  controllerRef.current ??= new VoiceCheckController()
  const controller = controllerRef.current

  /*
   * 이 점검의 F0 분석기. 리샘플러·프레이머가 조각 사이에 걸친 이력을 들고 있어 **듣기 1회당
   * 하나**이고, [다시 시도]에서 새로 만든다 (`WebVoiceRecorder`와 같은 규칙). 캡처 레이트를
   * 만들 때 알 수 없어(컨텍스트가 열려야 정해진다) 첫 조각이 올 때 만든다.
   */
  const trackerRef = useRef<PitchTracker | null>(null)
  const [state, setState] = useState<VoiceCheckState>(() => controller.state)

  /*
   * 캡처 정지 호출의 ref 사본. 판정이 끝나는 순간은 **조각 콜백 안**인데, 그 콜백은 훅이
   * 만들어 준 `stop`보다 먼저 만들어진다(순환). ref가 그 사이다 —
   * `useRecorder`가 onSamples를 ref로 갈아 끼우는 것과 같은 자리다.
   */
  const stopRef = useRef<(() => void) | null>(null)

  const handleSamples = useCallback(
    (chunk: Float32Array, sampleRate: number) => {
      const tracker = (trackerRef.current ??= new PitchTracker(sampleRate))
      /*
       * 프레임이 하나도 안 나온 조각도 그대로 넘긴다 — 볼륨(rms)은 프레임과 무관하게 이
       * 조각의 사실이고, 레벨 바와 통과 판정이 그 값으로 움직인다.
       */
      const ready = controller.onProgress(voiceCheckRms(chunk), tracker.push(chunk))
      setState(controller.state)
      // 판정이 끝났으면 마이크를 놓는다 — 더 들어 봐야 결과가 달라지지 않는다.
      if (ready) stopRef.current?.()
    },
    [controller],
  )

  const {
    state: recorder,
    start,
    stop,
    discard,
  } = useRecorder({
    maxDurationMs: VOICE_CHECK_MAX_DURATION_MS,
    capture,
    now,
    onSamples: handleSamples,
  })

  // 조각 콜백보다 먼저 걸려야 하므로 아래 시작 이펙트보다 위에 둔다 (이펙트는 선언 순서로 돈다).
  useEffect(() => {
    stopRef.current = () => void stop()
  }, [stop])

  /*
   * 권한은 앞 칸에서 받았으니 버튼 없이 진입 즉시 듣는다 — 여기서 한 번 더 누르게 하면
   * "말하세요"라는 안내와 "시작하세요"라는 버튼이 서로를 가린다.
   * 화면이 걷힐 때 마이크를 놓는 일은 훅의 언마운트 정리가 이미 한다 (FR-AD-04).
   */
  useEffect(() => {
    void start()
  }, [start])

  useEffect(() => {
    if (recorder.phase === 'review') {
      // 마이크가 닫혔다. 준비 상태면 그대로고, 아직 듣는 중이었으면 여기서 시간 초과가 된다.
      controller.onStopped()
      setState(controller.state)
      /*
       * 만들어진 녹음은 즉시 버린다 — 점검은 저장하지도 보내지도 않는다(화면 하단 문구가
       * 그 약속이다). 훅을 쓰는 대가로 녹음이 한 번 만들어지지만, 여기서 놓으면 그 수명이
       * 이 한 줄로 끝난다.
       */
      discard()
      return
    }
    if (recorder.phase === 'error') {
      // 캡처 계층이 지은 사용자용 문구를 그대로 쓴다 — 마이크가 왜 안 열렸는지는 이 화면이
      // 지어낼 수 없다.
      controller.onFailed(recorder.message)
      setState(controller.state)
    }
  }, [recorder, controller, discard])

  const restart = useCallback(() => {
    controller.restart()
    trackerRef.current = null
    setState(controller.state)
    // 오류 단계에 멈춰 있던 훅을 idle로 되돌린다. 시간 초과 경로는 위 이펙트가 이미 되돌렸다.
    discard()
    void start()
  }, [controller, discard, start])

  const frames = state.phase === 'failed' ? [] : state.frames
  /*
   * 잠긴 중심을 그대로 넘긴다. 값 자체는 [userCurveDisplayPoints]가 프레임에서 스스로 잡는
   * 것과 같지만(같은 [userCurveCenterHz]), 화면이 "무엇을 축으로 그리는가"를 판정기와 한
   * 값으로 묶어 두면 나중에 판정 규칙이 바뀌어도 곡선이 따라간다.
   */
  const centerHz = state.phase === 'listening' || state.phase === 'ready' ? state.centerHz : null

  return (
    <main className="item-screen">
      <div>
        <h1 className="type-title-sm">목소리를 확인할게요</h1>
        <p
          className="type-body-sm"
          style={{ color: 'var(--color-muted-foreground)', marginTop: 'var(--space-2)' }}
        >
          아래 말을 편하게 해 주세요
        </p>
      </div>

      {/* 문항 화면과 같은 카드다 — 여기서 말한 방식 그대로 문항에서도 말하면 된다는 뜻이 된다 */}
      <div className="prompt-card">
        {/* 이모지(🎤)를 뗐다 (KAN-161 3단계) — 문항 카드의 유형 배지와 같은 규칙이다.
            이모지는 시스템이 자기 색으로 그려 잉크 한 색 화면에 유일한 색조로 남는다 */}
        <span className="type-caption prompt-card__badge">목소리 점검</span>
        <p className="type-headline">안녕하세요</p>
      </div>

      <div className="item-screen__body">
        {/*
          레인은 하나다. 점검에는 따라 할 가이드가 없으므로(자기 목소리만 재는 자리)
          빈 가이드 레인을 함께 세우면 사용자는 없는 곡선을 찾게 된다.
          중심이 잠기기 전에는 빈 레인이 정상이다 — 임시 축으로 그려 두면 축이 잠기는 순간
          곡선 전체가 한 번 점프한다 (`userCurve.ts`).
        */}
        {/* 레인 하나여도 상자는 문항 화면과 같다 — 테두리·모서리는 상자가 갖는다 */}
        <div className="curve-card">
          <CurveLane
            label="내 억양"
            ariaLabel="내 억양 곡선"
            segments={userCurveDisplayPoints(frames, VOICE_CHECK_WINDOW_MS, centerHz)}
            variant="user"
          />
        </div>
        <InputLevelBar level={state.phase === 'listening' ? state.level : 0} />
        <StatusBlock
          /*
           * 실패에만 error를 준다 — 그래야 스크린 리더가 스스로 읽는다(StatusBlock 주석).
           * 듣는 중 문구는 조각마다 바뀌므로 읽어 주면 소음이 된다.
           */
          tone={state.phase === 'timedOut' || state.phase === 'failed' ? 'error' : 'waiting'}
          message={statusMessage(state)}
          /*
           * 시간이 다 됐을 때만 무엇이 모자랐는지 덧붙인다 — "잡히지 않았어요"만으로는
           * 다음에 무엇을 다르게 해야 하는지 알 수 없다.
           */
          detail={state.phase === 'timedOut' ? hintMessage(state.hint) : undefined}
        />
      </div>

      <div className="item-screen__footer">
        {/* 세션 생성이 막힌 자리. 이미 떠 있는 화면에 나중에 나타나므로 스스로 읽혀야 한다 */}
        {startFailure !== null && (
          <p
            className="type-label"
            role="alert"
            style={{ color: 'var(--color-destructive-on-surface)' }}
          >
            {startFailure}
          </p>
        )}
        {state.phase === 'ready' && (
          <Button onClick={() => onDone(state.centerHz)} style={{ width: '100%' }}>
            다음
          </Button>
        )}
        {(state.phase === 'timedOut' || state.phase === 'failed') && (
          <Button onClick={restart} style={{ width: '100%' }}>
            다시 시도
          </Button>
        )}
        {/*
          듣는 중에는 버튼이 없다 — 지금 사용자가 할 일은 말하는 것 하나뿐이라,
          누를 것을 주면 말하기를 멈추고 그걸 누른다.
        */}
        <p
          className="type-caption"
          style={{ color: 'var(--color-muted-foreground)', textAlign: 'center', marginTop: 'var(--space-4)' }}
        >
          이 소리는 저장하거나 보내지 않아요
        </p>
      </div>
    </main>
  )
}

/**
 * 입력 레벨 바. 곡선이 "무엇을 말했는가"라면 이건 "얼마나 크게 말했는가"다 —
 * 볼륨 부족은 곡선만 봐서는 알 수 없다(작게 말해도 F0는 잡힌다).
 *
 * 눈금은 통과선([QUIET_RMS_THRESHOLD])이다. "조금 더 크게"라는 말만으로는 얼마나 더인지
 * 알 수 없어서, 넘어야 할 자리를 눈에 보이게 둔다.
 *
 * `role="img"`로 감싸 값이 아니라 뜻만 읽히게 한다 — 시시각각 바뀌는 숫자를 읽어 주면
 * 스크린 리더로는 화면을 쓸 수 없다 (앱의 `clearAndSetSemantics`와 같은 처리).
 */
function InputLevelBar({ level }: { level: number }) {
  return (
    <div className="level-bar" role="img" aria-label="입력 레벨">
      <div className="level-bar__fill" style={{ width: `${levelBarFraction(level) * 100}%` }} />
      {/* 눈금은 채움 위에 그린다 — 채움이 눈금을 넘어선 순간에도 선이 보여야 통과가 읽힌다 */}
      <div
        className="level-bar__tick"
        style={{ left: `${levelBarFraction(QUIET_RMS_THRESHOLD) * 100}%` }}
      />
    </div>
  )
}

/**
 * 원 스케일 rms를 바의 0..1 비율로. **로그 스케일**이다 —
 * 사람이 느끼는 크기가 로그라서, 선형으로 그리면 일상적인 발화(rms 수백)가 전체 스케일
 * (32768) 대비 바 왼쪽 끝에 붙어 버려 커졌는지 작아졌는지가 안 보인다.
 */
function levelBarFraction(rms: number): number {
  if (rms <= 1) return 0
  return Math.min(1, Math.max(0, Math.log10(rms) / Math.log10(FULL_SCALE)))
}

/** 상태 한 줄. 비난 없이, 지금 할 일 하나만 말한다 (ux-ui.md) */
function statusMessage(state: VoiceCheckState): string {
  switch (state.phase) {
    case 'listening':
      return hintMessage(state.hint)
    case 'ready':
      return '좋아요, 목소리가 잘 들려요'
    case 'timedOut':
      return '목소리가 잡히지 않았어요'
    case 'failed':
      return state.reason
  }
}

function hintMessage(hint: VoiceCheckHint): string {
  switch (hint) {
    case 'SAY_IT':
      return "'안녕하세요'라고 말해 주세요"
    case 'KEEP_GOING':
      return '조금만 더요'
    case 'TOO_QUIET':
      return '조금 더 크게 말해 주세요'
  }
}
