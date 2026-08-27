/**
 * 종이 오리기 일러스트의 공통 재료 (KAN-161 3단계).
 *
 * ## 왜 토큰이 아니라 상수인가 (정본 §7)
 *
 * 일러스트는 그림 한 장이라 부분만 테마를 따라 바뀌면 선과 면이 어긋난다. 색을
 * `--color-*`로 끌어 쓰면 팔레트가 바뀌는 날 캐릭터의 얼굴만 다른 색이 되고, 반대로
 * `--color-illo-paper` 같은 이름을 토큰 표에 넣으면 화면 코드가 그걸 배경색으로 끌어다
 * 써서 "일러스트 전용"이라는 경계가 무너진다. 그래서 값을 여기 직접 박는다 — 이 파일이
 * 일러스트 색의 정본이고, 세 값 말고는 없다.
 *
 * ## 스티커 외곽선은 그림자가 아니라 필터다
 *
 * `.paper`는 크림 halo(4방향 2px)와 오프셋 그림자(3·4px)를 겹친 `drop-shadow` 다섯 겹이다.
 * `box-shadow`가 아니라 `filter: drop-shadow`인 이유는 대상이 사각형이 아니기 때문이다 —
 * box-shadow는 요소의 상자를 따라 그려지므로 SVG에 걸면 그림이 아니라 네모난 그늘이 생긴다.
 * drop-shadow는 알파 채널의 실루엣을 따라가서 오려 낸 종이의 가장자리가 그대로 나온다.
 * halo가 먼저 실루엣을 크림으로 한 겹 키우고, 마지막 한 겹이 그 키운 실루엣의 그늘을 놓는다.
 */

/** 일러스트 종이. 화면 배경이 무엇이든 소품의 종이는 이 크림 한 색이다 */
export const ILLO_PAPER = '#f3ecd9'
/** 일러스트 잉크. 선과 검정 면이 같은 값이다 — 오려 붙인 그림에 중간 톤은 없다 */
export const ILLO_INK = '#1c1a17'
/** 일러스트가 바닥에 떨구는 그늘. 화면 컴포넌트의 오프셋 그림자와 같은 거리(3·4)다 */
export const ILLO_SHADOW = '#cfc5aa'

/** 획 굵기. 그림 전체가 한 자루 펜으로 그린 것처럼 보이게 하나로 고정한다 */
const STROKE = 2.4

/** 스티커 외곽선. `style={{ filter: PAPER_FILTER }}`로 SVG 뿌리에 건다 */
export const PAPER_FILTER =
  `drop-shadow(2px 0 0 ${ILLO_PAPER}) drop-shadow(-2px 0 0 ${ILLO_PAPER})` +
  ` drop-shadow(0 2px 0 ${ILLO_PAPER}) drop-shadow(0 -2px 0 ${ILLO_PAPER})` +
  ` drop-shadow(3px 4px 0 ${ILLO_SHADOW})`

/** 선만 있는 획 (수염·팔·소리 표시) */
export const inkStroke = {
  stroke: ILLO_INK,
  strokeWidth: STROKE,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  fill: 'none',
} as const

/** 크림 면 + 잉크 테두리 — 오려 낸 종이 조각 하나 */
export const paperFill = {
  fill: ILLO_PAPER,
  stroke: ILLO_INK,
  strokeWidth: STROKE,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
} as const

/** 잉크로 꽉 찬 면 (머리카락·깃발) */
export const inkFill = {
  fill: ILLO_INK,
  stroke: ILLO_INK,
  strokeWidth: STROKE,
  strokeLinejoin: 'round',
} as const

/**
 * 망점 채움. 종이 오리기에서 "회색"을 만드는 유일한 수단이라 **화면당 한 곳**이다 (정본 §8).
 * 문항 화면에서는 곡선 레인이 그 한 곳이므로, 망점을 쓰는 일러스트는 곡선이 없는
 * 인트로·대기·결과 화면에만 선다.
 */
export function halftoneFill(patternId: string) {
  return {
    fill: `url(#${patternId})`,
    stroke: ILLO_INK,
    strokeWidth: STROKE,
    strokeLinejoin: 'round',
  } as const
}

/**
 * 망점 격자 정의. 5×5 칸에 반지름 1인 잉크 점 하나 (정본 §8).
 *
 * `patternUnits="userSpaceOnUse"`가 요점이다 — 무늬를 도형이 아니라 **좌표계**에 고정한다.
 * 도형 기준(`objectBoundingBox`)으로 두면 같은 무늬가 도형 크기에 따라 늘어나서, 나란히
 * 놓인 두 일러스트의 점 크기가 서로 달라진다.
 */
export function HalftonePattern({ id }: { id: string }) {
  return (
    <defs>
      <pattern id={id} width="5" height="5" patternUnits="userSpaceOnUse">
        <rect width="5" height="5" fill={ILLO_PAPER} />
        <circle cx="2.5" cy="2.5" r="1" fill={ILLO_INK} />
      </pattern>
    </defs>
  )
}
