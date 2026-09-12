import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { REQUIRED_BRIDGE_VERSION, type AccenturyBridge } from '../bridge/bridge'
import { resolveAdConsentSource, useAdConsent } from './adConsent'
import { readWebAdConsent, resetWebAdConsentMemory } from './webAdConsentStore'

/** 광고 동의를 아는 앱의 브리지 대역. 읽기 값은 인자, 쓰기는 기록만 한다 */
function adBridge(consent: string, setAdConsent = vi.fn()): AccenturyBridge {
  return {
    requestMicPermission: vi.fn(),
    startVoiceItem: vi.fn(),
    getContractVersion: () => REQUIRED_BRIDGE_VERSION,
    getAdConsent: () => consent,
    setAdConsent,
  }
}

/** 광고 동의 메서드를 모르는 구버전 앱 */
function oldBridge(): AccenturyBridge {
  return {
    requestMicPermission: vi.fn(),
    startVoiceItem: vi.fn(),
    getContractVersion: () => REQUIRED_BRIDGE_VERSION,
  }
}

afterEach(() => {
  delete window.AccenturyBridge
  // 웹 경로의 사본은 모듈 변수다 — 비우지 않으면 앞 테스트의 선택이 다음 테스트의 초기값이 된다
  resetWebAdConsentMemory()
})

describe('resolveAdConsentSource — 어느 저장소에 묻는가 (KAN-197 2단계)', () => {
  it('광고 동의 메서드를 아는 앱이면 브리지다', () => {
    expect(resolveAdConsentSource('?bridge=1', adBridge('unknown'))).toBe('bridge')
  })

  it('객체도 ?bridge=도 없으면 브라우저 단독 실행이라 웹 저장소다', () => {
    expect(resolveAdConsentSource('', undefined)).toBe('web')
  })

  it('객체는 있는데 메서드를 모르면 구버전 앱이라 물을 곳이 없다', () => {
    // 웹으로 보내면 앱 안에서 브라우저 저장소에 적게 되고, SDK를 세우는 네이티브는 그 값을 모른다
    expect(resolveAdConsentSource('?bridge=1', oldBridge())).toBe('none')
  })

  it('?bridge=는 있는데 객체가 없는 WebView도 물을 곳이 없다', () => {
    expect(resolveAdConsentSource('?bridge=1', undefined)).toBe('none')
  })
})

describe('useAdConsent — 실행 환경에 따라 저장소와 사업자가 갈린다', () => {
  it('브라우저 단독 실행은 웹 저장소에 묻고 사업자는 AdSense다', () => {
    const { result } = renderHook(() => useAdConsent())

    // 아직 고른 적이 없으니 시트를 띄우라는 뜻의 unknown이다
    expect(result.current.consent).toBe('unknown')
    expect(result.current.vendor).toBe('adsense')

    act(() => result.current.choose('denied'))

    expect(result.current.consent).toBe('denied')
    // 훅 안에만 남은 것이 아니라 공용 저장소를 거쳤다 — 같은 방문의 다른 독자도 이 값을 본다
    // (실제 localStorage 왕복은 `webAdConsentStore.test.ts`가 저장소 대역으로 본다)
    expect(readWebAdConsent()).toBe('denied')
  })

  it('앱 안이면 네이티브에 쓰고 사업자는 AdMob이다', () => {
    const setAdConsent = vi.fn()
    window.AccenturyBridge = adBridge('unknown', setAdConsent)

    const { result } = renderHook(() => useAdConsent())
    expect(result.current.vendor).toBe('admob')

    act(() => result.current.choose('granted'))

    expect(setAdConsent).toHaveBeenCalledWith('granted')
    expect(result.current.consent).toBe('granted')
  })

  it('구버전 앱에는 물을 곳이 없어 consent가 null이다', () => {
    window.AccenturyBridge = oldBridge()

    const { result } = renderHook(() => useAdConsent())

    expect(result.current.consent).toBeNull()
  })
})
