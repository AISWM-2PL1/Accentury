import { describe, expect, it, vi } from 'vitest'
import {
  classifyMediaError,
  microphoneSupport,
  requestMicrophonePermission,
  type MicEnvironment,
} from './microphone'

/** 트랙 정지를 관찰할 수 있는 가짜 스트림 */
function fakeStream() {
  const tracks = [{ stop: vi.fn() }, { stop: vi.fn() }]
  return {
    tracks,
    stream: { getTracks: () => tracks } as unknown as MediaStream,
  }
}

function env(overrides: Partial<MicEnvironment> = {}): MicEnvironment {
  return {
    isSecureContext: true,
    getUserMedia: vi.fn(async () => fakeStream().stream),
    hasAudioWorklet: true,
    ...overrides,
  }
}

describe('microphoneSupport', () => {
  it('셋 다 갖춰지면 supported다', () => {
    expect(microphoneSupport(env())).toBe('supported')
  })

  it('보안 컨텍스트가 아니면 insecure-context다 (개발 WebView http://10.0.2.2)', () => {
    // 나머지가 다 있어도 보안 컨텍스트가 먼저 걸린다 — 실제로는 그 경우 나머지도 전부 없다
    expect(microphoneSupport(env({ isSecureContext: false }))).toBe('insecure-context')
  })

  it('getUserMedia가 없으면 no-media-devices다', () => {
    expect(microphoneSupport(env({ getUserMedia: undefined }))).toBe('no-media-devices')
  })

  it('AudioWorklet이 없으면 no-audio-worklet이다', () => {
    expect(microphoneSupport(env({ hasAudioWorklet: false }))).toBe('no-audio-worklet')
  })
})

describe('requestMicrophonePermission', () => {
  it('허용되면 granted이고 스트림 트랙을 곧바로 전부 끊는다', async () => {
    const { tracks, stream } = fakeStream()
    const getUserMedia = vi.fn(async () => stream)

    await expect(requestMicrophonePermission(env({ getUserMedia }))).resolves.toBe('granted')

    expect(getUserMedia).toHaveBeenCalledWith({ audio: true })
    // 게이트는 권한만 필요하다 — 스트림을 들고 있으면 인트로부터 마이크 표시등이 켜져 있다
    for (const track of tracks) expect(track.stop).toHaveBeenCalledTimes(1)
  })

  it('지원되지 않는 환경이면 요청 자체를 하지 않는다', async () => {
    const getUserMedia = vi.fn(async () => fakeStream().stream)

    await expect(
      requestMicrophonePermission(env({ isSecureContext: false, getUserMedia })),
    ).resolves.toBe('unsupported')
    expect(getUserMedia).not.toHaveBeenCalled()
  })

  it.each(['NotAllowedError', 'SecurityError', 'PermissionDeniedError'])(
    '%s는 사용자가 막은 것으로 본다',
    async (name) => {
      const getUserMedia = vi.fn(async () => {
        throw new DOMException('거부', name)
      })
      await expect(requestMicrophonePermission(env({ getUserMedia }))).resolves.toBe('denied')
    },
  )

  it.each(['NotFoundError', 'NotReadableError', 'OverconstrainedError', 'AbortError'])(
    '%s는 장치 문제로 본다',
    async (name) => {
      const getUserMedia = vi.fn(async () => {
        throw new DOMException('실패', name)
      })
      await expect(requestMicrophonePermission(env({ getUserMedia }))).resolves.toBe('unavailable')
    },
  )

  it('모르는 오류는 거부가 아니라 unavailable로 접는다', async () => {
    const getUserMedia = vi.fn(async () => {
      throw new Error('무언가 잘못됨')
    })
    await expect(requestMicrophonePermission(env({ getUserMedia }))).resolves.toBe('unavailable')
  })
})

describe('classifyMediaError', () => {
  it('Error가 아닌 값도 unavailable로 접는다', () => {
    expect(classifyMediaError('문자열')).toBe('unavailable')
    expect(classifyMediaError(undefined)).toBe('unavailable')
  })
})
