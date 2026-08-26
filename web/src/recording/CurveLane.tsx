/**
 * 곡선 레인 하나 — 앱 `CurveLane.kt`의 웹 대응 (KAN-56 Stage 5).
 *
 * 위 레인은 정적 가이드 곡선, 아래 레인은 녹음 중 자라는 사용자 곡선이다. 좌표는
 * `guideCurveDisplayPoints`·`userCurveDisplayPoints`가 만든 0..1 비율의 선분 목록이고
 * 여기서는 픽셀 크기만 곱한다 — 곡선 처리 규칙은 전부 저쪽(DOM 없이 테스트 가능)에 있다.
 *
 * 점이 없으면 빈 레인이다: 전부 무성이거나 정의가 곡선을 안 실어 보낸 경우고, 곡선은 없어도
 * 녹음은 성립하므로 오류 표시 없이 조용히 비워 둔다.
 *
 * ## 왜 폭을 재서 픽셀로 그리는가
 *
 * SVG로 늘리는 흔한 방법은 `viewBox="0 0 1 1"` + `preserveAspectRatio="none"`인데, 그러면
 * **선 굵기까지 같이 늘어난다.** 폭 320px·높이 100px 레인이면 가로가 세로의 3.2배로 늘어나
 * 2px 선이 방향에 따라 0.6px~6.4px 사이를 오간다 — 곡선이 오르내릴 때마다 굵기가 출렁인다.
 * 그래서 실제 픽셀 크기를 재서 `viewBox`를 그 크기로 잡고 좌표도 픽셀로 계산한다. 측정이
 * 아직 안 된 첫 렌더(그리고 폭을 알 수 없는 jsdom)에서는 [FALLBACK_WIDTH]로 그린다 —
 * 곡선의 모양은 비율이라 폭이 달라도 형태가 바뀌지 않고, 다음 프레임에 실측 폭으로 다시 그린다.
 * `vector-effect="non-scaling-stroke"`는 그래도 남을 수 있는 스케일 왜곡에 대한 보험이다.
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { smoothPathCommands, toSvgPath } from './curvePath'
import type { CurvePoint } from './guideCurve'

export interface CurveLaneProps {
  /** 레인 좌상단 라벨 (시안) */
  label: string
  /** 스크린 리더가 읽을 이름. 곡선 자체는 이미지라 형태를 말로 대신할 수 없다 */
  ariaLabel: string
  /** 0..1 비율 좌표의 선분 목록. 선분이 갈리는 이유는 `userCurve.ts`의 무성 구간 규칙 */
  segments: CurvePoint[][]
  /** 선 색. 토큰 참조(`var(--color-guide-curve)`)를 그대로 받는다 */
  color: string
  /** 점선 여부. 가이드에만 쓴다 */
  dashed: boolean
}

/** 곡선 굵기 (px). 앱의 2dp와 같다 */
const STROKE_WIDTH = 2

/** 점선 패턴 (px). 앱의 5dp/3dp와 같다 */
const DASH_PATTERN = '5 3'

/** 폭을 아직 재지 못했을 때 쓸 값. 시안 기준 콘텐츠 폭(`--content-max-width`)이다 */
const FALLBACK_WIDTH = 320

/** 그리기 영역 높이 (px). 레인 높이 120에서 위 라벨 자리(16)와 아래 여백(4)을 뺀 값이다 */
const DRAW_HEIGHT = 100

export function CurveLane({ label, ariaLabel, segments, color, dashed }: CurveLaneProps) {
  const svgRef = useRef<SVGSVGElement | null>(null)
  const [width, setWidth] = useState(FALLBACK_WIDTH)

  // 첫 측정은 그리기 직전(useLayoutEffect)에 한다 - useEffect로 미루면 폴백 폭으로 한 번
  // 그린 화면이 사용자 눈에 보였다가 바뀐다.
  useLayoutEffect(() => {
    const measured = svgRef.current?.clientWidth ?? 0
    if (measured > 0) setWidth(measured)
  }, [])

  // 이후의 폭 변화(회전·창 크기)는 ResizeObserver가 따라간다. jsdom에는 없는 API라
  // 존재 확인 후에만 붙인다 - 없으면 첫 측정값으로 계속 그린다.
  useEffect(() => {
    const element = svgRef.current
    if (element === null || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      const measured = element.clientWidth
      if (measured > 0) setWidth((current) => (current === measured ? current : measured))
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  return (
    <div className="curve-lane">
      <span className="type-caption curve-lane__label">{label}</span>
      <svg
        ref={svgRef}
        className="curve-lane__canvas"
        viewBox={`0 0 ${width} ${DRAW_HEIGHT}`}
        role="img"
        aria-label={ariaLabel}
      >
        {segments.map((points, index) =>
          points.length >= 2 ? (
            <path
              // 선분은 무성 구간이 가른 조각이라 순서가 곧 정체성이다 - index가 안정된 키다
              key={index}
              d={toSvgPath(smoothPathCommands(points, width, DRAW_HEIGHT))}
              fill="none"
              stroke={color}
              strokeWidth={STROKE_WIDTH}
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeDasharray={dashed ? DASH_PATTERN : undefined}
              vectorEffect="non-scaling-stroke"
            />
          ) : points.length === 1 ? (
            // 점이 하나뿐인 선분 - 선은 못 그리니 그 시각에 점 하나로 남긴다 (앱과 같다)
            <circle
              key={index}
              cx={points[0].x * width}
              cy={points[0].y * DRAW_HEIGHT}
              r={STROKE_WIDTH}
              fill={color}
            />
          ) : null,
        )}
      </svg>
    </div>
  )
}
