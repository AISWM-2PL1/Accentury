import { describe, expect, it } from 'vitest'
import { parseRetestFailure, type RetestFailure } from './retestFailure'

const valid: RetestFailure = {
  code: 'RATE_LIMITED',
  message: '접속이 몰리고 있어요 · 잠시 뒤에 다시 시도해 주세요',
  retryable: true,
  retryAfterMs: 5_000,
}

/** 유효 payload에서 한 필드만 비틀어 본다 — 거부 사유가 그 필드 때문임을 분명히 하기 위해서다. */
function payloadWith(patch: Record<string, unknown>): string {
  return JSON.stringify({ ...valid, ...patch })
}

describe('parseRetestFailure — 계약대로인 payload', () => {
  it('네 필드가 모두 맞으면 그대로 통과시킨다', () => {
    expect(parseRetestFailure(JSON.stringify(valid))).toEqual(valid)
  })

  it('봉투를 못 읽은 응답의 code null을 받는다', () => {
    expect(parseRetestFailure(payloadWith({ code: null }))?.code).toBeNull()
  })

  it('code가 아예 빠져 있어도 null로 본다 — 빠진 것과 null이 같은 사실이다', () => {
    const { code: _dropped, ...withoutCode } = valid
    expect(parseRetestFailure(JSON.stringify(withoutCode))?.code).toBeNull()
  })

  it('429가 아닌 실패의 retryAfterMs null을 받는다', () => {
    expect(parseRetestFailure(payloadWith({ retryAfterMs: null }))?.retryAfterMs).toBeNull()
  })

  it('재시도 불가 거절도 통과시킨다 — 화면이 버튼을 접을 근거다', () => {
    const parsed = parseRetestFailure(payloadWith({ code: 'VALIDATION_FAILED', retryable: false }))
    expect(parsed?.retryable).toBe(false)
  })

  it('모르는 필드가 섞여 있어도 통과시킨다 (필드 추가는 하위호환)', () => {
    expect(parseRetestFailure(payloadWith({ futureField: 'whatever' }))).toEqual(valid)
  })

  it('대기 0ms는 유효하다 (음수만 버린다)', () => {
    expect(parseRetestFailure(payloadWith({ retryAfterMs: 0 }))?.retryAfterMs).toBe(0)
  })
})

describe('parseRetestFailure — 신뢰 경계 밖 입력은 거부한다', () => {
  it('JSON이 아니면 null', () => {
    expect(parseRetestFailure('')).toBeNull()
    expect(parseRetestFailure('{oops')).toBeNull()
  })

  it('객체가 아니면 null', () => {
    expect(parseRetestFailure('[]')).toBeNull()
    expect(parseRetestFailure('null')).toBeNull()
    expect(parseRetestFailure('"RATE_LIMITED"')).toBeNull()
  })

  it('보여줄 문구가 없으면 null — 이 회신으로 화면이 할 수 있는 말이 없다', () => {
    const { message: _dropped, ...withoutMessage } = valid
    expect(parseRetestFailure(JSON.stringify(withoutMessage))).toBeNull()
    expect(parseRetestFailure(payloadWith({ message: '' }))).toBeNull()
    expect(parseRetestFailure(payloadWith({ message: '   ' }))).toBeNull()
    expect(parseRetestFailure(payloadWith({ message: 42 }))).toBeNull()
  })

  it('retryable이 boolean이 아니면 null — 버튼을 어느 쪽으로도 그릴 수 없다', () => {
    const { retryable: _dropped, ...withoutRetryable } = valid
    expect(parseRetestFailure(JSON.stringify(withoutRetryable))).toBeNull()
    expect(parseRetestFailure(payloadWith({ retryable: 'true' }))).toBeNull()
  })

  it('code가 문자열도 null도 아니면 null', () => {
    expect(parseRetestFailure(payloadWith({ code: 429 }))).toBeNull()
  })
})

/*
 * 대기 시간만 다르게 다룬다 — 그것 하나 때문에 payload째 버리면 사용자는 왜 아무 일도 일어나지
 * 않았는지 영영 듣지 못한다. 실패를 알리지 못하는 쪽이 대기 안내를 못 하는 쪽보다 나쁘다.
 */
describe('parseRetestFailure — 불량 retryAfterMs는 그 값만 버린다', () => {
  it('숫자가 아니면 대기 안내만 없앤다', () => {
    const parsed = parseRetestFailure(payloadWith({ retryAfterMs: '5000' }))
    expect(parsed?.retryAfterMs).toBeNull()
    expect(parsed?.message).toBe(valid.message)
  })

  it('음수 대기도 마찬가지다', () => {
    const parsed = parseRetestFailure(payloadWith({ retryAfterMs: -1 }))
    expect(parsed?.retryAfterMs).toBeNull()
    expect(parsed?.message).toBe(valid.message)
  })

  it('JSON이 표현할 수 있는 무한대(1e999)도 버린다', () => {
    // NaN·Infinity는 JSON 리터럴이 아니지만, 지수가 너무 큰 수는 파싱 결과가 Infinity가 된다.
    const raw = `{"code":null,"message":"실패","retryable":true,"retryAfterMs":1e999}`
    const parsed = parseRetestFailure(raw)
    expect(parsed?.retryAfterMs).toBeNull()
    expect(parsed?.message).toBe('실패')
  })
})
