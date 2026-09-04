import { describe, expect, it } from 'vitest'
import { WAV_HEADER_BYTES } from './wavEncoder'
import { RecordingBuffer } from './recordingBuffer'

const RATE = 48000
const MAX_MS = 10_000

/** 진폭 amplitude짜리 sine 파형 */
function sine(seconds: number, frequency = 200, amplitude = 0.5): Float32Array {
  const samples = new Float32Array(Math.round(seconds * RATE))
  for (let i = 0; i < samples.length; i++) {
    samples[i] = amplitude * Math.sin((2 * Math.PI * frequency * i) / RATE)
  }
  return samples
}

describe('RecordingBuffer — 담기', () => {
  it('들어온 조각을 복사한다 — 캡처 버퍼가 재사용돼도 녹음이 덮이지 않는다', () => {
    const buffer = new RecordingBuffer(16000, 2000)
    const chunk = new Float32Array([0.5, 0.5, 0.5, 0.5])

    buffer.push(chunk)
    // 워클릿·ScriptProcessor는 같은 배열을 다음 블록에서 다시 쓴다
    chunk.fill(-1)

    const { wav } = buffer.finish()
    const pcm = new DataView(wav.buffer, wav.byteOffset)
    // 16kHz 입력이라 리샘플을 타지 않아 첫 샘플이 그대로 남는다
    expect(pcm.getInt16(WAV_HEADER_BYTES, true)).toBeGreaterThan(0)
  })

  it('상한에 닿으면 마지막 조각을 잘라 담고 true를 돌려준다', () => {
    const buffer = new RecordingBuffer(RATE, MAX_MS)
    const chunk = new Float32Array(200_000)

    expect(buffer.push(chunk)).toBe(false)
    expect(buffer.push(chunk)).toBe(false)
    expect(buffer.push(chunk)).toBe(true)

    // 10초 × 48000 = 480000. 조각 경계(600000)가 아니라 상한에서 정확히 끊긴다
    expect(buffer.capturedSamples).toBe(480_000)
    expect(buffer.durationMs).toBe(MAX_MS)
  })

  it('상한을 넘긴 뒤 들어온 조각은 무시하고 true만 다시 알린다', () => {
    const buffer = new RecordingBuffer(RATE, MAX_MS)
    buffer.push(new Float32Array(480_000))

    expect(buffer.push(new Float32Array(4096))).toBe(true)
    expect(buffer.capturedSamples).toBe(480_000)
  })

  it('유효하지 않은 레이트·길이는 만들 때 막는다', () => {
    expect(() => new RecordingBuffer(0, MAX_MS)).toThrow(RangeError)
    expect(() => new RecordingBuffer(RATE, 0)).toThrow(RangeError)
  })
})

describe('RecordingBuffer — 마무리', () => {
  it('2초 sine을 16kHz 모노 WAV로 마무리한다', () => {
    const buffer = new RecordingBuffer(RATE, MAX_MS)
    buffer.push(sine(2))

    const recording = buffer.finish()

    // 2초 × 16000 샘플 × 2바이트 + 헤더
    expect(recording.wav.length).toBe(WAV_HEADER_BYTES + 32_000 * 2)
    expect(recording.durationMs).toBe(2000)
    expect(recording.sourceSampleRate).toBe(RATE)
    expect(recording.status).toBe('NORMAL')
    expect(recording.quality.peak).toBeCloseTo(0.5, 2)
    expect(recording.quality.clipped).toBe(false)
  })

  it('1초에 못 미치면 TOO_SHORT다', () => {
    const buffer = new RecordingBuffer(RATE, MAX_MS)
    buffer.push(sine(0.5))

    const recording = buffer.finish()
    expect(recording.durationMs).toBe(500)
    expect(recording.status).toBe('TOO_SHORT')
  })

  it('무음이면 TOO_QUIET다', () => {
    const buffer = new RecordingBuffer(RATE, MAX_MS)
    buffer.push(new Float32Array(RATE * 2))

    const recording = buffer.finish()
    expect(recording.status).toBe('TOO_QUIET')
    expect(recording.quality.rms).toBe(0)
  })

  it('마무리 뒤에는 다시 마무리하거나 담을 수 없다 (오디오를 들고 있지 않는다)', () => {
    const buffer = new RecordingBuffer(RATE, MAX_MS)
    buffer.push(sine(2))
    buffer.finish()

    // 길이 사실은 남지만 조각은 이미 놓았다
    expect(buffer.capturedSamples).toBe(96_000)
    expect(() => buffer.finish()).toThrow('이미 마무리된 버퍼')
    expect(() => buffer.push(sine(0.1))).toThrow('이미 마무리된 버퍼')
  })
})
