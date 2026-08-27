import { afterEach, describe, expect, it, vi } from 'vitest'
import { shareResult } from './shareResult'
import type { ResultShare } from '../result/testResult'

const share: ResultShare = {
  imageUrl: 'https://static.accentury.app/tier/honorary.png',
  text: '나는 명예주민! 너도 시도해볼래?',
  webTestUrl: 'https://accentury.app/t?c=kko_share',
}

/**
 * 되돌릴 것들을 한 자리에 모은다. jsdom에는 `navigator.share`도 `navigator.clipboard`도
 * `window.alert`도 없어서 매번 심었다가 지워야 하는데, 하나라도 남으면 다음 테스트가 그
 * 잔재를 자기 환경으로 착각한다 (채널 우선순위가 곧 이 테스트의 관심사라 특히 위험하다).
 */
const restores: Array<() => void> = []

afterEach(() => {
  while (restores.length > 0) restores.pop()?.()
  delete window.AccenturyBridge
  vi.restoreAllMocks()
})

/** `navigator.share`를 심는다 (App.test.tsx의 `withNavigatorShare`와 같은 방식) */
function stubNavigatorShare(fn: (data: ShareData) => Promise<void>) {
  Object.defineProperty(navigator, 'share', { configurable: true, writable: true, value: fn })
  restores.push(() => {
    delete (navigator as { share?: unknown }).share
  })
}

/** `navigator.clipboard`를 심는다. jsdom에는 아예 없어서 객체째 정의한다 */
function stubClipboard(writeText: (text: string) => Promise<void>) {
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    writable: true,
    value: { writeText },
  })
  restores.push(() => {
    delete (navigator as { clipboard?: unknown }).clipboard
  })
}

/** `window.alert`는 jsdom에 없다 — 스텁을 심고 그 mock을 돌려준다 */
function stubAlert(): ReturnType<typeof vi.fn> {
  const alert = vi.fn()
  Object.defineProperty(window, 'alert', { configurable: true, writable: true, value: alert })
  restores.push(() => {
    delete (window as { alert?: unknown }).alert
  })
  return alert
}

/** `shareResult`를 아는 브리지 대역 (KAN-30 2단계 결선 후의 앱) */
function stubBridgeWithShare(): ReturnType<typeof vi.fn> {
  const shareFn = vi.fn()
  window.AccenturyBridge = {
    requestMicPermission: vi.fn(),
    startVoiceItem: vi.fn(),
    getContractVersion: () => 1,
    shareResult: shareFn,
  }
  return shareFn
}

describe('shareResult — 앱 안 (브리지 채널)', () => {
  it('브리지가 있으면 네이티브로 넘기고 bridge를 돌려준다', () => {
    const shareFn = stubBridgeWithShare()

    expect(shareResult(share)).toBe('bridge')
    expect(shareFn).toHaveBeenCalledWith(JSON.stringify(share))
  })

  it('브리지가 있으면 공유 시트를 열지 않는다 — 통로가 둘 열리면 시트가 두 번 뜬다', () => {
    stubBridgeWithShare()
    const systemShare = vi.fn(async () => {})
    stubNavigatorShare(systemShare)

    expect(shareResult(share)).toBe('bridge')
    expect(systemShare).not.toHaveBeenCalled()
  })

  it('네이티브가 던져도 화면으로 새지 않고 웹 통로로 내려간다', () => {
    window.AccenturyBridge = {
      requestMicPermission: vi.fn(),
      startVoiceItem: vi.fn(),
      getContractVersion: () => 1,
      shareResult: () => {
        throw new Error('native boom')
      },
    }
    const systemShare = vi.fn(async () => {})
    stubNavigatorShare(systemShare)
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})

    expect(() => shareResult(share)).not.toThrow()
    expect(shareResult(share)).toBe('system')
    expect(systemShare).toHaveBeenCalled()
    expect(warn).toHaveBeenCalled()
  })
})

describe('shareResult — 브라우저 (공유 시트 채널)', () => {
  it('브리지가 없으면 navigator.share로 점수 없이 문구와 URL만 넘긴다', () => {
    const systemShare = vi.fn(async () => {})
    stubNavigatorShare(systemShare)

    expect(shareResult(share)).toBe('system')
    expect(systemShare).toHaveBeenCalledWith({
      text: '나는 명예주민! 너도 시도해볼래?',
      url: 'https://accentury.app/t?c=kko_share',
    })
  })

  it('사용자가 시트를 닫아 reject가 와도 예외로 새지 않는다 (KAN-30 AC)', async () => {
    stubNavigatorShare(vi.fn(async () => Promise.reject(new Error('AbortError'))))

    expect(shareResult(share)).toBe('system')
    // 처리되지 않은 rejection이 남으면 이 microtask 비우기 지점에서 드러난다.
    await Promise.resolve()
  })
})

describe('shareResult — 폴백 (링크 복사 · 미지원)', () => {
  it('공유 시트가 없으면 링크를 복사하고 붙여넣기를 안내한다', async () => {
    const writeText = vi.fn(async () => {})
    stubClipboard(writeText)
    const alert = stubAlert()

    expect(shareResult(share)).toBe('clipboard')
    expect(writeText).toHaveBeenCalledWith('https://accentury.app/t?c=kko_share')

    // 안내는 복사가 실제로 끝난 뒤에 뜬다 — 미리 띄우면 실패했을 때 없는 링크를 붙여넣는다.
    await vi.waitFor(() => expect(alert).toHaveBeenCalledWith('공유 링크를 복사했어요 · 붙여넣어서 보내 주세요'))
  })

  it('복사가 거절되면 미지원과 같은 안내로 내려간다', async () => {
    stubClipboard(vi.fn(async () => Promise.reject(new Error('denied'))))
    const alert = stubAlert()
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})

    expect(shareResult(share)).toBe('clipboard')

    await vi.waitFor(() => expect(alert).toHaveBeenCalledWith('이 환경에서는 공유를 지원하지 않아요'))
    expect(warn).toHaveBeenCalled()
  })

  it('아무 통로도 없으면 안내만 남기고 unsupported를 돌려준다', () => {
    const alert = stubAlert()
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})

    expect(shareResult(share)).toBe('unsupported')
    expect(alert).toHaveBeenCalledWith('이 환경에서는 공유를 지원하지 않아요')
    expect(warn).toHaveBeenCalled()
  })

  it('alert조차 막힌 환경에서도 던지지 않는다 — 공유 실패가 결과 화면을 깨면 안 된다', () => {
    Object.defineProperty(window, 'alert', {
      configurable: true,
      writable: true,
      value: () => {
        throw new Error('alert blocked')
      },
    })
    restores.push(() => {
      delete (window as { alert?: unknown }).alert
    })
    vi.spyOn(console, 'warn').mockImplementation(() => {})

    expect(() => shareResult(share)).not.toThrow()
    expect(shareResult(share)).toBe('unsupported')
  })
})
