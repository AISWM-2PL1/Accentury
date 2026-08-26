import { describe, expect, it } from 'vitest'
import { readCampaignToken, sanitizeCampaignToken } from './campaign'

describe('readCampaignToken — 공유 링크의 ?c=', () => {
  it('공유 URL 형태에서 코드를 읽는다', () => {
    // 결과 화면이 넘기는 `webTestUrl`과 같은 형태다 (백엔드 application.yml)
    expect(readCampaignToken('?c=kko_share')).toBe('kko_share')
  })

  it('다른 진입 파라미터와 섞여 있어도 읽는다', () => {
    expect(readCampaignToken('?screen=test&c=kko_a1b2&sessionId=s_1')).toBe('kko_a1b2')
  })

  it('없거나 비어 있으면 null이다', () => {
    expect(readCampaignToken('')).toBeNull()
    expect(readCampaignToken('?bridge=1&app=1.0')).toBeNull()
    expect(readCampaignToken('?c=')).toBeNull()
  })
})

describe('sanitizeCampaignToken — 서버가 받아 주는 형태만', () => {
  it('영숫자와 ._- 조합을 통과시킨다', () => {
    expect(sanitizeCampaignToken('kko_a1b2')).toBe('kko_a1b2')
    expect(sanitizeCampaignToken('a.b-c_1')).toBe('a.b-c_1')
  })

  it('망가진 코드는 null이다 — 계측 하나 때문에 응시가 400으로 막히지 않게 한다', () => {
    expect(sanitizeCampaignToken('kko share')).toBeNull()
    expect(sanitizeCampaignToken('한글')).toBeNull()
    expect(sanitizeCampaignToken('x'.repeat(65))).toBeNull()
    expect(sanitizeCampaignToken('')).toBeNull()
    expect(sanitizeCampaignToken(null)).toBeNull()
    expect(sanitizeCampaignToken(undefined)).toBeNull()
  })
})
