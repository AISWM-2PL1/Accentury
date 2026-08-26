/**
 * 억양 곡선 카드 — 앱 녹음 화면의 `CurveCard`와 같은 자리·같은 구성 (KAN-56 Stage 5).
 *
 * 위가 가이드(점선), 아래가 내 억양(실선)이다. 두 레인을 **색이 아니라 선 모양으로도** 가르는
 * 것이 요점이다 — 색각 이상에서도 어느 쪽이 내 곡선인지 알 수 있어야 한다 (WCAG 1.4.1).
 *
 * 두 레인의 세로축은 둘 다 semitone이지만 **시간축은 서로 다르다.** 가이드는 자기 길이로
 * 레인 폭 전체를 쓰고 사용자 레인은 미끄러지는 창을 쓴다 — 같은 x가 같은 시각이 아니다.
 * 그렇게 둔 근거는 `pitch-curve.md` §4에 있다: 정렬하면 발화가 길수록 가이드가 왼쪽 구석에
 * 눌려 정작 비교하라고 놓은 곡선이 더 안 보인다. 두 레인은 시각을 맞춰 보는 도구가 아니라
 * 오르내림의 방향과 폭을 견주는 도구다.
 */

import { CurveLane } from './CurveLane'
import type { CurvePoint } from './guideCurve'

export interface CurveCardProps {
  /** `guideCurveDisplayPoints`가 만든 정적 가이드 곡선. 빈 배열이면 레인만 남는다 */
  guidePoints: CurvePoint[]
  /** `userCurveDisplayPoints`가 만든 선분 목록 */
  userSegments: CurvePoint[][]
}

export function CurveCard({ guidePoints, userSegments }: CurveCardProps) {
  return (
    <div className="curve-card">
      <span className="type-label curve-card__title">억양 곡선</span>
      {/* 가이드는 무성 구간을 보간으로 이어 둔 하나짜리 폴리라인이라 선분 하나로 감싼다 */}
      <CurveLane
        label="가이드"
        ariaLabel="가이드 억양 곡선"
        segments={guidePoints.length > 0 ? [guidePoints] : []}
        color="var(--color-guide-curve)"
        dashed
      />
      <CurveLane
        label="내 억양"
        ariaLabel="내 억양 곡선"
        segments={userSegments}
        color="var(--color-user-curve)"
        dashed={false}
      />
    </div>
  )
}
