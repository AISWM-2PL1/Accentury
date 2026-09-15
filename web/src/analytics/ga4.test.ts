import { afterEach, describe, expect, it } from 'vitest'
import { installGa4Tag } from './ga4'

afterEach(() => {
  delete window.gtag
  delete window.dataLayer
  document.head.querySelectorAll('script[src*="googletagmanager"]').forEach((el) => el.remove())
})

/** 큐에 쌓인 gtag 호출을 읽기 좋게 편다 (`arguments` 객체라 배열로 바꿔 본다) */
function calls(): unknown[][] {
  return (window.dataLayer ?? []).map((entry) => Array.from(entry as IArguments))
}

describe('installGa4Tag — 태그 설치 (KAN-33)', () => {
  it('측정 ID가 있으면 큐를 세우고 태그 스크립트를 붙인다', () => {
    expect(installGa4Tag('G-TEST123')).toBe(true)

    const script = document.head.querySelector<HTMLScriptElement>('script[src*="googletagmanager"]')
    expect(script?.src).toBe('https://www.googletagmanager.com/gtag/js?id=G-TEST123')
    expect(script?.async).toBe(true)
    expect(typeof window.gtag).toBe('function')
  })

  it('광고 식별자와 구글 신호를 끈 채로 설정한다 (사용자 ID·광고 식별자 미설정 요구)', () => {
    installGa4Tag('G-TEST123')

    const config = calls().find((call) => call[0] === 'config')
    expect(config?.[1]).toBe('G-TEST123')
    expect(config?.[2]).toEqual({
      allow_google_signals: false,
      allow_ad_personalization_signals: false,
    })
  })

  it('측정 ID가 없으면(로컬·CI 빌드) 아무것도 설치하지 않는다', () => {
    expect(installGa4Tag(undefined)).toBe(false)
    expect(installGa4Tag('   ')).toBe(false)
    expect(window.gtag).toBeUndefined()
    expect(document.head.querySelector('script[src*="googletagmanager"]')).toBeNull()
  })

  it('두 번 불러도 태그를 두 번 붙이지 않는다 (StrictMode 재실행)', () => {
    installGa4Tag('G-TEST123')

    expect(installGa4Tag('G-TEST123')).toBe(false)
    expect(document.head.querySelectorAll('script[src*="googletagmanager"]').length).toBe(1)
  })

  it('이미 있던 큐를 갈아엎지 않는다 — 태그보다 먼저 쌓인 호출이 살아 있어야 한다', () => {
    const existing: unknown[] = ['before']
    window.dataLayer = existing

    installGa4Tag('G-TEST123')

    expect(window.dataLayer).toBe(existing)
    expect(existing[0]).toBe('before')
  })
})
