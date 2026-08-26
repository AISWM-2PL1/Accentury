import { describe, expect, it } from 'vitest'
import { HOP_SIZE, OverlappedFramer, WINDOW_SIZE, type AnalysisFrame } from './overlappedFramer'

/** 값이 곧 전역 샘플 인덱스인 램프 신호. 창 내용이 어디서 잘려 나왔는지 값으로 검증할 수 있다 */
function ramp(from: number, size: number): Float32Array {
  const chunk = new Float32Array(size)
  for (let i = 0; i < size; i++) chunk[i] = from + i
  return chunk
}

function expectRampFrame(frame: AnalysisFrame, expectedStart: number) {
  expect(frame.startSampleIndex).toBe(expectedStart)
  expect(frame.samples.length).toBe(WINDOW_SIZE)
  for (let i = 0; i < frame.samples.length; i++) {
    expect(frame.samples[i]).toBe(expectedStart + i)
  }
}

describe('OverlappedFramer (앱 OverlappedFramerTest 이식)', () => {
  it('창 길이와 같은 조각은 프레임 1개를 만든다', () => {
    const framer = new OverlappedFramer()

    const frames = framer.push(ramp(0, WINDOW_SIZE))

    expect(frames.length).toBe(1)
    expectRampFrame(frames[0], 0)
  })

  it('창 하나 뒤에 hop만큼 더 들어오면 프레임이 하나 더 나온다', () => {
    const framer = new OverlappedFramer()
    framer.push(ramp(0, WINDOW_SIZE))

    const frames = framer.push(ramp(WINDOW_SIZE, HOP_SIZE))

    expect(frames.length).toBe(1)
    expectRampFrame(frames[0], HOP_SIZE)
  })

  it('hop보다 짧은 조각만으로는 프레임이 나오지 않는다', () => {
    const framer = new OverlappedFramer()
    framer.push(ramp(0, WINDOW_SIZE))

    expect(framer.push(ramp(WINDOW_SIZE, 300))).toEqual([])
  })

  it('한 번의 큰 조각이 여러 프레임을 만든다', () => {
    const framer = new OverlappedFramer()

    const frames = framer.push(ramp(0, WINDOW_SIZE + HOP_SIZE * 3))

    expect(frames.length).toBe(4)
    ;[0, 512, 1024, 1536].forEach((start, i) => expectRampFrame(frames[i], start))
  })

  it('불규칙한 작은 조각들도 이어 붙여 올바른 프레임을 만든다', () => {
    // 조각 경계와 창 경계가 어긋나는 경우. 실제 캡처에서 늘 일어난다.
    const framer = new OverlappedFramer()
    const collected: AnalysisFrame[] = []
    let pushed = 0
    while (pushed < WINDOW_SIZE + HOP_SIZE * 2) {
      collected.push(...framer.push(ramp(pushed, 300)))
      pushed += 300
    }

    expect(collected.length).toBe(3)
    ;[0, 512, 1024].forEach((start, i) => expectRampFrame(collected[i], start))
  })

  it('창을 채우지 못한 꼬리는 프레임으로 나오지 않는다', () => {
    const framer = new OverlappedFramer()

    expect(framer.push(ramp(0, WINDOW_SIZE - 1))).toEqual([])
    // 남은 꼬리(2047샘플)는 다음 입력이 올 때까지 그대로 대기한다.
    expect(framer.push(ramp(WINDOW_SIZE - 1, 1)).length).toBe(1)
  })

  it('빈 조각은 프레임을 만들지 않는다', () => {
    const framer = new OverlappedFramer()

    expect(framer.push(new Float32Array(0))).toEqual([])
    expect(framer.push(ramp(0, WINDOW_SIZE)).length).toBe(1)
  })

  it('창보다 큰 hop은 거부한다', () => {
    expect(() => new OverlappedFramer(512, 1024)).toThrow(RangeError)
    expect(() => new OverlappedFramer(0, 512)).toThrow(RangeError)
  })
})
