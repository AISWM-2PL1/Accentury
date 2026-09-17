/**
 * 시작 대기 화면 (KAN-216). 목소리 점검과 첫 문항 사이에 서는 3초 카운트다운이다.
 *
 * 점검이 끝나자마자 첫 문항이 뜨면 "이제 시험이 시작됐다"는 경계가 없다 — 앱에서는 네이티브
 * 점검 화면이 닫히는 것과 동시에 녹음 화면이 열려, 사용자가 문장을 읽을 준비가 되기 전에
 * 이미 녹음 중이다. 3 → 2 → 1 한 번이 그 경계다. 스킵은 없고 3초 고정이다 (팀장 확정,
 * 2026-09-17).
 *
 * **프레젠테이션 전용이다.** 남은 초는 부모(`TestFlowScreen`의 `TestRunner`)가 JS 타이머로
 * 재서 내려 준다. 시간을 CSS 애니메이션으로 재지 않는 이유: `tokens.css`의 reduced-motion
 * 규칙이 모든 animation·transition을 0.01ms로 접으므로, CSS가 시계면 그 설정에서 3초가 통째로
 * 사라진다. CSS는 시각 효과만 맡는다. 타이머를 여기 두지 않는 것도 같은 결정의 연장이다 —
 * 부모가 "카운트다운이 끝났는가"로 첫 문항 마운트와 `item_shown` 계측을 가르므로, 시계는
 * 그 판정을 내리는 쪽에 있어야 한다.
 *
 * ## 스크린 리더에는 문구 하나만
 *
 * 매초 바뀌는 숫자는 읽지 않는다. `WebVoiceRecorder`의 `.record-countdown`이 매초 읽는 것은
 * 마지막 2초짜리라 예외고, 3초를 매초 읽으면 "3, 2, 1"이 소음이다. 대신 마운트 때 한 번
 * 읽히는 고정 문구를 `role="status"`로 두고, 링과 숫자 블록은 통째로 `aria-hidden`이다.
 */

import { TextHero } from '../ui/TextHero'

/**
 * 카운트다운 길이 (초). 3인 이유는 위 헤더 — 경계를 느끼기엔 충분하고 기다리기엔 짧은 값이다.
 * `TestFlowScreen`이 시계의 초기값으로 쓰고, 이 화면은 링의 분모로 쓴다. 테스트도 이 값으로
 * 타이머를 민다.
 */
export const START_COUNTDOWN_SECONDS = 3

/**
 * 제목 아래 한 줄 (사용자 확정, 2026-09-17). "3초 뒤 시작"처럼 시계를 되풀이하지 않고 곧 할
 * 일을 말한다 — 시간은 링과 숫자가 이미 보여 주고, 스크린 리더에는 이 문구 하나만 읽히므로
 * 그 한 번이 "무엇을 하러 왔는가"여야 한다. export인 이유는 테스트가 문자열을 복붙하지 않게
 * 하려는 것이다 — 카피가 바뀌면 여기 한 곳만 바뀐다.
 */
export const START_SCREEN_SUBTITLE = '당신의 경남 사투리 실력을 보여주세요!'

export interface TestStartScreenProps {
  /** 남은 초. 3·2·1 — 0은 부모가 이 화면 대신 첫 문항을 그리므로 여기까지 오지 않는다 */
  secondsLeft: number
}

/*
 * 링의 반지름. `score-donut`과 같은 `viewBox 0 0 120 120` 좌표계라 결과 화면의 원과 같은
 * 비례로 그려진다 — 화면을 여는 원형이 두 가지 규격이면 같은 물건으로 안 읽힌다.
 */
const RADIUS = 54
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

export function TestStartScreen({ secondsLeft }: TestStartScreenProps) {
  /*
   * 남은 몫만큼 채운다 — 3에서 한 바퀴, 1초마다 한 칸씩 감긴다. offset 전환(`components.css`
   * `.test-start__value`)이 칸 사이를 잇는다. 상한 밖 값(음수·3 초과)은 0~1로 가둔다.
   * 부모가 그런 값을 주지는 않지만, 원호가 한 바퀴를 넘어 그려지면 어디가 잘못됐는지 알 수 없다.
   */
  const remaining = Math.min(Math.max(secondsLeft / START_COUNTDOWN_SECONDS, 0), 1)

  return (
    <main className="screen test-start">
      {/* 이 화면의 이름이다 — 다른 제목이 없으므로 h1으로 선다 (`TextHero` 헤더 주석) */}
      <TextHero heading>곧 시작합니다</TextHero>
      {/*
        마운트 때 한 번만 읽히는 고정 문구. 남은 초를 여기 넣으면 매초 다시 읽힌다 — 헤더 참고.
        `polite`인 이유는 `.record-countdown`과 같다: 앞 화면의 낭독을 가로채지 않는다.
      */}
      <p className="type-body" role="status" aria-live="polite">
        {START_SCREEN_SUBTITLE}
      </p>
      <div className="test-start__dial" aria-hidden="true">
        <svg className="test-start__ring" viewBox="0 0 120 120">
          <circle className="test-start__track" cx="60" cy="60" r={RADIUS} />
          <circle
            className="test-start__value"
            cx="60"
            cy="60"
            r={RADIUS}
            strokeDasharray={CIRCUMFERENCE}
            strokeDashoffset={CIRCUMFERENCE * (1 - remaining)}
          />
        </svg>
        {/*
          key가 숫자라 초마다 새 노드가 선다 — 그래야 CSS 진입 애니메이션(`test-start-pop`)이
          매초 다시 돈다. 같은 노드의 텍스트만 바꾸면 애니메이션은 첫 마운트 한 번뿐이다.
        */}
        <span key={secondsLeft} className="test-start__count type-display">
          {secondsLeft}
        </span>
      </div>
    </main>
  )
}
