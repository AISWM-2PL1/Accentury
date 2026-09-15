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

  /*
   * 이름을 사실대로 고쳤다 (리뷰 P1-1, 2026-09-13). 원래 이름이 「요청 URL의 npa=1」을 내걸었는데
   * 여기서 보는 것은 큐 프로퍼티다 — jsdom에는 광고 요청이라는 것이 없다. 요청 URL을 실제로
   * 보는 자리는 E2E다 (`e2e/full-run.spec.ts`의 KAN-197 케이스).
   */
  it('거부한 방문은 큐에 비맞춤 플래그를 세운다', () => {
    installAdSenseTag('denied', IDS)

    expect(window.adsbygoogle?.requestNonPersonalizedAds).toBe(1)
    expect(window.adsbygoogle?.pauseAdRequests).toBe(0)
  })

  /*
   * 순서가 계약이라는 말의 실체다 (리뷰 P1-1). 문서가 "You must do this before triggering any ad
   * requests"라고 적은 것은 곧 **스크립트가 큐를 집어 들기 전에** 플래그가 서 있어야 한다는
   * 뜻이고, 그 시점을 테스트가 붙들 수 있는 자리가 `appendChild` 호출 순간이다 — 스크립트
   * 요소가 문서에 붙는 그 줄부터 브라우저가 내려받기를 시작한다.
   *
   * 완료 뒤의 상태만 보는 단언은 이 순서를 못 잡는다. 큐를 스크립트 뒤에 세우도록 코드를
   * 뒤집어도 최종 상태는 똑같기 때문이다.
   */
  it('스크립트를 붙이기 전에 플래그가 이미 서 있다 — 순서가 계약이다', () => {
    const flagsAtAppend: Array<0 | 1 | undefined> = []
    const doc = {
      createElement: (tag: string) => document.createElement(tag),
      head: {
        appendChild: (node: Node) => {
          flagsAtAppend.push(window.adsbygoogle?.requestNonPersonalizedAds)
          return document.head.appendChild(node)
        },
      },
    } as unknown as Document

    expect(installAdSenseTag('denied', IDS, doc)).toBe(true)

    expect(flagsAtAppend).toEqual([1])
    expect(tagScript()?.src).toBe(ADSENSE_SCRIPT_ORIGIN + IDS.clientId)
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
    expect(applyAdConsentToAdSense('denied')).toBe(false)

    expect(window.adsbygoogle).toBeUndefined()
  })

  /*
   * 남이 만든 큐에서도 던지지 않는다 (리뷰 P1-3, 2026-09-13). 부르는 자리가 시트의 `choose`라
   * 여기서 예외가 오르면 선택을 바꾼 순간 인트로가 날아간다. 두 케이스는 실제로 관찰되는 모양을
   * 각각 세운 것이다 — 차단 확장이 큐를 동결해 둔 경우와 대역 객체로 갈아끼운 경우다.
   */
  it('동결된 큐에서는 false를 돌려줄 뿐 던지지 않는다', () => {
    window.adsbygoogle = Object.freeze([]) as unknown as typeof window.adsbygoogle

    expect(applyAdConsentToAdSense('denied')).toBe(false)
  })

  it('setter가 던지는 큐에서도 마찬가지다', () => {
    const queue = [] as unknown as NonNullable<typeof window.adsbygoogle>
    Object.defineProperty(queue, 'requestNonPersonalizedAds', {
      set: () => {
        throw new Error('큐를 갈아끼운 확장이 막았다')
      },
    })
    window.adsbygoogle = queue

    expect(applyAdConsentToAdSense('denied')).toBe(false)
  })

  it('갱신에 성공하면 true다', () => {
    installAdSenseTag('unknown', IDS)

    expect(applyAdConsentToAdSense('granted')).toBe(true)
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
