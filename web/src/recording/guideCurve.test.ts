import { describe, expect, it } from 'vitest'
import { guideCurveDisplayPoints } from './guideCurve'
import { REAL_GUIDE_F0, REAL_GUIDE_F0_ITEM } from './guideF0Fixture'

describe('guideCurveDisplayPoints (앱 GuideCurveTest 이식)', () => {
  it('전부 무성이면 그릴 점이 없다', () => {
    expect(guideCurveDisplayPoints([null, null, null])).toEqual([])
    expect(guideCurveDisplayPoints([])).toEqual([])
  })

  it('NaN도 무성으로 취급한다', () => {
    expect(guideCurveDisplayPoints([Number.NaN])).toEqual([])
  })

  it('높은 음이 위로 간다 - 값이 클수록 y가 작다', () => {
    const points = guideCurveDisplayPoints([-1, 0, 1, 2])
    for (let k = 0; k < points.length - 1; k++) {
      expect(points[k].y).toBeGreaterThan(points[k + 1].y)
    }
  })

  it('0은 무성이 아니라 유효한 semitone 값이다', () => {
    // 0을 무성으로 잘못 취급하면 세 점이 아니라 두 점이 나온다
    expect(guideCurveDisplayPoints([-1, 0, 1].map((v) => v)).length).toBe(3)
  })

  it('중간 무성 구간은 양옆 값의 선형 보간으로 이어진다', () => {
    const points = guideCurveDisplayPoints([0, null, null, 3])
    expect(points.length).toBe(4)
    // 0→3 사이 두 무성 프레임은 1, 2로 채워진다. y 간격이 균일한지로 확인한다.
    const gaps = [0, 1, 2].map((i) => points[i].y - points[i + 1].y)
    gaps.forEach((gap) => expect(gap).toBeCloseTo(gaps[0], 5))
  })

  it('앞뒤 무성 구간은 그리지 않되 x 위치는 원래 시각을 유지한다', () => {
    const points = guideCurveDisplayPoints([null, 1, 2, 1, null, null])
    expect(points.length).toBe(3)
    // 배열 길이 6 → x 간격은 1/5. 첫 유성 프레임은 index 1이므로 x = 0.2에서 시작한다.
    expect(points[0].x).toBeCloseTo(0.2, 5)
    expect(points[points.length - 1].x).toBeCloseTo(0.6, 5)
  })

  it('x는 시간축 전체를 0에서 1로 나눈 위치다', () => {
    expect(guideCurveDisplayPoints([1, 2, 3]).map((p) => p.x)).toEqual([0, 0.5, 1])
  })

  it('표시 스케일 여백 - 최고점과 최저점이 레인 가장자리에 붙지 않는다', () => {
    const points = guideCurveDisplayPoints([-2, 5])
    points.forEach((point) => {
      expect(point.y).toBeGreaterThan(0.05)
      expect(point.y).toBeLessThan(0.95)
    })
    // 여백 10% 기준 최고점 y = 1 - 1.1/1.2 ≈ 0.0833
    const ys = points.map((p) => p.y)
    expect(Math.min(...ys)).toBeCloseTo(0.0833, 3)
    expect(Math.max(...ys)).toBeCloseTo(0.9167, 3)
  })

  it('평평한 곡선은 레인 중앙에 그린다', () => {
    guideCurveDisplayPoints([1.5, 1.5, 1.5]).forEach((point) => {
      expect(point.y).toBeCloseTo(0.5, 5)
    })
  })

  it('거의 평평한 곡선의 미세 잡음은 레인 전체로 증폭되지 않는다', () => {
    // 부동소수 잡음 수준(1e-9 semitone)의 등락. 자기 스케일만 있으면 이게 전폭으로 튄다 —
    // 표시 범위 바닥값(0.5 semitone)이 잡음을 중앙 부근에 눌러 둔다.
    guideCurveDisplayPoints([1, 1 + 1e-9, 1]).forEach((point) => {
      expect(point.y).toBeCloseTo(0.5, 3)
    })
  })

  it('유성 프레임이 하나뿐이면 그 시각에 점 하나다', () => {
    const points = guideCurveDisplayPoints([null, 2, null])
    expect(points.length).toBe(1)
    expect(points[0].x).toBeCloseTo(0.5, 5)
    expect(points[0].y).toBeCloseTo(0.5, 5) // 값 하나는 range 0 - 중앙
  })
})

/**
 * 위 케이스들이 규칙을 하나씩 못박는다면, 여기는 그 규칙들이 실제로 내려가는 데이터에서
 * 함께 굴러가는지를 본다. 발행본 145문항 전수는 `publishedGuideF0.test.ts`가 맡고,
 * 이 파일에서는 무성 null이 가장 많은 대표 문항 하나를 끝까지 따라간다.
 */
describe('발행본 실문항 곡선 (KAN-194)', () => {
  const values = REAL_GUIDE_F0.values
  const points = guideCurveDisplayPoints(values)

  it('무성 구간을 보간해 240점을 하나도 버리지 않고 그린다', () => {
    expect(REAL_GUIDE_F0_ITEM.itemId).toBe('v102')
    expect(values.length).toBe(240)
    expect(values.filter((value) => value === null).length).toBe(14)
    // 앞뒤 가장자리가 유성이라 첫 프레임부터 끝 프레임까지 다 들어온다 - 중간 null은
    // 버려지는 게 아니라 양옆 값의 보간으로 메워진다
    expect(points.length).toBe(240)
    expect(points[0].x).toBe(0)
    expect(points[points.length - 1].x).toBe(1)
  })

  it('보간된 무성 구간은 양옆 유성 값 사이를 고르게 잇는다', () => {
    // index 125(-0.48)에서 140(-1.8)으로 내려가는 구간이 통째로 무성이다.
    // 값이 내려가므로 화면에서는 y가 단조 증가하고, 보간이라 간격이 균일하다
    const hole = points.slice(125, 141)
    const gaps = hole.slice(0, -1).map((point, k) => hole[k + 1].y - point.y)
    gaps.forEach((gap) => {
      expect(gap).toBeGreaterThan(0)
      expect(gap).toBeCloseTo(gaps[0], 10)
    })
  })

  it('실측 범위(-5.1~5.44)가 여백 안에 들어와 레인 밖으로 안 넘친다', () => {
    points.forEach((point) => {
      expect(point.y).toBeGreaterThan(0)
      expect(point.y).toBeLessThan(1)
    })
    // 자기 min/max 스케일이라 최고·최저점이 여백 10%만큼 가장자리에서 떨어져 있다
    const ys = points.map((point) => point.y)
    expect(Math.min(...ys)).toBeCloseTo(0.0833, 3)
    expect(Math.max(...ys)).toBeCloseTo(0.9167, 3)
  })
})
