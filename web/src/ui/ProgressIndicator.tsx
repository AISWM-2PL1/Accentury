/**
 * 진척도 (KAN-148, 형태는 KAN-161 2단계). 도트 줄과 "3 / 10" 표기를 한 덩어리로 묶는다 —
 * 둘이 떨어져 있으면 한쪽만 고쳐 숫자와 도트가 어긋나는 날이 온다.
 *
 * 막대 하나였는데 문항 수만큼의 캡슐로 바꿨다. 남은 문항을 세어 볼 수 있고, 칸 하나가
 * 채워지는 것이 막대가 조금 자라는 것보다 "한 문항 넘어갔다"로 읽힌다.
 *
 * `<progress>`를 버린 대가로 role·값 의미론을 손으로 채운다. 그래도 도트가 이기는 이유는
 * 형태다 — 완료·현재·미완료를 색이 아니라 채움과 테두리 두께로 갈라야 하는데(정본 §7),
 * `<progress>`의 내장 모양에는 "현재 칸" 같은 개념이 없다.
 *
 * 값이 1부터 시작하는 건 호출자 몫이자 의도다 — 첫 문항을 0/10으로 보이면 아직 시작도
 * 안 한 느낌이라 이탈이 는다 (ux-ui.md §3 Goal-Gradient, endowed progress).
 */

/** 도트 하나의 상태. 스타일은 `data-state`로 CSS가 갖고, 여기서는 이름만 정한다 */
export type ProgressDotState = 'done' | 'current' | 'todo'

export interface ProgressIndicatorProps {
  current: number
  total: number
  label?: string
  /** 숫자 뒤에 붙는 한 마디 (예: `음성` → "3 / 10 · 음성"). 시각 전용이라 읽히지 않는다 */
  note?: string
}

export function ProgressIndicator({
  current,
  total,
  label = '문항 진행률',
  note,
}: ProgressIndicatorProps) {
  return (
    <div className="progress-indicator">
      {/*
        값을 읽는 것은 이 줄 하나다. 도트 열 개가 각각 읽히면 스크린 리더가 같은 정보를
        열 번 말하므로 개별 도트는 aria-hidden으로 빼고, 아래 숫자도 의미론에서 뺀다
        (시각적으로는 남는다).
      */}
      <div
        className="progress-indicator__dots"
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={total}
        aria-valuenow={current}
      >
        {Array.from({ length: Math.max(total, 0) }, (_, index) => (
          <span
            key={index}
            className="progress-indicator__dot"
            data-state={dotState(index + 1, current)}
            aria-hidden="true"
          />
        ))}
      </div>
      <p className="type-caption progress-indicator__count" aria-hidden="true">
        {current} / {total}
        {note !== undefined && ` · ${note}`}
      </p>
    </div>
  )
}

/**
 * 몇 번째 칸이 어떤 상태인가. 계산을 떼어 둔 이유는 경계다 — `position === current`가
 * 현재 칸이고 그보다 앞이 완료인데, 부등호를 한 칸 잘못 쓰면 진행이 통째로 밀린다.
 */
function dotState(position: number, current: number): ProgressDotState {
  if (position < current) return 'done'
  if (position === current) return 'current'
  return 'todo'
}
