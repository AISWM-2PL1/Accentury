/**
 * 캡처 결선의 결선·정리 경로 테스트 (KAN-56 Stage 2, Codex 검증 후속).
 *
 * `capture.ts`는 "브라우저에서만 도는 파일이라 단위 테스트가 없다"고 적어 둔 자리였다. 그
 * 전제가 깨진 지점이 AC6(마이크·오디오 누수 없음)이다 — flush 요청이나 노드 해제가 **던졌을
 * 때** 트랙 정지와 컨텍스트 종료까지 도달하는지는 실기 확인으로 잡히지 않는다. 그 예외를
 * 실기에서 일부러 일으킬 방법이 없기 때문이다. 누수는 조용해서, 나면 사용자 화면에는 마이크
 * 표시등만 남고 오류는 어디에도 뜨지 않는다.
 *
 * 그래서 jsdom에 없는 전역(`AudioContext`·`AudioWorkletNode`·`URL.createObjectURL`)을
 * `vi.stubGlobal`로 세워 두고 **순서**만 본다: 무엇을 먼저 열고, 어떤 순서로 잇고, 무엇이
 * 던져도 무엇까지는 반드시 끊는지. 신호 자체는 여기서 보지 않는다 — 그건 순수 계층
 * (`resample`·`quality`)의 몫이고, 이 파일이 지키는 것은 **끊는 일**이다.
 *
 * 대역이 실제 브라우저와 다를 수 있다는 한계는 그대로 남는다. 여기서 통과한다고 iOS에서
 * 도는 것이 보장되지 않으므로 Stage 4의 실기 확인은 없어지지 않는다.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CaptureError, webAudioCapture } from './capture'

/** 대역 컨텍스트가 보고하는 하드웨어 레이트. 실기 대부분이 이 값이다 */
const RATE = 48000

/** `installBrowserGlobals`가 심는 Blob URL. 회수까지 확인하려고 값을 고정해 둔다 */
const BLOB_URL = 'blob:accentury-capture-test'

/** connect/disconnect만 있는 노드 대역 */
function fakeNode() {
  return { connect: vi.fn(), disconnect: vi.fn() }
}

/**
 * Web Audio 전역 한 벌을 세운다.
 *
 * 대역을 테스트마다 새로 만드는 이유는 spy 호출 횟수 때문이다 — 정리가 "한 번만" 일어나는지가
 * 이 파일의 주요 주장이라, 인스턴스를 공유하면 그 주장이 무의미해진다.
 */
function installBrowserGlobals() {
  /** getUserMedia → AudioContext 순서(파일 상단의 iOS 레이트 계약)를 확인하기 위한 기록 */
  const order: string[] = []

  const config = {
    /** 워클릿이 'flush'에 답하는지. 이미 죽은 워클릿을 흉내 낼 때 끈다 */
    flushReply: true,
    /** `resume()` 뒤 컨텍스트가 도달하는 상태. 제스처 밖에서 시작하면 'suspended'로 남는다 */
    stateAfterResume: 'running',
    /** `addModule`이 던질 오류. Blob URL 적재 실패를 흉내 낸다 */
    addModuleError: undefined as Error | undefined,
  }

  const tracks = [{ stop: vi.fn() }, { stop: vi.fn() }]
  const stream = { getTracks: () => tracks }
  const getUserMedia = vi.fn(async () => {
    order.push('getUserMedia')
    return stream
  })
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia } })

  const contexts: FakeAudioContext[] = []
  class FakeAudioContext {
    sampleRate = RATE
    // 실제 브라우저도 생성 직후에는 suspended다. `resume()`을 정말 기다리는지 보려고 맞춰 둔다
    state = 'suspended'
    destination = { id: 'destination' }
    source = fakeNode()
    gain = { gain: { value: 1 }, connect: vi.fn(), disconnect: vi.fn() }
    resume = vi.fn(async () => {
      this.state = config.stateAfterResume
    })
    close = vi.fn(async () => {
      this.state = 'closed'
    })
    audioWorklet = {
      addModule: vi.fn(async (_url: string) => {
        if (config.addModuleError !== undefined) throw config.addModuleError
      }),
    }
    createMediaStreamSource = vi.fn(() => this.source)
    createGain = vi.fn(() => this.gain)

    constructor() {
      order.push('AudioContext')
      contexts.push(this)
    }
  }
  vi.stubGlobal('AudioContext', FakeAudioContext)

  const worklets: FakeAudioWorkletNode[] = []
  class FakeAudioWorkletNode {
    port: { postMessage: ReturnType<typeof vi.fn>; onmessage: ((event: { data: unknown }) => void) | null }
    connect = vi.fn()
    disconnect = vi.fn()

    constructor(
      _ctx: unknown,
      readonly name: string,
    ) {
      this.port = {
        // 진짜 워클릿은 'flush'를 받으면 잔여분을 비우고 'flushed'로 답한다. 회신이 동기라
        // 정지 경로가 타이머에 기대지 않고도 풀린다 — 실제 브라우저에서는 한 틱 뒤에 온다.
        postMessage: vi.fn((data: unknown) => {
          if (data === 'flush' && config.flushReply) this.port.onmessage?.({ data: 'flushed' })
        }),
        onmessage: null,
      }
      worklets.push(this)
    }
  }
  vi.stubGlobal('AudioWorkletNode', FakeAudioWorkletNode)

  const revokeObjectURL = vi.fn()
  /*
   * jsdom에는 `createObjectURL`이 없다. 전역 URL을 통째로 객체로 갈아끼우면 `new URL()`이
   * 죽으므로, 진짜 URL을 상속한 클래스에 정적 메서드만 얹는다.
   */
  class FakeURL extends URL {
    static createObjectURL = () => BLOB_URL
    static revokeObjectURL = revokeObjectURL
  }
  vi.stubGlobal('URL', FakeURL)

  return {
    order,
    config,
    tracks,
    getUserMedia,
    revokeObjectURL,
    /** 마지막으로 만들어진 컨텍스트 */
    get context() {
      return contexts[contexts.length - 1]
    },
    /** 마지막으로 만들어진 워클릿 노드 */
    get worklet() {
      return worklets[worklets.length - 1]
    },
  }
}

/** 실패해야 하는 호출에서 CaptureError를 꺼낸다. 성공하면 그 자체가 실패다 */
async function captureFailure(promise: Promise<unknown>): Promise<CaptureError> {
  try {
    await promise
  } catch (error) {
    if (error instanceof CaptureError) return error
    throw error
  }
  throw new Error('실패해야 하는 호출이 성공했다')
}

function spyOnWarn() {
  return vi.spyOn(console, 'warn').mockImplementation(() => {})
}

describe('webAudioCapture', () => {
  let h: ReturnType<typeof installBrowserGlobals>
  let warn: ReturnType<typeof spyOnWarn>

  beforeEach(() => {
    h = installBrowserGlobals()
    // 정리 경로는 실패를 콘솔로만 알린다. 테스트 출력이 그 경고로 덮이지 않게 가로챈다
    warn = spyOnWarn()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
    warn.mockRestore()
  })

  it('스트림을 먼저 열고 그 다음에 컨텍스트를 만든다 (iOS 레이트 계약)', async () => {
    const chunks: Float32Array[] = []
    const capture = await webAudioCapture((chunk) => chunks.push(chunk))

    expect(capture.sampleRate).toBe(RATE)
    // 뒤집히면 iOS에서 컨텍스트가 마이크 활성화 전 레이트를 들고 실제 입력과 어긋난다
    expect(h.order).toEqual(['getUserMedia', 'AudioContext'])
    expect(h.context.resume).toHaveBeenCalled()

    // 워클릿 모듈은 Blob URL로 심고, 심자마자 회수한다 (URL은 컨텍스트가 이미 들었다)
    expect(h.context.audioWorklet.addModule).toHaveBeenCalledWith(BLOB_URL)
    expect(h.revokeObjectURL).toHaveBeenCalledWith(BLOB_URL)
    expect(h.worklet.name).toBe('accentury-capture')

    // 그래프: 마이크 → 워클릿 → 무음 게인 → 출력. 게인이 0이 아니면 자기 목소리가 들린다
    expect(h.context.source.connect).toHaveBeenCalledWith(h.worklet)
    expect(h.worklet.connect).toHaveBeenCalledWith(h.context.gain)
    expect(h.context.gain.connect).toHaveBeenCalledWith(h.context.destination)
    expect(h.context.gain.gain.value).toBe(0)

    const chunk = new Float32Array([0.1, 0.2, 0.3, 0.4])
    h.worklet.port.onmessage?.({ data: chunk })
    expect(chunks).toEqual([chunk])
  })

  it('stop은 잔여분을 회수한 뒤 노드·트랙·컨텍스트를 모두 놓는다 (AC6)', async () => {
    const capture = await webAudioCapture(() => {})
    const worklet = h.worklet

    const stopping = capture.stop()
    // 겹쳐 부르면 같은 약속을 돌려준다 — 두 번 정리하면 close가 닫힌 컨텍스트에 다시 간다
    expect(capture.stop()).toBe(stopping)
    await stopping

    expect(worklet.port.postMessage).toHaveBeenCalledWith('flush')
    // 핸들러를 남기면 정리 뒤 도착한 조각이 이미 끝난 녹음에 붙는다
    expect(worklet.port.onmessage).toBeNull()
    expect(h.context.source.disconnect).toHaveBeenCalledTimes(1)
    expect(worklet.disconnect).toHaveBeenCalledTimes(1)
    expect(h.context.gain.disconnect).toHaveBeenCalledTimes(1)
    for (const track of h.tracks) expect(track.stop).toHaveBeenCalledTimes(1)
    expect(h.context.close).toHaveBeenCalledTimes(1)
  })

  it('워클릿이 flush에 답하지 않아도 상한(100ms) 뒤에 정리하고 끝낸다', async () => {
    vi.useFakeTimers()
    h.config.flushReply = false
    const capture = await webAudioCapture(() => {})

    const stopping = capture.stop()
    let settled = false
    void stopping.then(() => {
      settled = true
    })

    // 상한 전에는 기다린다 — 잔여분(최대 85ms)을 성급히 버리지 않는다
    await vi.advanceTimersByTimeAsync(99)
    expect(settled).toBe(false)

    await vi.advanceTimersByTimeAsync(1)
    await stopping
    expect(settled).toBe(true)
    for (const track of h.tracks) expect(track.stop).toHaveBeenCalledTimes(1)
    expect(h.context.close).toHaveBeenCalledTimes(1)
  })

  it('flush 요청 자체가 던져도 stop은 실패하지 않고 정리를 끝낸다 (AC6)', async () => {
    // 이 경로는 타이머를 걸어 둔 채로 promise가 거절돼 setTimeout이 남는다. 가짜 시계를
    // 쓰면 그 잔여 타이머가 테스트와 함께 사라진다
    vi.useFakeTimers()
    const capture = await webAudioCapture(() => {})
    h.worklet.port.postMessage.mockImplementation(() => {
      throw new Error('포트가 이미 닫혔다')
    })

    await expect(capture.stop()).resolves.toBeUndefined()

    expect(h.context.source.disconnect).toHaveBeenCalledTimes(1)
    expect(h.worklet.disconnect).toHaveBeenCalledTimes(1)
    expect(h.context.gain.disconnect).toHaveBeenCalledTimes(1)
    for (const track of h.tracks) expect(track.stop).toHaveBeenCalledTimes(1)
    expect(h.context.close).toHaveBeenCalledTimes(1)
    expect(warn).toHaveBeenCalled()
  })

  it('노드 해제와 트랙 정지가 던져도 남은 정리는 계속된다 (AC6)', async () => {
    const capture = await webAudioCapture(() => {})
    h.context.source.disconnect.mockImplementation(() => {
      throw new Error('이미 끊긴 노드')
    })
    h.tracks[0].stop.mockImplementation(() => {
      throw new Error('트랙이 죽었다')
    })

    await expect(capture.stop()).resolves.toBeUndefined()

    // 앞이 던졌다고 멈추면 두 번째 트랙이 열린 채 남는다 — 마이크 표시등이 그대로다
    expect(h.worklet.disconnect).toHaveBeenCalledTimes(1)
    expect(h.context.gain.disconnect).toHaveBeenCalledTimes(1)
    expect(h.tracks[1].stop).toHaveBeenCalledTimes(1)
    expect(h.context.close).toHaveBeenCalledTimes(1)
    expect(warn).toHaveBeenCalled()
  })

  it('워클릿 적재가 실패하면 마이크를 놓고 worklet-load-failed로 던진다', async () => {
    h.config.addModuleError = new Error('모듈을 찾을 수 없음')

    const error = await captureFailure(webAudioCapture(() => {}))

    expect(error.reason).toBe('worklet-load-failed')
    // 실패했다면서 계속 듣고 있는 상태로 남지 않는다
    for (const track of h.tracks) expect(track.stop).toHaveBeenCalledTimes(1)
    expect(h.context.close).toHaveBeenCalledTimes(1)
  })

  it('컨텍스트가 resume 뒤에도 멈춰 있으면 audio-context-suspended로 던진다', async () => {
    h.config.stateAfterResume = 'suspended'

    const error = await captureFailure(webAudioCapture(() => {}))

    expect(error.reason).toBe('audio-context-suspended')
    for (const track of h.tracks) expect(track.stop).toHaveBeenCalledTimes(1)
    expect(h.context.close).toHaveBeenCalledTimes(1)
  })

  it('권한 거부와 장치 없음을 서로 다른 사유로 갈라 준다', async () => {
    h.getUserMedia.mockRejectedValueOnce(new DOMException('사용자가 막음', 'NotAllowedError'))
    expect((await captureFailure(webAudioCapture(() => {}))).reason).toBe('permission')

    h.getUserMedia.mockRejectedValueOnce(new DOMException('장치 없음', 'NotFoundError'))
    expect((await captureFailure(webAudioCapture(() => {}))).reason).toBe('unavailable')

    // 스트림을 얻지 못했으니 컨텍스트는 아예 만들지 않는다 — 닫을 것도 남지 않는다
    expect(h.order).toEqual([])
  })
})
