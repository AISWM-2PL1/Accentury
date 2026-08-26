import { describe, expect, it } from 'vitest'
import { FULL_SCALE_INT16, floatToInt16 } from './pcm'

describe('floatToInt16', () => {
  it('±1.0을 부호 대칭인 최대치로 옮긴다', () => {
    const pcm = floatToInt16(Float32Array.from([1, -1, 0]))
    expect(Array.from(pcm)).toEqual([FULL_SCALE_INT16, -FULL_SCALE_INT16, 0])
  })

  it('범위를 넘은 값은 던지지 않고 최대치로 눕힌다', () => {
    const pcm = floatToInt16(Float32Array.from([1.5, -2.3, 1000, -1000]))
    expect(Array.from(pcm)).toEqual([
      FULL_SCALE_INT16,
      -FULL_SCALE_INT16,
      FULL_SCALE_INT16,
      -FULL_SCALE_INT16,
    ])
  })

  it('32768이 아닌 32767을 곱해 +1.0이 음수로 뒤집히지 않는다', () => {
    // 32768을 곱했다면 +32768이 Int16 범위를 넘어 -32768로 감겼을 자리다.
    expect(floatToInt16(Float32Array.from([1]))[0]).toBeGreaterThan(0)
  })

  it('중간 진폭을 반올림해 담는다', () => {
    const pcm = floatToInt16(Float32Array.from([0.5, -0.5, 0.25]))
    // 0.5 × 32767 = 16383.5 → Math.round는 .5를 +∞ 쪽으로 올린다.
    expect(pcm[0]).toBe(16384)
    expect(pcm[1]).toBe(-16383)
    expect(pcm[2]).toBe(Math.round(0.25 * FULL_SCALE_INT16))
  })

  it('빈 입력은 빈 배열을 만든다', () => {
    expect(floatToInt16(new Float32Array(0)).length).toBe(0)
  })

  it('입력 길이를 그대로 유지한다', () => {
    expect(floatToInt16(new Float32Array(1234)).length).toBe(1234)
  })
})
