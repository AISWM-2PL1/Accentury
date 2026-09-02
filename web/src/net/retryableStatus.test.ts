import { describe, expect, it } from 'vitest'
import { isRetryableStatus } from './retryableStatus'

describe('isRetryableStatus — 봉투 없는 HTTP 실패의 재시도 판정', () => {
  it.each([500, 502, 503])('5xx(%i)는 서버 쪽 사정이라 다시 보낼 수 있다', (status) => {
    expect(isRetryableStatus(status)).toBe(true)
  })

  it.each([408, 429])('%i는 시간·부하가 원인이라 잠시 뒤 통할 수 있다', (status) => {
    expect(isRetryableStatus(status)).toBe(true)
  })

  it('403은 재시도하지 않는다 — WAF 기본 응답이 여기로 온다', () => {
    expect(isRetryableStatus(403)).toBe(false)
  })

  it.each([400, 401, 404, 409, 410, 422])('%i는 다시 보내도 같은 답이라 재시도하지 않는다', (status) => {
    expect(isRetryableStatus(status)).toBe(false)
  })
})
