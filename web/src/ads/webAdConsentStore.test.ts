import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  readWebAdConsent,
  resetWebAdConsentMemory,
  WEB_AD_CONSENT_KEY,
  writeWebAdConsent,
  type WebAdConsentStorage,
} from './webAdConsentStore'

/** Map 하나를 등에 업은 저장소 대역. 실물 localStorage와 같은 계약(없으면 null)이다 */
function fakeStorage(seed: Record<string, string> = {}): WebAdConsentStorage & { map: Map<string, string> } {
  const map = new Map(Object.entries(seed))
  return {
    map,
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => void map.set(key, value),
  }
}

afterEach(() => {
  // 사본은 모듈 변수라 테스트 사이에 남는다 — 비우지 않으면 앞 테스트가 고른 값이 새어 나간다
  resetWebAdConsentMemory()
})

describe('webAdConsentStore — 브라우저 저장소의 광고 동의 (KAN-197 2단계)', () => {
  it('저장된 것이 없으면 unknown이다 — 아직 묻지 않았다는 뜻이다', () => {
    expect(readWebAdConsent(fakeStorage())).toBe('unknown')
  })

  it.each(['granted', 'denied'] as const)('고른 값(%s)은 그대로 다시 읽힌다', (choice) => {
    const storage = fakeStorage()

    expect(writeWebAdConsent(choice, storage)).toBe(true)
    expect(storage.map.get(WEB_AD_CONSENT_KEY)).toBe(choice)
    expect(readWebAdConsent(storage)).toBe(choice)
  })

  it('계약 밖 문자열은 unknown으로 접는다 — 다시 물으면 제대로 된 값이 들어간다', () => {
    // 우리 자신의 옛 값이거나 사용자가 개발자 도구로 만진 것이다. 브리지처럼 null로 접지 않는다
    expect(readWebAdConsent(fakeStorage({ [WEB_AD_CONSENT_KEY]: 'yes' }))).toBe('unknown')
  })

  it('읽기가 던지는 저장소(쿠키 차단)에서도 unknown으로 끝난다', () => {
    const storage: WebAdConsentStorage = {
      getItem: () => {
        throw new DOMException('blocked', 'SecurityError')
      },
      setItem: () => {},
    }

    expect(readWebAdConsent(storage)).toBe('unknown')
  })

  it('쓰기가 던져도 true이고, 이번 방문 안에서는 고른 값이 지켜진다', () => {
    // 사생활 모드·쿼터 초과. 시트가 닫히고 링크가 생기려면 선택이 어디엔가는 남아야 한다
    const setItem = vi.fn(() => {
      throw new DOMException('quota', 'QuotaExceededError')
    })
    const storage: WebAdConsentStorage = { getItem: () => null, setItem }

    expect(writeWebAdConsent('granted', storage)).toBe(true)
    expect(setItem).toHaveBeenCalledTimes(1)
    expect(readWebAdConsent(storage)).toBe('granted')
  })

  it('사본을 비우면 저장소의 값으로 돌아간다 — 다음 방문이 보는 것이 그 값이다', () => {
    const storage = fakeStorage({ [WEB_AD_CONSENT_KEY]: 'denied' })
    writeWebAdConsent('granted', { getItem: () => null, setItem: () => {} })
    expect(readWebAdConsent(storage)).toBe('granted')

    resetWebAdConsentMemory()

    expect(readWebAdConsent(storage)).toBe('denied')
  })
})
