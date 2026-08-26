import { describe, expect, it } from 'vitest'
import { PitchTracker } from './pitchTracker'
import { sineChunk } from './testing/fakeCapture'

const CAPTURE_RATE = 48000

describe('PitchTracker', () => {
  it('48kHz 발화에서 32ms 간격으로 220Hz를 뽑는다', () => {
    const tracker = new PitchTracker(CAPTURE_RATE)

    tracker.push(sineChunk(1000, { sampleRate: CAPTURE_RATE, frequency: 220 }))

    const frames = tracker.frames
    // 1초 = 16kHz 16000샘플. 첫 창이 2048샘플을 채운 뒤 512마다 하나씩 = 28개 안팎
    expect(frames.length).toBeGreaterThan(20)
    // 창 중앙 시각이라 첫 프레임은 64ms, 이후 32ms 간격이다
    expect(frames[0].timestampMs).toBe(64)
    for (let i = 1; i < frames.length; i++) {
      expect(frames[i].timestampMs - frames[i - 1].timestampMs).toBe(32)
    }
    for (const frame of frames) {
      expect(frame.pitchHz).not.toBeNull()
      expect(Math.abs(frame.pitchHz! - 220)).toBeLessThanOrEqual(4)
    }
  })

  it('조각을 잘게 나눠 넣어도 같은 프레임이 나온다', () => {
    // 실제 워클릿 조각(4096)과 들쭉날쭉한 조각이 같은 결과를 내야 한다 - 조각 경계가
    // 곡선에 흔적을 남기면 여기서 갈린다.
    const full = sineChunk(1000, { sampleRate: CAPTURE_RATE, frequency: 220 })

    const whole = new PitchTracker(CAPTURE_RATE)
    whole.push(full)

    const chunked = new PitchTracker(CAPTURE_RATE)
    for (let at = 0; at < full.length; at += 4096) {
      chunked.push(full.subarray(at, Math.min(at + 4096, full.length)))
    }

    expect(chunked.frames.length).toBe(whole.frames.length)
    chunked.frames.forEach((frame, i) => {
      expect(frame.timestampMs).toBe(whole.frames[i].timestampMs)
      expect(frame.pitchHz!).toBeCloseTo(whole.frames[i].pitchHz!, 3)
    })
  })

  it('무음은 전부 무성 프레임이다', () => {
    const tracker = new PitchTracker(CAPTURE_RATE)

    tracker.push(new Float32Array(CAPTURE_RATE))

    expect(tracker.frames.length).toBeGreaterThan(20)
    expect(tracker.frames.every((frame) => frame.pitchHz === null)).toBe(true)
  })

  it('push는 이번에 새로 완성된 프레임만 돌려준다', () => {
    const tracker = new PitchTracker(CAPTURE_RATE)

    const first = tracker.push(sineChunk(200, { sampleRate: CAPTURE_RATE, frequency: 220 }))
    const second = tracker.push(sineChunk(200, { sampleRate: CAPTURE_RATE, frequency: 220 }))

    expect(first.length).toBeGreaterThan(0)
    expect(second.length).toBeGreaterThan(0)
    expect(tracker.frames.length).toBe(first.length + second.length)
    // 시각은 조각을 건너뛰어도 이어진다 - 리샘플러·프레이머가 이력을 들고 있기 때문이다
    expect(second[0].timestampMs - first[first.length - 1].timestampMs).toBe(32)
  })

  it('창을 채우기 전에는 프레임이 없다', () => {
    const tracker = new PitchTracker(CAPTURE_RATE)

    // 16kHz 2048샘플(128ms)에 못 미치는 조각
    expect(tracker.push(sineChunk(50, { sampleRate: CAPTURE_RATE })).length).toBe(0)
  })

  it('캡처가 이미 16kHz면 리샘플 없이도 같은 규칙으로 돈다', () => {
    const tracker = new PitchTracker(16000)

    tracker.push(sineChunk(500, { sampleRate: 16000, frequency: 220 }))

    expect(tracker.frames[0].timestampMs).toBe(64)
    expect(Math.abs(tracker.frames[0].pitchHz! - 220)).toBeLessThanOrEqual(4)
  })
})
