import { describe, expect, it, vi } from 'vitest'
import type { FetchLike } from './fetchTestDefinition'
import { submitVocabAnswer, VocabSubmitError, type VocabSubmission } from './submitVocabAnswer'

function submission(overrides: Partial<VocabSubmission> = {}): VocabSubmission {
  return {
    apiBase: 'http://localhost:8080',
    sessionId: 'sess-1',
    itemId: 'w1',
    choiceId: 'w1a',
    sessionToken: 'token-1',
    idempotencyKey: 'key-1',
    ...overrides,
  }
}

function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}

/** 오류 봉투(§2.3) 대역. 클라이언트가 읽지 않는 필드(correlationId)도 실물처럼 실어 둔다 */
function envelope(code: string, message: string, retryable: boolean) {
  return { code, message, retryable, retryAfterMs: null, correlationId: 'c_test' }
}

describe('요청 형태', () => {
  it('명세 §3.5 그대로 보낸다 — URL·Bearer·Idempotency-Key·본문', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, { accepted: true, answeredCount: 1, totalCount: 10 }))

    await submitVocabAnswer(submission(), fetchImpl)

    expect(fetchImpl).toHaveBeenCalledTimes(1)
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('http://localhost:8080/v0/sessions/sess-1/vocab-items/w1/answer')
    expect(init?.method).toBe('POST')
    expect(init?.headers).toEqual({
      'Content-Type': 'application/json',
      Authorization: 'Bearer token-1',
      'Idempotency-Key': 'key-1',
    })
    expect(JSON.parse(init?.body as string)).toEqual({ choiceId: 'w1a' })
  })

  it('빈 값이 있으면 네트워크를 타기 전에 끊는다', async () => {
    const fetchImpl = vi.fn<FetchLike>()

    await expect(submitVocabAnswer(submission({ sessionToken: ' ' }), fetchImpl)).rejects.toMatchObject({
      name: 'VocabSubmitError',
      retryable: false,
    })
    expect(fetchImpl).not.toHaveBeenCalled()
  })
})

describe('결과 해석', () => {
  it('200이면 SAVED다 — 본문 해석 실패가 성공을 뒤집지 않는다', async () => {
    // json()이 터지는 200: 저장은 된 것이므로 여전히 SAVED여야 한다
    const fetchImpl: FetchLike = async () =>
      ({ ok: true, status: 200, json: async () => { throw new Error('본문 없음') } }) as unknown as Response

    await expect(submitVocabAnswer(submission(), fetchImpl)).resolves.toEqual({ status: 'SAVED' })
  })

  it('409 ITEM_ALREADY_ANSWERED는 오류가 아니라 ALREADY_ANSWERED다 (응답 유실 복구)', async () => {
    const fetchImpl: FetchLike = async () =>
      jsonResponse(409, envelope('ITEM_ALREADY_ANSWERED', '이미 답변한 문항입니다.', false))

    await expect(submitVocabAnswer(submission(), fetchImpl)).resolves.toEqual({ status: 'ALREADY_ANSWERED' })
  })

  it('봉투가 말한 오류는 code·message·retryable을 그대로 싣는다', async () => {
    const fetchImpl: FetchLike = async () =>
      jsonResponse(409, envelope('SESSION_COMPLETED', '이미 완료된 테스트입니다.', false))

    const error = await submitVocabAnswer(submission(), fetchImpl).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(VocabSubmitError)
    expect(error).toMatchObject({
      code: 'SESSION_COMPLETED',
      message: '이미 완료된 테스트입니다.',
      retryable: false,
    })
  })

  it('봉투를 못 읽는 HTTP 오류는 상태 코드 문구로, 재시도 가능으로 돌린다', async () => {
    // 봉투 없는 오류의 대표: 게이트웨이가 낸 HTML 오류 페이지
    const fetchImpl: FetchLike = async () =>
      ({ ok: false, status: 502, json: async () => { throw new Error('HTML') } }) as unknown as Response

    await expect(submitVocabAnswer(submission(), fetchImpl)).rejects.toMatchObject({
      code: null,
      message: '답안을 제출하지 못했습니다 (HTTP 502)',
      retryable: true,
    })
  })

  it('봉투 모양이 아닌 JSON 오류 본문도 상태 코드 폴백으로 간다', async () => {
    const fetchImpl: FetchLike = async () => jsonResponse(500, { error: 'unexpected shape' })

    await expect(submitVocabAnswer(submission(), fetchImpl)).rejects.toMatchObject({
      code: null,
      retryable: true,
    })
  })

  it('네트워크 거부는 재시도 가능한 실패다 (멱등 키 덕에 재전송이 안전)', async () => {
    const fetchImpl: FetchLike = async () => {
      throw new TypeError('Failed to fetch')
    }

    await expect(submitVocabAnswer(submission(), fetchImpl)).rejects.toMatchObject({
      name: 'VocabSubmitError',
      code: null,
      retryable: true,
    })
  })
})
