import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AccenturyBridge } from '../bridge/bridge'
import { clearTestId, ensureTestId } from './testId'
import { track } from './track'

beforeEach(() => {
  // DEV 빌드의 진단 로그를 잠재운다 — 확인 대상은 어디로 나갔는가이지 로그가 아니다
  vi.spyOn(console, 'debug').mockImplementation(() => {})
})

afterEach(() => {
  clearTestId()
  delete window.AccenturyBridge
  delete window.gtag
  delete window.dataLayer
  vi.restoreAllMocks()
})

/** 계측만 갖춘 브리지 대역 — 나머지 계약은 이 테스트의 관심사가 아니다 */
function bridgeWithLogEvent(logEvent: AccenturyBridge['logEvent']): void {
  window.AccenturyBridge = {
    requestMicPermission: vi.fn(),
    startVoiceItem: vi.fn(),
    getContractVersion: () => 1,
    logEvent,
  }
}

describe('track — 앱 안 (브리지 → 네이티브 Firebase)', () => {
  it('브리지가 있으면 이벤트명과 파라미터 JSON을 네이티브로 넘긴다', () => {
    const logEvent = vi.fn()
    bridgeWithLogEvent(logEvent)

    track({ name: 'referral_opened', campaign: 'kko_share' })

    expect(logEvent).toHaveBeenCalledWith('referral_opened', JSON.stringify({ campaign: 'kko_share' }))
  })

  it('네이티브가 가져갔으면 gtag로 한 번 더 보내지 않는다 — 이중 집계 방지', () => {
    bridgeWithLogEvent(vi.fn())
    const gtag = vi.fn()
    window.gtag = gtag

    track({ name: 'test_completed', campaign: null })

    expect(gtag).not.toHaveBeenCalled()
  })

  it('계측을 모르는 구버전 앱이면 이벤트를 버린다 — 웹 스트림으로 새지 않는다', () => {
    window.AccenturyBridge = {
      requestMicPermission: vi.fn(),
      startVoiceItem: vi.fn(),
      getContractVersion: () => 1,
    }
    const gtag = vi.fn()
    window.gtag = gtag

    track({ name: 'retest_started' })

    // 계측 하나를 잃는 편이 앱 사용자를 웹 트래픽으로 세는 것보다 낫다
    expect(gtag).not.toHaveBeenCalled()
  })
})

describe('track — 웹 단독 실행 (gtag → GA4)', () => {
  it('이벤트명과 파라미터를 gtag 모양으로 보낸다', () => {
    const gtag = vi.fn()
    window.gtag = gtag

    track({ name: 'referral_opened', campaign: 'kko_share' })

    expect(gtag).toHaveBeenCalledWith('event', 'referral_opened', { campaign: 'kko_share' })
  })

  it('지점별 파라미터가 그대로 실린다 — 다운로드에는 스토어가 붙는다', () => {
    const gtag = vi.fn()
    window.gtag = gtag

    track({ name: 'app_download_clicked', campaign: null, platform: 'ios' })

    expect(gtag).toHaveBeenCalledWith('event', 'app_download_clicked', {
      campaign: null,
      platform: 'ios',
    })
  })

  it('운영 지표도 같은 창구로 나간다 (KAN-24 트리거의 계기판)', () => {
    const gtag = vi.fn()
    window.gtag = gtag

    track({ name: 'analysis_wait_duration', duration_ms: 8200, pending_item_count: 2 })

    expect(gtag).toHaveBeenCalledWith('event', 'analysis_wait_duration', {
      duration_ms: 8200,
      pending_item_count: 2,
    })
  })
})

describe('track — 보낼 곳이 없거나 전송이 깨져도 흐름을 끊지 않는다', () => {
  it('태그도 브리지도 없으면(측정 ID 없는 빌드) 아무 일도 하지 않는다', () => {
    expect(() => track({ name: 'test_completed', campaign: 'kko_share' })).not.toThrow()
    expect(window.dataLayer).toBeUndefined()
  })

  it('gtag가 던져도 호출자에게 튀지 않는다', () => {
    window.gtag = () => {
      throw new Error('tag exploded')
    }

    expect(() => track({ name: 'referral_opened', campaign: 'kko_share' })).not.toThrow()
  })

  it('브리지가 던져도 호출자에게 튀지 않는다', () => {
    bridgeWithLogEvent(() => {
      throw new Error('bridge exploded')
    })

    expect(() => track({ name: 'referral_test_started', campaign: null })).not.toThrow()
  })
})

describe('track — 응시 상관 키 (KAN-33 AC 1)', () => {
  it('응시가 시작된 뒤에는 모든 이벤트에 같은 키가 붙는다', () => {
    const gtag = vi.fn()
    window.gtag = gtag
    const testId = ensureTestId('s_1')

    track({ name: 'item_shown', item_seq: 1, item_type: 'VOICE' })
    track({ name: 'test_completed', campaign: null })

    // 지점마다 다시 적는 값이 아니라 창구가 공통으로 얹는다 — 지점이 늘 때 빠뜨릴 자리가 없다
    expect(gtag).toHaveBeenNthCalledWith(1, 'event', 'item_shown', {
      item_seq: 1,
      item_type: 'VOICE',
      test_id: testId,
    })
    expect(gtag).toHaveBeenNthCalledWith(2, 'event', 'test_completed', {
      campaign: null,
      test_id: testId,
    })
  })

  it('세션이 생기기 전(인트로 유입)에는 붙지 않는다', () => {
    const gtag = vi.fn()
    window.gtag = gtag

    track({ name: 'referral_opened', campaign: 'kko_share' })

    expect(gtag).toHaveBeenCalledWith('event', 'referral_opened', { campaign: 'kko_share' })
  })

  it('브리지 경로에도 같은 키가 실린다 — 앱·웹이 같은 축으로 묶인다', () => {
    const logEvent = vi.fn()
    bridgeWithLogEvent(logEvent)
    const testId = ensureTestId('s_1')

    track({ name: 'retest_started' })

    expect(logEvent).toHaveBeenCalledWith('retest_started', JSON.stringify({ test_id: testId }))
  })
})
