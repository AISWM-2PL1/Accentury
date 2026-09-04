import { describe, expect, it } from 'vitest'
import { TARGET_SAMPLE_RATE } from './pcm'
import { TAP_COUNT, outputLength, resampleTo16k } from './resample'
import { StreamingResampler } from './streamingResampler'

/** 진폭 1의 사인파. `resample.test.ts`와 같은 생성기다 */
function sine(freq: number, sampleRate: number, seconds: number): Float32Array {
  const samples = new Float32Array(Math.round(sampleRate * seconds))
  for (let i = 0; i < samples.length; i++) {
    samples[i] = Math.sin((2 * Math.PI * freq * i) / sampleRate)
  }
  return samples
}

/**
 * 결정적 난수(LCG). 실제 워클릿의 조각 크기는 고정이지만, 조각 경계가 어디에 떨어져도
 * 결과가 같아야 한다는 것이 이 클래스의 요점이라 일부러 들쭉날쭉하게 자른다.
 * 시드를 고정하는 이유는 실패가 실행마다 달라지면 재현할 수 없기 때문이다.
 */
function randomSizes(total: number, seed = 7): number[] {
  let state = seed
  const next = () => {
    state = (state * 1103515245 + 12345) % 2147483648
    return state / 2147483648
  }
  const sizes: number[] = []
  let left = total
  while (left > 0) {
    const size = Math.min(left, 1 + Math.floor(next() * 5000))
    sizes.push(size)
    left -= size
  }
  return sizes
}

/** 조각으로 잘라 밀어넣고 이어 붙인 결과. [flush]까지 포함한다 */
function streamInChunks(input: Float32Array, inputRate: number, sizes: number[]): Float32Array {
  const resampler = new StreamingResampler(inputRate)
  const parts: Float32Array[] = []
  let offset = 0
  for (const size of sizes) {
    parts.push(resampler.push(input.subarray(offset, offset + size)))
    offset += size
  }
  parts.push(resampler.flush())

  const total = parts.reduce((sum, part) => sum + part.length, 0)
  const out = new Float32Array(total)
  let at = 0
  for (const part of parts) {
    out.set(part, at)
    at += part.length
  }
  return out
}

describe('StreamingResampler', () => {
  it('조각으로 나눠 넣어도 배치 리샘플과 같은 신호가 나온다', () => {
    const input = sine(200, 48000, 3)
    const batch = resampleTo16k(input, 48000)

    const streamed = streamInChunks(input, 48000, randomSizes(input.length))

    // 길이는 배치와 같다 — flush가 꼬리까지 내보내므로 어긋날 자리가 없다
    expect(Math.abs(streamed.length - batch.length)).toBeLessThanOrEqual(2)

    // 양 끝은 커널이 0으로 채워진 바깥을 물어 값이 죽는 구간이라 비교에서 뺀다.
    const margin = TAP_COUNT
    let worst = 0
    for (let i = margin; i < batch.length - margin; i++) {
      worst = Math.max(worst, Math.abs(streamed[i] - batch[i]))
    }
    expect(worst).toBeLessThan(1e-3)
  })

  it('조각 크기를 바꿔도 결과가 같다 - 경계가 신호에 흔적을 남기지 않는다', () => {
    // 페이드가 조각마다 찍히던 버그가 있으면 이 두 결과가 갈린다.
    const input = sine(200, 48000, 1)
    const uniform = streamInChunks(input, 48000, randomSizes(input.length, 1))
    const ragged = streamInChunks(input, 48000, randomSizes(input.length, 99))

    expect(ragged.length).toBe(uniform.length)
    for (let i = 0; i < uniform.length; i++) {
      expect(ragged[i]).toBeCloseTo(uniform[i], 6)
    }
  })

  it('44.1kHz 같은 비정수비도 배치와 같다', () => {
    const input = sine(200, 44100, 1)
    const batch = resampleTo16k(input, 44100)
    const streamed = streamInChunks(input, 44100, randomSizes(input.length, 3))

    expect(Math.abs(streamed.length - batch.length)).toBeLessThanOrEqual(2)
    for (let i = TAP_COUNT; i < batch.length - TAP_COUNT; i++) {
      expect(streamed[i]).toBeCloseTo(batch[i], 3)
    }
  })

  it('이미 16kHz면 그대로 복사만 한다', () => {
    const resampler = new StreamingResampler(TARGET_SAMPLE_RATE)
    const chunk = sine(200, TARGET_SAMPLE_RATE, 0.01)

    const out = resampler.push(chunk)

    expect(out).toEqual(chunk)
    expect(out).not.toBe(chunk) // 호출자가 이어 쓰다 덮어쓰는 사고를 막는다
    expect(resampler.flush().length).toBe(0)
  })

  it('빈 조각은 아무것도 내보내지 않는다', () => {
    const resampler = new StreamingResampler(48000)

    expect(resampler.push(new Float32Array(0)).length).toBe(0)
  })

  it('오른쪽 지지 구간이 안 찬 꼬리는 flush 전까지 쥐고 있는다', () => {
    // 아직 안 들어온 입력을 0으로 치고 내보내면 그게 곧 조각 경계 페이드다.
    const input = sine(200, 48000, 0.5)
    const resampler = new StreamingResampler(48000)

    const pushed = resampler.push(input).length
    const flushed = resampler.flush().length

    expect(flushed).toBeGreaterThan(0)
    expect(pushed + flushed).toBe(outputLength(input.length, 48000))
  })

  it('레이트가 유효하지 않으면 만들 때 거부한다', () => {
    expect(() => new StreamingResampler(0)).toThrow(RangeError)
    expect(() => new StreamingResampler(Number.NaN)).toThrow(RangeError)
  })
})
