/**
 * 곡선 레인 하나 — 앱 `CurveLane.kt`의 웹 대응 (KAN-56 Stage 5, 형태는 KAN-161 2단계).
 *
 * 위 레인은 정적 가이드 곡선, 아래 레인은 녹음 중 자라는 사용자 곡선이다. 좌표는
 * `guideCurveDisplayPoints`·`userCurveDisplayPoints`가 만든 0..1 비율의 선분 목록이고
 * 여기서는 픽셀 크기만 곱한다 — 곡선 처리 규칙은 전부 저쪽(DOM 없이 테스트 가능)에 있다.
 *
 * 점이 없으면 빈 레인이다: 전부 무성이거나 정의가 곡선을 안 실어 보낸 경우고, 곡선은 없어도
 * 녹음은 성립하므로 오류 표시 없이 조용히 비워 둔다.
 *
 * ## 두 곡선을 무엇이 가르는가
 *
 * 팔레트가 잉크 한 색이라 색으로는 아무것도 못 가른다 (정본 §7). 대신 셋이 함께 가른다 —
 * 가이드는 얇은 점선, 내 억양은 굵은 실선에 곡선 아래가 망점으로 차 있다. 망점(halftone)은
 * 종이 오리기 인쇄물의 회색 표현이고, 이 앱에서 **화면당 한 곳**만 쓰기로 한 무늬다:
 * 곡선 레인이 그 한 곳이라 다른 컴포넌트에는 망점이 없다.
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

import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { smoothPathCommands, toSvgPath } from './curvePath'
import type { CurvePoint } from './guideCurve'

/**
 * 레인의 성격. 선 굵기·점선·망점·색이 함께 움직이므로 하나로 묶는다 — 넷을 따로 받으면
 * 호출자마다 조합이 달라져 "가이드처럼 생긴 내 곡선"이 만들어진다.
 */
export type CurveLaneVariant = 'guide' | 'user'

export interface CurveLaneProps {
  /** 레인 좌상단 라벨 (시안) */
  label: string
  /** 스크린 리더가 읽을 이름. 곡선 자체는 이미지라 형태를 말로 대신할 수 없다 */
  ariaLabel: string
  /** 0..1 비율 좌표의 선분 목록. 선분이 갈리는 이유는 `userCurve.ts`의 무성 구간 규칙 */
  segments: CurvePoint[][]
  variant: CurveLaneVariant
}

/** 곡선 굵기 (px). 가이드는 얇고 내 억양은 굵다 — 앱의 2dp/3dp와 같다 */
const GUIDE_STROKE_WIDTH = 2
const USER_STROKE_WIDTH = 3

/** 점선 패턴 (px). 가이드에만 쓴다. 앱의 6dp/5dp와 같다 */
const DASH_PATTERN = '6 5'

/** 망점 한 칸의 크기와 점 반지름 (px). 앱의 `HALFTONE_STEP`·`HALFTONE_DOT`과 같다 */
const HALFTONE_STEP = 5
const HALFTONE_DOT = 1

/** 망점의 진하기. 1이면 곡선 아래가 잉크 면이 되어 곡선 자체가 안 보인다 */
const HALFTONE_OPACITY = 0.5

/** 폭을 아직 재지 못했을 때 쓸 값. 시안 기준 콘텐츠 폭(`--content-max-width`)이다 */
const FALLBACK_WIDTH = 320

/** 그리기 영역 높이 (px). 레인 높이 120에서 위 라벨 자리(16)와 아래 여백(4)을 뺀 값이다 */
const DRAW_HEIGHT = 100

export function CurveLane({ label, ariaLabel, segments, variant }: CurveLaneProps) {
  const svgRef = useRef<SVGSVGElement | null>(null)
  const [width, setWidth] = useState(FALLBACK_WIDTH)

  // 한 화면에 레인이 둘이면 `<pattern>`의 id가 겹친다 - 겹치면 나중에 그린 쪽이 앞의 정의를
  // 덮어써 두 레인이 같은 무늬를 공유하게 된다. useId가 인스턴스마다 다른 이름을 준다.
  // url(#...) 안에 들어가므로 React가 붙이는 구분 기호는 털어 낸다.
  const patternId = `halftone-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`

  const isUser = variant === 'user'
  // 채울 곡선이 있을 때만 무늬를 정의한다 - 빈 레인에 <pattern> 안의 점이 남으면 화면에는
  // 안 보여도 DOM에는 곡선이 있는 것처럼 보인다
  const hasCurve = segments.some((points) => points.length >= 2)
  const halftone = isUser && hasCurve
  const color = isUser ? 'var(--color-user-curve)' : 'var(--color-guide-curve)'
  const strokeWidth = isUser ? USER_STROKE_WIDTH : GUIDE_STROKE_WIDTH

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
        {halftone && (
          <defs>
            {/*
              망점 한 칸. `userSpaceOnUse`라 무늬가 곡선을 따라 늘어나지 않고 레인 좌표에
              고정된다 - 곡선이 자랄 때 이미 찍힌 점이 움직이면 무늬가 살아 있는 것처럼 보인다.
            */}
            <pattern
              id={patternId}
              width={HALFTONE_STEP}
              height={HALFTONE_STEP}
              patternUnits="userSpaceOnUse"
            >
              <rect
                width={HALFTONE_STEP}
                height={HALFTONE_STEP}
                fill="var(--color-curve-lane-surface)"
              />
              <circle
                cx={HALFTONE_STEP / 2}
                cy={HALFTONE_STEP / 2}
                r={HALFTONE_DOT}
                fill={color}
              />
            </pattern>
          </defs>
        )}
        {/*
          채움을 먼저 그리고 선을 나중에 그린다 - 순서가 바뀌면 망점이 곡선 위를 덮어
          선이 점무늬에 잠긴다.
        */}
        {halftone &&
          segments.map((points, index) =>
            points.length >= 2 ? (
              <path
                key={index}
                d={fillPath(points, width)}
                fill={`url(#${patternId})`}
                opacity={HALFTONE_OPACITY}
                stroke="none"
              />
            ) : null,
          )}
        {segments.map((points, index) =>
          points.length >= 2 ? (
            <path
              // 선분은 무성 구간이 가른 조각이라 순서가 곧 정체성이다 - index가 안정된 키다
              key={index}
              d={toSvgPath(smoothPathCommands(points, width, DRAW_HEIGHT))}
              fill="none"
              stroke={color}
              strokeWidth={strokeWidth}
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeDasharray={isUser ? undefined : DASH_PATTERN}
              vectorEffect="non-scaling-stroke"
            />
          ) : points.length === 1 ? (
            // 점이 하나뿐인 선분 - 선은 못 그리니 그 시각에 점 하나로 남긴다 (앱과 같다)
            <circle
              key={index}
              cx={points[0].x * width}
              cy={points[0].y * DRAW_HEIGHT}
              r={strokeWidth}
              fill={color}
            />
          ) : null,
        )}
      </svg>
    </div>
  )
}

/**
 * 곡선 아래를 닫은 도형. 곡선 끝에서 바닥으로 내려가고, 바닥을 따라 시작점 아래까지 간 뒤
 * 닫는다 — 선분이 레인 폭 전체를 쓰지 않아도(녹음이 짧으면 왼쪽만 차 있다) 채운 면이
 * 곡선 밑에만 남는다.
 */
function fillPath(points: CurvePoint[], width: number): string {
  const curve = toSvgPath(smoothPathCommands(points, width, DRAW_HEIGHT))
  const startX = points[0].x * width
  const endX = points[points.length - 1].x * width
  return `${curve} L ${endX} ${DRAW_HEIGHT} L ${startX} ${DRAW_HEIGHT} Z`
}
