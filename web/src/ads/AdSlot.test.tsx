import { render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { REQUIRED_BRIDGE_VERSION } from '../bridge/bridge'
import { AdSlot } from './AdSlot'
import { resetAdSenseForTests } from './adsense'
import { resetWebAdConsentMemory, writeWebAdConsent } from './webAdConsentStore'

const CLIENT_ID = 'ca-pub-1234567890123456'
const SLOT_ID = '9876543210'

/** 두 빌드 변수가 다 있는 빌드 */
function stubIds(): void {
  vi.stubEnv('VITE_ADSENSE_CLIENT_ID', CLIENT_ID)
  vi.stubEnv('VITE_ADSENSE_SLOT_ID', SLOT_ID)
}

afterEach(() => {
  resetAdSenseForTests()
  resetWebAdConsentMemory()
  vi.unstubAllEnvs()
  delete window.adsbygoogle
  delete window.AccenturyBridge
  document.head.querySelectorAll('script[src*="adsbygoogle"]').forEach((el) => el.remove())
  // 주소를 만진 테스트가 있다 — 남겨 두면 다음 테스트가 앱 WebView로 판정된다
  window.history.replaceState({}, '', '/')
})

describe('AdSlot — 브라우저 단독 실행의 배너 (KAN-197 3단계)', () => {
  it('ID가 있는 웹 단독 실행에서 슬롯을 그리고 요청을 밀어 넣는다', () => {
    stubIds()
    // 이 방문은 이미 거부를 골랐다 — 대기 화면에 올 때는 인트로에서 선택이 끝나 있다
    writeWebAdConsent('denied')

    render(<AdSlot />)

    const slot = screen.getByRole('complementary', { name: '광고' })
    const ins = slot.querySelector('ins.adsbygoogle')
    expect(ins).not.toBeNull()
    expect(ins?.getAttribute('data-ad-client')).toBe(CLIENT_ID)
    expect(ins?.getAttribute('data-ad-slot')).toBe(SLOT_ID)
    expect(window.adsbygoogle?.length).toBe(1)
    // 저장된 선택이 그대로 요청 플래그가 된다
    expect(window.adsbygoogle?.requestNonPersonalizedAds).toBe(1)
    expect(window.adsbygoogle?.pauseAdRequests).toBe(0)
  })

  /*
   * StrictMode 이중 마운트에서도 요청은 한 번이다 (리뷰 P1-2, 2026-09-13).
   *
   * 제품의 트리가 `main.tsx`에서 `<StrictMode>`로 감싸여 있으므로 개발 빌드의 실제 모양이
   * 이것이다. 표식이 없던 3단계에서는 여기가 2가 된다 — 큐에 아직 스크립트가 안 붙은 동안에는
   * 두 번째 push가 던지지 않고 조용히 성공하기 때문이다 (`AdSlot.tsx`의 이펙트 주석).
   */
  it('StrictMode로 감싸도 슬롯 요청은 한 번뿐이다', () => {
    stubIds()
    writeWebAdConsent('denied')

    render(
      <StrictMode>
        <AdSlot />
      </StrictMode>,
    )

    expect(window.adsbygoogle?.length).toBe(1)
    // 건너뛴 근거가 DOM에 남는다
    const ins = screen.getByRole('complementary', { name: '광고' }).querySelector('ins.adsbygoogle')
    expect(ins?.getAttribute('data-accentury-pushed')).toBe('1')
  })

  it('앱 WebView에서는 슬롯도 태그도 없다 — AdSense를 앱 안에서 돌리지 않는다', () => {
    stubIds()
    window.AccenturyBridge = {
      requestMicPermission: vi.fn(),
      startVoiceItem: vi.fn(),
      getContractVersion: () => REQUIRED_BRIDGE_VERSION,
    }

    render(<AdSlot />)

    expect(screen.queryByRole('complementary')).toBeNull()
    expect(window.adsbygoogle).toBeUndefined()
  })

  it('브리지 객체 없이 ?bridge=만 있는 WebView에서도 그리지 않는다', () => {
    stubIds()
    /*
     * `isStandaloneWeb`은 객체도 `?bridge=`도 없을 때만 참이다 (`bridge.ts`) — 객체 주입이
     * 늦은 구버전 앱을 웹으로 오인하지 않기 위한 판정이고, 광고도 같은 판정을 탄다.
     * `location`을 통째로 대역으로 갈아끼우는 대신 주소만 바꾼다 (jsdom에서 실제로 반영된다).
     */
    window.history.replaceState({}, '', '/?bridge=1')

    render(<AdSlot />)

    expect(screen.queryByRole('complementary')).toBeNull()
    expect(window.adsbygoogle).toBeUndefined()
  })

  it('게시자 ID가 없는 빌드에서는 빈 자리도 남기지 않는다', () => {
    vi.stubEnv('VITE_ADSENSE_CLIENT_ID', undefined)
    vi.stubEnv('VITE_ADSENSE_SLOT_ID', SLOT_ID)

    render(<AdSlot />)

    expect(screen.queryByRole('complementary')).toBeNull()
    expect(window.adsbygoogle).toBeUndefined()
  })

  it('슬롯 ID가 없는 빌드도 마찬가지다 (로컬·CI 기본)', () => {
    vi.stubEnv('VITE_ADSENSE_CLIENT_ID', CLIENT_ID)
    vi.stubEnv('VITE_ADSENSE_SLOT_ID', undefined)

    render(<AdSlot />)

    expect(screen.queryByRole('complementary')).toBeNull()
    expect(window.adsbygoogle).toBeUndefined()
  })
})
