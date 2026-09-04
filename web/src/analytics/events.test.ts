import { describe, expect, it } from 'vitest'
import { overallBucket } from './events'

describe('overallBucket — 종합 점수를 10점 단위로 (KAN-21 계기판, FR-AN-09)', () => {
  it('내림으로 묶는다', () => {
    expect(overallBucket(0)).toBe(0)
    expect(overallBucket(9)).toBe(0)
    expect(overallBucket(10)).toBe(10)
    expect(overallBucket(67)).toBe(60)
  })

  it('100은 100 버킷에 그대로 둔다 — 만점만 따로 세는 편이 맞다', () => {
    expect(overallBucket(100)).toBe(100)
  })

  it('범위 밖 값은 0~100으로 자른다 — 집계 축에 -10이 생기면 사람이 다시 읽어야 한다', () => {
    expect(overallBucket(-5)).toBe(0)
    expect(overallBucket(140)).toBe(100)
  })

  it('숫자가 아닌 값(NaN)은 0으로 접는다', () => {
    expect(overallBucket(Number.NaN)).toBe(0)
  })
})
