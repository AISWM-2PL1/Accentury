import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AdConsent } from '../bridge/bridge'
import {
  ADSENSE_SCRIPT_ORIGIN,
  adSenseIdsFromEnv,
  adSenseRequestFlags,
  applyAdConsentToAdSense,
  installAdSenseTag,
  pushAdSlot,
  resetAdSenseForTests,
} from './adsense'

/** 테스트용 ID 한 벌. 실제 값과 모양만 같으면 된다 (`ca-pub-` + 숫자 16자리) */
const IDS = { clientId: 'ca-pub-1234567890123456', slotId: '9876543210' }

afterEach(() => {
  resetAdSenseForTests()
  delete window.adsbygoogle
  vi.unstubAllEnvs()
  document.head.querySelectorAll('script[src*="adsbygoogle"]').forEach((el) => el.remove())
})

/** 붙은 태그 스크립트. 없으면 null */
function tagScript(): HTMLScriptElement | null {
  return document.head.querySelector<HTMLScriptElement>('script[src*="adsbygoogle"]')
}

describe('adSenseIdsFromEnv — 두 ID가 다 있어야 한다', () => {
  it('둘 다 있으면 읽는다', () => {
    vi.stubEnv('VITE_ADSENSE_CLIENT_ID', IDS.clientId)
    vi.stubEnv('VITE_ADSENSE_SLOT_ID', IDS.slotId)

    expect(adSenseIdsFromEnv()).toEqual(IDS)
  })

  it('게시자 ID가 없으면 null이다 — 슬롯만 알아도 태그를 붙일 수 없다', () => {
    vi.stubEnv('VITE_ADSENSE_CLIENT_ID', undefined)
    vi.stubEnv('VITE_ADSENSE_SLOT_ID', IDS.slotId)

    expect(adSenseIdsFromEnv()).toBeNull()
  })

  it('슬롯 ID가 없으면 null이다 — 태그만 붙이고 그릴 자리가 없으면 의미가 없다', () => {
    vi.stubEnv('VITE_ADSENSE_CLIENT_ID', IDS.clientId)
    vi.stubEnv('VITE_ADSENSE_SLOT_ID', undefined)

    expect(adSenseIdsFromEnv()).toBeNull()
  })

  // GitHub vars가 정의되지 않은 환경에서 빈 문자열로 주입되는 경우 (`regions.ts`와 같은 함정)
  it('빈 문자열과 공백은 없는 것과 같다', () => {
    vi.stubEnv('VITE_ADSENSE_CLIENT_ID', '')
    vi.stubEnv('VITE_ADSENSE_SLOT_ID', '   ')

    expect(adSenseIdsFromEnv()).toBeNull()
  })
})

describe('adSenseRequestFlags — 동의 세 갈래의 요청 플래그 (KAN-197 AC)', () => {
  it.each<[AdConsent, 0 | 1, 0 | 1]>([
    ['granted', 0, 0],
    ['denied', 1, 0],
    ['unknown', 1, 1],
  ])('%s이면 npa=%d, pause=%d', (consent, npa, pause) => {
    expect(adSenseRequestFlags(consent)).toEqual({
      requestNonPersonalizedAds: npa,
      pauseAdRequests: pause,
    })
  })

  it('허용도 거부도 요청을 재개한다 — pauseAdRequests=0을 빠뜨리면 광고가 하나도 안 나온다', () => {
    expect(adSenseRequestFlags('granted').pauseAdRequests).toBe(0)
    expect(adSenseRequestFlags('denied').pauseAdRequests).toBe(0)
  })
})

describe('installAdSenseTag — 태그 설치', () => {
  it('ID가 있으면 큐를 세우고 태그 스크립트를 붙인다', () => {
    expect(installAdSenseTag('granted', IDS)).toBe(true)

    const script = tagScript()
    expect(script?.src).toBe(ADSENSE_SCRIPT_ORIGIN + IDS.clientId)
    expect(script?.async).toBe(true)
    expect(script?.crossOrigin).toBe('anonymous')
    expect(Array.isArray(window.adsbygoogle)).toBe(true)
  })

  it('ID가 없는 빌드에서는 태그도 큐도 없다', () => {
    expect(installAdSenseTag('granted', null)).toBe(false)

    expect(tagScript()).toBeNull()
    expect(window.adsbygoogle).toBeUndefined()
  })

  it('거부한 방문은 비맞춤 요청으로 세운다 (요청 URL의 npa=1)', () => {
    installAdSenseTag('denied', IDS)

    expect(window.adsbygoogle?.requestNonPersonalizedAds).toBe(1)
    expect(window.adsbygoogle?.pauseAdRequests).toBe(0)
  })

  it('아직 묻지 않았으면 요청 자체를 멈춰 둔다', () => {
    installAdSenseTag('unknown', IDS)

    expect(window.adsbygoogle?.pauseAdRequests).toBe(1)
    expect(window.adsbygoogle?.requestNonPersonalizedAds).toBe(1)
  })

  it('허용한 방문은 맞춤 광고를 요청한다', () => {
    installAdSenseTag('granted', IDS)

    expect(window.adsbygoogle?.requestNonPersonalizedAds).toBe(0)
    expect(window.adsbygoogle?.pauseAdRequests).toBe(0)
  })

  it('두 번 불러도 태그를 두 번 붙이지 않는다 (StrictMode 재실행)', () => {
    installAdSenseTag('denied', IDS)

    expect(installAdSenseTag('denied', IDS)).toBe(false)
    expect(document.head.querySelectorAll('script[src*="adsbygoogle"]').length).toBe(1)
  })

  it('DOM 조작이 던져도 false를 돌려줄 뿐 던지지 않는다', () => {
    const doc = {
      createElement: () => {
        throw new Error('DOM이 막혔다')
      },
    } as unknown as Document

    expect(installAdSenseTag('denied', IDS, doc)).toBe(false)
  })
})

describe('applyAdConsentToAdSense — 선택을 바꾸면 다음 요청부터 반영된다', () => {
  it('이미 선 큐의 플래그를 갈아 끼운다', () => {
    installAdSenseTag('denied', IDS)

    applyAdConsentToAdSense('granted')

    expect(window.adsbygoogle?.requestNonPersonalizedAds).toBe(0)
    expect(window.adsbygoogle?.pauseAdRequests).toBe(0)
  })

  it('큐가 없으면 아무 일도 없다 — 시트는 태그가 서기 전인 인트로에 뜬다', () => {
    applyAdConsentToAdSense('denied')

    expect(window.adsbygoogle).toBeUndefined()
  })
})

describe('pushAdSlot — 슬롯 요청', () => {
  it('큐에 한 건을 밀어 넣는다', () => {
    installAdSenseTag('denied', IDS)

    expect(pushAdSlot()).toBe(true)
    expect(window.adsbygoogle?.length).toBe(1)
  })

  it('큐가 없으면 만들어서 넣는다 (공식 스니펫의 `||= []`)', () => {
    expect(pushAdSlot()).toBe(true)
    expect(window.adsbygoogle?.length).toBe(1)
  })

  it('push가 던져도 삼킨다 — 중복 push는 StrictMode의 정상 경로다', () => {
    // 태그가 로드된 뒤의 두 번째 push가 "All ins elements … already have ads"로 동기적으로 던진다
    const queue = [] as unknown as typeof window.adsbygoogle
    Object.defineProperty(queue, 'push', {
      value: () => {
        throw new Error('All ins elements in the DOM with class=adsbygoogle already have ads in them.')
      },
    })
    window.adsbygoogle = queue

    expect(pushAdSlot()).toBe(false)
  })
})
