/**
 * 곡선 하나를 그리는 데 필요한 명령 — 앱 `CurvePath.kt`의 1:1 포팅 (KAN-56 Stage 5).
 *
 * 그리기 백엔드(SVG `d` 문자열)와 기하 계산을 가르는 자리다. 문자열은 만들어 놓고 나면
 * 되읽어 검사하기가 번거롭고, 무엇보다 **인과성**(아래 [smoothPathCommands])은 눈으로 확인할
 * 수 없는 성질이라 값으로 못박아야 한다. 그래서 "어떤 곡선을 그릴지"는 여기서 명령 목록으로
 * 만들고, [toSvgPath]는 그것을 문자열로 옮겨 담기만 한다.
 */

import type { CurvePoint } from './guideCurve'

/** 새 하위 경로를 시작한다 */
export interface MoveTo {
  kind: 'M'
  x: number
  y: number
}

/** 현재 점에서 직선 */
export interface LineTo {
  kind: 'L'
  x: number
  y: number
}

/** 현재 점에서 제어점 (cx, cy)를 거쳐 (x, y)까지 2차 베지어 */
export interface QuadTo {
  kind: 'Q'
  cx: number
  cy: number
  x: number
  y: number
}

export type PathCommand = MoveTo | LineTo | QuadTo

/**
 * 선분 하나를 부드럽게 그리는 명령 목록으로 바꾼다. 좌표는 0..1 비율이고 여기서
 * [width]·[height]를 곱해 픽셀로 만든다.
 *
 * 표준 중간점(midpoint) 2차 베지어다. `M(p0)` → `L(mid(p0,p1))`로 시작해, i = 1..n-2 각각에
 * 대해 점 p[i]를 **제어점**으로, mid(p[i], p[i+1])을 **끝점**으로 쓰는 `Q`를 잇고, 마지막에
 * `L(p[n-1])`로 닫는다. 점이 2개면 곡선 조각 없이 `M` → `L(mid)` → `L(p1)`이고, 2개 미만이면
 * 명령이 없다 (점 하나짜리 선분을 원으로 남기는 일은 그리는 쪽이 한다).
 *
 * ## 인과성
 *
 * 곡선 조각 하나가 (p[i-1], p[i], p[i+1]) 세 점만으로 확정된다. 그래서 32ms마다 점이 하나씩
 * 붙어도 **이미 그린 곡선 조각은 다시 계산되지 않는다** — n개 점의 명령 목록에서 꼬리 `L`
 * 하나를 뺀 나머지가 n+1개 점 명령 목록의 접두사와 정확히 같다.
 *
 * 점이 붙을 때 실제로 다시 그려지는 곳은 마지막 반 구간뿐이다: 직전 중간점에서 마지막 점까지
 * 그은 임시 직선이, 다음 점이 오면 그 구간을 지나는 베지어로 바뀐다. 시간으로는 프레임 간격의
 * 절반, 곧 16ms다. 곡선 조각의 모양이 다음 점에 달려 있는 한 마지막 조각은 미정이라 어떤
 * 스무딩으로도 이보다 줄일 수 없고, 정확히 0인 방법은 점을 그대로 잇는 직선 폴리라인뿐인데
 * 그 대가가 꺾임이다.
 *
 * 곡선이 점을 정확히 지나는 Catmull-Rom을 안 쓰는 이유도 여기 있다 — 구간 [p[i], p[i+1]]을
 * 정하려고 p[i+2]까지 봐야 해서 비인과 구간이 온전한 한 구간(32ms)으로 늘고, 제어점 밖으로
 * 부풀어 실제 F0에 없는 봉우리를 만든다. 자세한 근거는 `pitch-curve.md` §4.
 */
export function smoothPathCommands(
  points: CurvePoint[],
  width: number,
  height: number,
): PathCommand[] {
  if (points.length < 2) return []
  const x = (i: number) => points[i].x * width
  const y = (i: number) => points[i].y * height
  const midX = (i: number) => (x(i) + x(i + 1)) / 2
  const midY = (i: number) => (y(i) + y(i + 1)) / 2

  const commands: PathCommand[] = [
    { kind: 'M', x: x(0), y: y(0) },
    // 첫 반 구간은 곡선이 될 짝(p[-1])이 없다 - 중간점까지 직선으로 간다.
    { kind: 'L', x: midX(0), y: midY(0) },
  ]
  for (let i = 1; i < points.length - 1; i++) {
    commands.push({ kind: 'Q', cx: x(i), cy: y(i), x: midX(i), y: midY(i) })
  }
  // 마지막 점도 제어점이 될 짝(p[n])이 없다 - 직전 중간점에서 곧장 이어 붙인다.
  // 점이 더 오면 이 한 줄만 베지어로 바뀐다(위 인과성 문단).
  commands.push({ kind: 'L', x: x(points.length - 1), y: y(points.length - 1) })
  return commands
}

/**
 * 명령 목록을 SVG `path`의 `d` 속성으로 옮긴다. 앱 쪽 `CurveLane.toPath()`와 같은 자리 —
 * 계산은 하지 않고 표기만 바꾼다.
 *
 * 좌표를 소수 셋째 자리에서 끊는 이유는 문자열 길이다. 곡선이 300점이면 `d`가 수 KB가 되는데
 * 0.001px 아래의 차이는 어떤 화면에서도 픽셀이 되지 않는다.
 */
export function toSvgPath(commands: PathCommand[]): string {
  const n = (value: number) => Number(value.toFixed(3)).toString()
  return commands
    .map((command) => {
      switch (command.kind) {
        case 'M':
          return `M ${n(command.x)} ${n(command.y)}`
        case 'L':
          return `L ${n(command.x)} ${n(command.y)}`
        case 'Q':
          return `Q ${n(command.cx)} ${n(command.cy)} ${n(command.x)} ${n(command.y)}`
      }
    })
    .join(' ')
}
