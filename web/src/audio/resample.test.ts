import { describe, expect, it } from 'vitest'
import { CUTOFF_RATIO, TAP_COUNT, outputLength, resampleTo16k } from './resample'
import { TARGET_SAMPLE_RATE } from './pcm'

/** 진폭 1의 사인파. 리샘플러가 지켜야 할 것과 지워야 할 것을 모두 이 신호로 만든다 */
function sine(freq: number, sampleRate: number, seconds: number): Float32Array {
  const samples = new Float32Array(Math.round(sampleRate * seconds))
  for (let i = 0; i < samples.length; i++) {
    samples[i] = Math.sin((2 * Math.PI * freq * i) / sampleRate)
  }
  return samples
}

/**
 * 특정 주파수 성분의 진폭을 잰다 (한 점짜리 DFT).
 *
 * 해닝 창을 씌우는 이유는 신호 길이가 주파수의 정수 배가 아닐 때 생기는 스펙트럼 누설 때문이다.
 * 누설을 그대로 두면 -74 dB짜리 에일리어스를 재려는데 옆 대역의 큰 성분이 흘러들어와
 * 측정 바닥이 -40 dB쯤에 깔린다. 창의 이득(가중치 합)으로 나눠 진폭 단위를 되돌린다.
 */
function amplitudeAt(signal: Float32Array, freq: number, sampleRate: number): number {
  let re = 0
  let im = 0
  let windowGain = 0
  for (let n = 0; n < signal.length; n++) {
    const w = 0.5 - 0.5 * Math.cos((2 * Math.PI * n) / signal.length)
    const phase = (2 * Math.PI * freq * n) / sampleRate
    re += w * signal[n] * Math.cos(phase)
    im -= w * signal[n] * Math.sin(phase)
    windowGain += w
  }
  return (2 * Math.hypot(re, im)) / windowGain
}

/** 양 끝은 커널이 0으로 채워진 바깥을 물어 진폭이 죽는다. 측정 전에 잘라낸다 */
function trimEdges(signal: Float32Array, margin = TAP_COUNT): Float32Array {
  return signal.slice(margin, signal.length - margin)
}

/** 안티에일리어싱 없이 3개마다 하나씩 집는 방식 — 이 테스트가 반증하려는 순진한 구현 */
function naiveDecimate(input: Float32Array, factor: number): Float32Array {
  const out = new Float32Array(Math.round(input.length / factor))
  for (let n = 0; n < out.length; n++) out[n] = input[n * factor]
  return out
}

describe('resampleTo16k — 길이', () => {
  it('48kHz 1초는 16000 샘플이 된다 (3:1)', () => {
    const out = resampleTo16k(sine(200, 48000, 1), 48000)
    expect(Math.abs(out.length - 48000 / 3)).toBeLessThanOrEqual(1)
  })

  it('44.1kHz도 재생 시간이 유지되는 길이로 나온다 (비정수비)', () => {
    const out = resampleTo16k(sine(200, 44100, 1), 44100)
    expect(Math.abs(out.length - (44100 * TARGET_SAMPLE_RATE) / 44100)).toBeLessThanOrEqual(1)
    expect(out.length).toBe(outputLength(44100, 44100))
  })

  it('빈 입력은 빈 출력이 된다', () => {
    expect(resampleTo16k(new Float32Array(0), 48000).length).toBe(0)
  })
})

describe('resampleTo16k — 통과대역 보존', () => {
  it('48kHz의 200Hz 음성 대역을 진폭 손실 없이 넘긴다', () => {
    const out = resampleTo16k(sine(200, 48000, 1), 48000)
    expect(amplitudeAt(trimEdges(out), 200, TARGET_SAMPLE_RATE)).toBeCloseTo(1, 1)
  })

  it('44.1kHz의 200Hz도 진폭이 유지된다', () => {
    const out = resampleTo16k(sine(200, 44100, 1), 44100)
    expect(amplitudeAt(trimEdges(out), 200, TARGET_SAMPLE_RATE)).toBeCloseTo(1, 1)
  })

  it('F0 상한(400Hz)까지 5% 안쪽으로 보존한다', () => {
    const out = resampleTo16k(sine(400, 48000, 1), 48000)
    expect(amplitudeAt(trimEdges(out), 400, TARGET_SAMPLE_RATE)).toBeGreaterThan(0.95)
  })

  it('차단 주파수는 두 나이퀴스트 중 낮은 쪽의 90%다', () => {
    expect(CUTOFF_RATIO * TARGET_SAMPLE_RATE).toBe(7200)
  })
})

describe('resampleTo16k — 안티에일리어싱 (이 모듈의 존재 이유)', () => {
  /*
   * 48kHz의 9000Hz는 16kHz 격자에서 |9000 − 16000| = 7000Hz로 접힌다. 접힌 뒤에는 원래
   * 9000Hz였다는 정보가 남지 않아 서버가 되돌릴 수 없다 (지라 2026-08-09).
   */
  const input = sine(9000, 48000, 1)
  const aliasFreq = 7000

  it('순진하게 3개마다 하나씩 집으면 7000Hz에 가짜 성분이 나타난다', () => {
    const naive = trimEdges(naiveDecimate(input, 3))
    expect(amplitudeAt(naive, aliasFreq, TARGET_SAMPLE_RATE)).toBeGreaterThan(0.9)
  })

  it('저역통과를 먼저 태우면 같은 성분이 40 dB 이상 눌린다', () => {
    const filtered = trimEdges(resampleTo16k(input, 48000))
    const naive = trimEdges(naiveDecimate(input, 3))

    const attenuationDb = 20 * Math.log10(
      amplitudeAt(filtered, aliasFreq, TARGET_SAMPLE_RATE) /
        amplitudeAt(naive, aliasFreq, TARGET_SAMPLE_RATE),
    )
    expect(attenuationDb).toBeLessThan(-40)
  })

  it('44.1kHz에서도 접힐 고역을 지운다', () => {
    // 44.1kHz의 9000Hz는 16kHz 격자에서 |9000 − 16000| = 7000Hz로 접힌다.
    const filtered = trimEdges(resampleTo16k(sine(9000, 44100, 1), 44100))
    expect(amplitudeAt(filtered, aliasFreq, TARGET_SAMPLE_RATE)).toBeLessThan(0.01)
  })
})

describe('resampleTo16k — 경계 조건', () => {
  it('이미 16kHz면 내용은 같고 참조는 다른 사본을 준다', () => {
    const input = sine(200, TARGET_SAMPLE_RATE, 0.1)
    const out = resampleTo16k(input, TARGET_SAMPLE_RATE)

    expect(out).not.toBe(input)
    expect(Array.from(out)).toEqual(Array.from(input))
  })

  it('원본 배열을 변형하지 않는다', () => {
    const input = sine(200, 48000, 0.1)
    const before = Array.from(input)
    resampleTo16k(input, 48000)
    expect(Array.from(input)).toEqual(before)
  })

  it('유효하지 않은 샘플레이트는 RangeError로 막는다', () => {
    const input = sine(200, 48000, 0.01)
    expect(() => resampleTo16k(input, 0)).toThrow(RangeError)
    expect(() => resampleTo16k(input, -48000)).toThrow(RangeError)
    expect(() => resampleTo16k(input, Number.NaN)).toThrow(RangeError)
    expect(() => resampleTo16k(input, Number.POSITIVE_INFINITY)).toThrow(RangeError)
  })
})

describe('resampleTo16k — 성능', () => {
  it('48kHz 10초(480k 샘플)를 2초 안에 끝낸다', () => {
    const input = sine(200, 48000, 10)
    const started = performance.now()
    const out = resampleTo16k(input, 48000)
    const elapsed = performance.now() - started

    expect(out.length).toBe(160000)
    expect(elapsed).toBeLessThan(2000)
  })
})
