import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { CurveCard } from './CurveCard'
import { CurveLane, type CurveLaneVariant } from './CurveLane'
import type { CurvePoint } from './guideCurve'

const points = (n: number): CurvePoint[] =>
  Array.from({ length: n }, (_, i) => ({ x: i / (n - 1), y: i % 2 === 0 ? 0.2 : 0.8 }))

function renderLane(segments: CurvePoint[][], variant: CurveLaneVariant = 'user') {
  const { container } = render(
    <CurveLane label="내 억양" ariaLabel="내 억양 곡선" segments={segments} variant={variant} />,
  )
  return container
}

/** 망점 채움을 뺀, 실제로 그린 곡선만. 사용자 레인은 선분마다 채움 path가 하나씩 더 있다 */
const strokePaths = (container: HTMLElement) =>
  Array.from(container.querySelectorAll('path')).filter(
    (path) => path.getAttribute('fill') === 'none',
  )

describe('CurveLane', () => {
  it('점이 둘 이상이면 곡선 하나를 그린다', () => {
    const container = renderLane([points(5)])

    const [path] = strokePaths(container)
    expect(path).not.toBeUndefined()
    // 명령 목록이 그대로 d 문자열이 된다 - 시작은 언제나 MoveTo다
    expect(path.getAttribute('d')!.startsWith('M ')).toBe(true)
    expect(path.getAttribute('d')).toContain('Q ')
  })

  it('점이 하나면 선 대신 점을 찍는다', () => {
    const container = renderLane([[{ x: 0.5, y: 0.5 }]])

    expect(container.querySelector('path')).toBeNull()
    const circle = container.querySelector('circle')
    expect(circle).not.toBeNull()
    // 폴백 폭 320, 그리기 높이 92(DRAW_HEIGHT) 기준 한가운데
    expect(circle!.getAttribute('cx')).toBe('160')
    expect(circle!.getAttribute('cy')).toBe('46')
  })

  it('창을 벗어나 y=0·y=1로 눌린 구간도 선 굵기만큼 안쪽에 그린다 - 가장자리에서 잘리지 않는다', () => {
    // userCurveDisplayPoints가 ±7 semitone 밖을 0·1로 클램프한 모양: 천장 평선 → 바닥 평선
    const clamped: CurvePoint[] = [
      { x: 0, y: 0 },
      { x: 0.25, y: 0 },
      { x: 0.5, y: 0.5 },
      { x: 0.75, y: 1 },
      { x: 1, y: 1 },
    ]
    const [path] = strokePaths(renderLane([clamped], 'user'))
    const d = path.getAttribute('d')!
    // 굵기 3 → 절반 1.5를 위아래로 들인다. 시작(천장)은 1.5, 끝(바닥)은 90.5, 가운데는 그대로 46
    expect(d.startsWith('M 0 1.5 ')).toBe(true)
    expect(d.endsWith('L 320 90.5')).toBe(true)
    expect(d).toContain('Q 160 46 ')
    // 어느 좌표도 그리기 영역 [1.5, 90.5] 밖에 없다
    const ys = d.match(/-?\d+(\.\d+)?/g)!.map(Number).filter((_, i) => i % 2 === 1)
    expect(Math.min(...ys)).toBeGreaterThanOrEqual(1.5)
    expect(Math.max(...ys)).toBeLessThanOrEqual(90.5)

    // 가이드(굵기 2)는 1씩 들인다
    const [guide] = strokePaths(renderLane([clamped], 'guide'))
    expect(guide.getAttribute('d')!.startsWith('M 0 1 ')).toBe(true)
  })

  it('경계의 고립점은 반지름만큼 들인다 - 선 굵기 절반으로는 지름의 1/4이 여전히 잘린다', () => {
    // 원 반지름이 굵기(3)라 굵기 절반(1.5)만 들이면 위로 1.5px가 viewBox 밖이다. 반지름만큼 들여
    // 천장 점의 중심은 3, 바닥 점의 중심은 92 − 3 = 89.
    const top = renderLane([[{ x: 0.5, y: 0 }]]).querySelector('circle')!
    expect(top.getAttribute('r')).toBe('3')
    expect(top.getAttribute('cy')).toBe('3')
    const bottom = renderLane([[{ x: 0.5, y: 1 }]]).querySelector('circle')!
    expect(bottom.getAttribute('cy')).toBe('89')
  })

  it('선분이 갈리면 곡선도 따로 그린다 - 쉼 구간을 가로지르는 가짜 사선이 없다', () => {
    const container = renderLane([points(3), points(4)])

    expect(strokePaths(container).length).toBe(2)
  })

  it('가이드는 얇은 점선, 내 억양은 굵은 실선이다 - 색각 이상에서도 갈린다', () => {
    const [guide] = strokePaths(renderLane([points(3)], 'guide'))
    expect(guide.getAttribute('stroke-dasharray')).toBe('6 5')
    expect(guide.getAttribute('stroke-width')).toBe('2')

    const [user] = strokePaths(renderLane([points(3)], 'user'))
    expect(user.getAttribute('stroke-dasharray')).toBeNull()
    expect(user.getAttribute('stroke-width')).toBe('3')
  })

  it('내 억양만 곡선 아래를 망점으로 채운다 - 망점은 화면당 한 곳이다', () => {
    const user = renderLane([points(4)], 'user')
    const fill = Array.from(user.querySelectorAll('path')).find((path) =>
      path.getAttribute('fill')?.startsWith('url(#'),
    )
    expect(fill).not.toBeUndefined()
    // 곡선 끝에서 바닥으로 내려가 시작점 아래까지 닫은 도형이다
    expect(fill!.getAttribute('d')).toMatch(/L \d+(\.\d+)? 92 L \d+(\.\d+)? 92 Z$/)
    expect(user.querySelector('pattern')).not.toBeNull()

    const guide = renderLane([points(4)], 'guide')
    expect(guide.querySelector('pattern')).toBeNull()
  })

  it('그릴 점이 없으면 빈 레인이다 - 오류를 말하지 않는다', () => {
    const container = renderLane([])

    expect(container.querySelector('path')).toBeNull()
    expect(container.querySelector('circle')).toBeNull()
    expect(screen.getByRole('img', { name: '내 억양 곡선' })).toBeInTheDocument()
  })

  it('곡선은 이름을 가진 이미지다 - 형태를 말로 대신할 수 없다', () => {
    renderLane([points(3)])

    expect(screen.getByRole('img', { name: '내 억양 곡선' })).toBeInTheDocument()
  })
})

describe('CurveCard', () => {
  it('가이드 레인이 위, 내 억양 레인이 아래다', () => {
    render(<CurveCard guidePoints={points(4)} userSegments={[points(3)]} />)

    const lanes = screen.getAllByRole('img')
    expect(lanes.map((lane) => lane.getAttribute('aria-label'))).toEqual([
      '가이드 억양 곡선',
      '내 억양 곡선',
    ])
  })

  it('가이드가 비어 있어도 레인 자리는 남는다', () => {
    render(<CurveCard guidePoints={[]} userSegments={[]} />)

    expect(screen.getByRole('img', { name: '가이드 억양 곡선' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: '내 억양 곡선' })).toBeInTheDocument()
  })
})
