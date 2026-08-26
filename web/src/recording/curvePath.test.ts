import { describe, expect, it } from 'vitest'
import { smoothPathCommands, toSvgPath, type PathCommand } from './curvePath'
import type { CurvePoint } from './guideCurve'

const width = 100
const height = 40

/** x는 고르게, y는 오르내리게 - 중간점이 원래 점과 겹치지 않아야 검사가 의미를 갖는다 */
function points(n: number): CurvePoint[] {
  return Array.from({ length: n }, (_, i) => ({ x: i / 10, y: i % 2 === 0 ? 0.2 : 0.8 }))
}

const commands = (n: number) => smoothPathCommands(points(n), width, height)
const px = (i: number) => points(20)[i].x * width
const py = (i: number) => points(20)[i].y * height

describe('smoothPathCommands (앱 CurvePathTest 이식)', () => {
  it('점이 2개면 중간점을 거치는 직선 두 도막이다', () => {
    expect(commands(2)).toEqual([
      { kind: 'M', x: px(0), y: py(0) },
      { kind: 'L', x: (px(0) + px(1)) / 2, y: (py(0) + py(1)) / 2 },
      { kind: 'L', x: px(1), y: py(1) },
    ])
  })

  it('점이 2개 미만이면 명령이 없다 - 원 그리기는 CurveLane이 한다', () => {
    expect(smoothPathCommands([], width, height)).toEqual([])
    expect(smoothPathCommands(points(1), width, height)).toEqual([])
  })

  it('점이 붙어도 이미 그린 곡선은 다시 계산되지 않는다 - 인과성', () => {
    // n개 명령에서 꼬리 L 하나를 뺀 나머지 == n+1개 명령의 접두사.
    // 다시 그려지는 곳은 마지막 반 구간(직전 중간점 -> 마지막 점, 16ms)뿐이다.
    for (let n = 2; n <= 8; n++) {
      const settled = commands(n).slice(0, -1)
      const next = commands(n + 1)

      expect(settled.length).toBeLessThanOrEqual(next.length)
      expect(next.slice(0, settled.length)).toEqual(settled)
    }
  })

  it('점 하나가 늘 때 명령도 하나만 는다', () => {
    // 접두사만 보면 "새 점이 아무것도 안 그렸다"도 통과한다 - 자라기는 자라야 한다.
    for (let n = 2; n <= 8; n++) {
      expect(commands(n + 1).length).toBe(commands(n).length + 1)
    }
  })

  it('모든 Q는 제어점이 원래 점이고 끝점이 이웃과의 중간점이다', () => {
    const n = 6
    const list = commands(n)
    const quads = list.filter((command): command is Extract<PathCommand, { kind: 'Q' }> =>
      command.kind === 'Q',
    )

    // i = 1..n-2 각각 하나씩.
    expect(quads.length).toBe(n - 2)
    quads.forEach((quad, index) => {
      const i = index + 1
      expect(quad.cx).toBe(px(i))
      expect(quad.cy).toBe(py(i))
      expect(quad.x).toBe((px(i) + px(i + 1)) / 2)
      expect(quad.y).toBe((py(i) + py(i + 1)) / 2)
    })
    // 곡선은 첫 중간점에서 시작해 마지막 점으로 닫힌다.
    expect(list[0]).toEqual({ kind: 'M', x: px(0), y: py(0) })
    expect(list[1]).toEqual({ kind: 'L', x: (px(0) + px(1)) / 2, y: (py(0) + py(1)) / 2 })
    expect(list[list.length - 1]).toEqual({ kind: 'L', x: px(n - 1), y: py(n - 1) })
  })

  it('비율 좌표에 캔버스 크기를 곱한다', () => {
    const scaled = smoothPathCommands(points(3), width * 2, height * 2)

    expect(scaled[0]).toEqual({ kind: 'M', x: px(0) * 2, y: py(0) * 2 })
    expect(scaled[scaled.length - 1]).toEqual({ kind: 'L', x: px(2) * 2, y: py(2) * 2 })
  })
})

describe('toSvgPath', () => {
  it('명령을 그대로 d 문자열로 옮긴다', () => {
    const d = toSvgPath([
      { kind: 'M', x: 0, y: 8 },
      { kind: 'L', x: 5, y: 20 },
      { kind: 'Q', cx: 10, cy: 32, x: 15, y: 20 },
    ])

    expect(d).toBe('M 0 8 L 5 20 Q 10 32 15 20')
  })

  it('소수는 셋째 자리에서 끊는다 - 그 아래는 어떤 화면에서도 픽셀이 되지 않는다', () => {
    expect(toSvgPath([{ kind: 'M', x: 1 / 3, y: 2 / 3 }])).toBe('M 0.333 0.667')
  })

  it('명령이 없으면 빈 문자열이다', () => {
    expect(toSvgPath([])).toBe('')
  })
})
