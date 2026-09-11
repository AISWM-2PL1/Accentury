import { afterEach, describe, expect, it, vi } from 'vitest'
import { REQUIRED_BRIDGE_VERSION, type AccenturyBridge } from '../bridge/bridge'
import { showInterstitialAdOnce } from './interstitial'

afterEach(() => {
  delete window.AccenturyBridge
})

/** 전면 광고를 아는 브리지 대역. `bridge.test.ts`의 것과 같은 규칙이다 */
function adBridge(show = vi.fn()): AccenturyBridge {
  return {
    requestMicPermission: vi.fn(),
    startVoiceItem: vi.fn(),
    getContractVersion: () => REQUIRED_BRIDGE_VERSION,
    showInterstitialAd: show,
  }
}

/*
 * 세션 id는 테스트마다 다르게 준다 — 이 모듈의 기록은 문서(테스트 파일) 수명 동안 남으므로
 * 같은 id를 두 테스트가 쓰면 앞 테스트의 요청이 뒤 테스트에서 "이미 나갔다"로 읽힌다.
 * 리셋 함수를 내보내는 것보다 이 편이 모듈의 뜻("문서 안에서 세션당 한 번")에 가깝다.
 */
describe('showInterstitialAdOnce — 세션당 한 번 (KAN-196)', () => {
  it('처음 부르면 요청이 나가고 true다', () => {
    const show = vi.fn()
    window.AccenturyBridge = adBridge(show)

    expect(showInterstitialAdOnce('once-first')).toBe(true)
    expect(show).toHaveBeenCalledTimes(1)
  })

  it('같은 세션으로 다시 부르면 요청이 나가지 않는다 — StrictMode 이중 실행·재마운트', () => {
    const show = vi.fn()
    window.AccenturyBridge = adBridge(show)

    showInterstitialAdOnce('once-twice')
    expect(showInterstitialAdOnce('once-twice')).toBe(false)
    expect(showInterstitialAdOnce('once-twice')).toBe(false)
    expect(show).toHaveBeenCalledTimes(1)
  })

  it('다른 세션이면 다시 한 번이다', () => {
    const show = vi.fn()
    window.AccenturyBridge = adBridge(show)

    showInterstitialAdOnce('once-a')
    expect(showInterstitialAdOnce('once-b')).toBe(true)
    expect(show).toHaveBeenCalledTimes(2)
  })

  it('브리지가 없으면 false이고 기록하지 않는다 — 나중에 브리지가 생기면 그때 한 번이다', () => {
    expect(showInterstitialAdOnce('once-late')).toBe(false)

    const show = vi.fn()
    window.AccenturyBridge = adBridge(show)
    expect(showInterstitialAdOnce('once-late')).toBe(true)
    expect(show).toHaveBeenCalledTimes(1)
  })
})
