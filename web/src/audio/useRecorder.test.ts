import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CaptureError, type CaptureFactory } from './capture'
// 캡처 대역은 화면 테스트(WebVoiceRecorder·TestFlowScreen)와 공유한다 — 대역이 갈라지면
// "훅은 통과하는데 화면만 깨진다"가 대역 차이인지 코드 차이인지 알 수 없게 된다.
import { createFakeCapture, FAKE_SAMPLE_RATE } from './testing/fakeCapture'
import { useRecorder } from './useRecorder'

const RATE = FAKE_SAMPLE_RATE
const MAX_MS = 10_000

/** 갱신 간격(100ms) 판정을 결정적으로 만들기 위한 조종 가능한 시계 */
function fakeClock() {
  const box = { value: 0 }
  return { box, now: () => box.value }
}

function setup(capture: CaptureFactory, now: () => number = () => 0) {
  return renderHook(() => useRecorder({ maxDurationMs: MAX_MS, capture, now }))
}

describe('useRecorder', () => {
  it('start하면 recording으로 들어간다', async () => {
    const capture = createFakeCapture()
    const { result } = setup(capture.factory)

    expect(result.current.state).toEqual({ phase: 'idle' })
    await act(async () => {
      await result.current.start()
    })

    expect(result.current.state).toEqual({ phase: 'recording', elapsedMs: 0 })
  })

  it('조각이 들어올 때마다 경과 시간이 샘플 수 기준으로 늘어난다', async () => {
    const capture = createFakeCapture()
    const clock = fakeClock()
    const { result } = setup(capture.factory, clock.now)

    await act(async () => {
      await result.current.start()
    })

    // 첫 조각은 간격과 무관하게 반영된다 — 아니면 시작 직후 100ms 동안 0초에 멈춰 보인다
    await act(async () => {
      capture.emit(new Float32Array(RATE))
    })
    expect(result.current.state).toEqual({ phase: 'recording', elapsedMs: 1000 })

    // 갱신 간격 안에 들어온 조각은 담기지만 화면을 다시 그리지는 않는다
    clock.box.value += 50
    await act(async () => {
      capture.emit(new Float32Array(RATE))
    })
    expect(result.current.state).toEqual({ phase: 'recording', elapsedMs: 1000 })

    clock.box.value += 100
    await act(async () => {
      capture.emit(new Float32Array(RATE))
    })
    // 갱신을 건너뛴 조각도 버퍼에는 들어 있다 — 3초분이 한 번에 드러난다
    expect(result.current.state).toEqual({ phase: 'recording', elapsedMs: 3000 })
  })

  it('최대 길이에 닿으면 스스로 멈추고 검토 단계로 넘어간다 (FR-RC-02)', async () => {
    const capture = createFakeCapture()
    const { result } = setup(capture.factory)

    await act(async () => {
      await result.current.start()
    })
    await act(async () => {
      // 상한(480000)을 넘겨 보낸다
      capture.emit(new Float32Array(500_000))
    })

    expect(result.current.state.phase).toBe('review')
    expect(capture.stop).toHaveBeenCalledTimes(1)
    if (result.current.state.phase !== 'review') throw new Error('unreachable')
    // 넘긴 만큼이 아니라 상한에서 정확히 끊긴다
    expect(result.current.state.recording.durationMs).toBe(MAX_MS)
  })

  it('사용자가 멈추면 그때까지 담긴 녹음을 들고 검토 단계로 간다', async () => {
    const capture = createFakeCapture()
    const { result } = setup(capture.factory)

    await act(async () => {
      await result.current.start()
    })
    await act(async () => {
      capture.emit(new Float32Array(RATE * 2))
    })
    await act(async () => {
      await result.current.stop()
    })

    expect(result.current.state.phase).toBe('review')
    if (result.current.state.phase !== 'review') throw new Error('unreachable')
    const { recording } = result.current.state
    expect(recording.durationMs).toBe(2000)
    expect(recording.sourceSampleRate).toBe(RATE)
    // 무음이라 [다음]을 막는 판정이 나온다 — 품질 게이트가 검토 화면의 근거다
    expect(recording.status).toBe('TOO_QUIET')
    expect(capture.stop).toHaveBeenCalledTimes(1)
  })

  it('정지를 겹쳐 불러도 캡처는 한 번만 닫힌다', async () => {
    const capture = createFakeCapture()
    const { result } = setup(capture.factory)

    await act(async () => {
      await result.current.start()
    })
    await act(async () => {
      await Promise.all([result.current.stop(), result.current.stop()])
    })

    expect(capture.stop).toHaveBeenCalledTimes(1)
    expect(result.current.state.phase).toBe('review')
  })

  it('[재녹음]은 녹음을 버리고 처음으로 되돌린다', async () => {
    const capture = createFakeCapture()
    const { result } = setup(capture.factory)

    await act(async () => {
      await result.current.start()
    })
    await act(async () => {
      await result.current.stop()
    })
    act(() => {
      result.current.discard()
    })

    expect(result.current.state).toEqual({ phase: 'idle' })
  })

  it('캡처가 실패하면 사유를 든 오류 단계로 가고, 거기서도 다시 시작할 수 있다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const factory: CaptureFactory = async () => {
      throw new CaptureError('permission', 'denied by user')
    }
    const { result } = setup(factory)

    await act(async () => {
      await result.current.start()
    })

    expect(result.current.state).toEqual({
      phase: 'error',
      reason: 'permission',
      message: '마이크 권한이 필요해요',
    })

    act(() => {
      result.current.discard()
    })
    expect(result.current.state).toEqual({ phase: 'idle' })
    warn.mockRestore()
  })

  it('CaptureError가 아닌 실패는 unknown으로 접는다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const factory: CaptureFactory = async () => {
      throw new Error('무언가 잘못됨')
    }
    const { result } = setup(factory)

    await act(async () => {
      await result.current.start()
    })

    expect(result.current.state.phase).toBe('error')
    if (result.current.state.phase !== 'error') throw new Error('unreachable')
    expect(result.current.state.reason).toBe('unknown')
    warn.mockRestore()
  })

  it('녹음 중 화면을 떠나면 마이크를 놓는다 (FR-AD-04)', async () => {
    const capture = createFakeCapture()
    const { result, unmount } = setup(capture.factory)

    await act(async () => {
      await result.current.start()
    })
    unmount()

    expect(capture.stop).toHaveBeenCalledTimes(1)
  })

  it('녹음 중이 아닐 때의 stop은 아무 일도 하지 않는다', async () => {
    const capture = createFakeCapture()
    const { result } = setup(capture.factory)

    await act(async () => {
      await result.current.stop()
    })

    expect(result.current.state).toEqual({ phase: 'idle' })
    expect(capture.stop).not.toHaveBeenCalled()
  })

  it('시작하는 도중에는 두 번째 start가 무시된다', async () => {
    const capture = createFakeCapture()
    const { result } = setup(capture.factory)

    await act(async () => {
      await Promise.all([result.current.start(), result.current.start()])
    })

    expect(result.current.state.phase).toBe('recording')
    // 캡처가 둘 잡히면 두 번째 것만 정지되고 첫 번째는 마이크를 든 채 남는다
    await act(async () => {
      await result.current.stop()
    })
    expect(capture.stop).toHaveBeenCalledTimes(1)
  })
})
