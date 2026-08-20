/**
 * 진척도 (KAN-148). 진행바와 "3/10" 표기를 한 덩어리로 묶는다 — 둘이 떨어져 있으면
 * 한쪽만 고쳐 숫자와 막대가 어긋나는 날이 온다.
 *
 * `<progress>`를 쓰는 이유: role=progressbar와 value/max 의미론을 브라우저가 그냥 준다.
 * div로 그리면 aria를 손으로 채워야 하고 얻는 게 없다.
 *
 * 값이 1부터 시작하는 건 호출자 몫이자 의도다 — 첫 문항을 0/10으로 보이면 아직 시작도
 * 안 한 느낌이라 이탈이 는다 (ux-ui.md §3 Goal-Gradient, endowed progress).
 */

export interface ProgressIndicatorProps {
  current: number
  total: number
  label?: string
}

export function ProgressIndicator({ current, total, label = '문항 진행률' }: ProgressIndicatorProps) {
  return (
    <div className="progress-indicator">
      <progress className="progress-indicator__bar" aria-label={label} value={current} max={total} />
      <p className="type-caption progress-indicator__count">
        {current} / {total}
      </p>
    </div>
  )
}
