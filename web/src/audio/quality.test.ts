import { describe, expect, it } from 'vitest'
import {
  CLIP_RATIO_THRESHOLD,
  FULL_SCALE,
  judge,
  measure,
  SILENCE_SAMPLE_THRESHOLD,
  type ClientQuality,
} from './quality'
import { TARGET_SAMPLE_RATE } from './pcm'

/**
 * 앱 `AudioQualityTest`의 `pcmOfSeconds`와 같은 신호다. 샘플마다 부호가 뒤집히는 사각파라
 * 모든 샘플의 절댓값이 amplitude로 같아 RMS·peak가 amplitude와 정확히 일치한다 —
 * 임계값 검증에 계산 오차가 끼지 않는다.
 */
function pcmOfSeconds(seconds: number, amplitude: number): Int16Array {
  const pcm = new Int16Array(Math.trunc(TARGET_SAMPLE_RATE * seconds))
  for (let i = 0; i < pcm.length; i++) pcm[i] = i % 2 === 0 ? amplitude : -amplitude
  return pcm
}

/** 임계 비율(1%)을 넘는 2%를 최대 진폭으로 덮어 클리핑 상태를 만든다 */
function withClipping(pcm: Int16Array): Int16Array {
  const clipCount = Math.trunc(pcm.length * 0.02)
  for (let i = 0; i < clipCount; i++) pcm[i] = 32767
  return pcm
}

describe('judge — 앱과 같은 순서로 판정한다', () => {
  it('1초 미만 발화는 TOO_SHORT다', () => {
    expect(judge(pcmOfSeconds(0.5, 1000), 500)).toBe('TOO_SHORT')
  })

  it('무음에 가까운 녹음은 TOO_QUIET다', () => {
    expect(judge(pcmOfSeconds(2, 10), 2000)).toBe('TOO_QUIET')
  })

  it('클리핑 비율이 임계를 넘으면 CLIPPED다', () => {
    expect(judge(withClipping(pcmOfSeconds(2, 1000)), 2000)).toBe('CLIPPED')
  })

  it('정상 발화는 NORMAL이다', () => {
    expect(judge(pcmOfSeconds(2, 1000), 2000)).toBe('NORMAL')
  })

  it('길이가 충분해도 샘플이 하나도 없으면 TOO_SHORT다', () => {
    expect(judge(new Int16Array(0), 2000)).toBe('TOO_SHORT')
  })

  it('클리핑은 RMS 판정보다 먼저 걸린다 (찢어진 녹음은 크게 들리므로)', () => {
    const clipped = withClipping(pcmOfSeconds(2, 1000))
    expect(measure(clipped).rms * FULL_SCALE).toBeGreaterThan(100)
    expect(judge(clipped, 2000)).toBe('CLIPPED')
  })
})

describe('measure — 서버로 보낼 0..1 정규화 지표', () => {
  it('진폭을 0에서 1 사이로 정규화한다', () => {
    const quality = measure(pcmOfSeconds(1, FULL_SCALE / 2))

    expect(quality.rms).toBeCloseTo(0.5, 6)
    expect(quality.peak).toBeCloseTo(0.5, 6)
    expect(quality.silenceRatio).toBeCloseTo(0, 9)
    expect(quality.clipped).toBe(false)
  })

  it('무음 배열은 silenceRatio가 1이고 나머지는 0이다', () => {
    const quality = measure(new Int16Array(TARGET_SAMPLE_RATE))

    expect(quality.rms).toBeCloseTo(0, 9)
    expect(quality.peak).toBeCloseTo(0, 9)
    expect(quality.silenceRatio).toBeCloseTo(1, 9)
    expect(quality.clipped).toBe(false)
  })

  it('클리핑이 임계를 넘으면 clipped가 true다', () => {
    const quality = measure(withClipping(pcmOfSeconds(2, 1000)))

    expect(quality.clipped).toBe(true)
    expect(quality.peak).toBeGreaterThan(0.99)
  })

  it('클리핑 샘플이 임계 비율 이하면 clipped가 false다', () => {
    const pcm = pcmOfSeconds(2, 1000)
    // 임계는 "초과"라 정확히 1%는 통과한다 (앱 구현과 같은 부등호).
    const clipCount = Math.trunc(pcm.length * CLIP_RATIO_THRESHOLD)
    for (let i = 0; i < clipCount; i++) pcm[i] = 32767

    expect(measure(pcm).clipped).toBe(false)
  })

  it('무음 임계 미만인 샘플만 silenceRatio에 든다', () => {
    const pcm = new Int16Array(4)
    pcm[0] = SILENCE_SAMPLE_THRESHOLD - 1
    pcm[1] = -(SILENCE_SAMPLE_THRESHOLD - 1)
    pcm[2] = SILENCE_SAMPLE_THRESHOLD
    pcm[3] = 1000

    expect(measure(pcm).silenceRatio).toBeCloseTo(0.5, 9)
  })

  it('빈 배열은 0으로 나누지 않고 전부 0을 반환한다', () => {
    const expected: ClientQuality = { rms: 0, peak: 0, silenceRatio: 0, clipped: false }
    expect(measure(new Int16Array(0))).toEqual(expected)
  })
})
