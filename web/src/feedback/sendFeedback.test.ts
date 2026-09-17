import { describe, expect, it, vi } from 'vitest'
import type { FetchLike } from '../progress/fetchTestDefinition'
import {
  FeedbackApiError,
  FEEDBACK_ALREADY_SUBMITTED,
  FEEDBACK_BODY_MAX,
  FEEDBACK_EMAIL_MAX,
  sendFeedback,
  validateFeedbackInput,
  type FeedbackInput,
} from './sendFeedback'

const API_BASE = 'http://localhost:8080'

function input(overrides: Partial<FeedbackInput> = {}): FeedbackInput {
  return { rating: null, body: '화면이 예뻐요', contactEmail: null, ...overrides }
}

/** 1단계 계약의 오류 봉투(§2.3) */
function envelope(code: string, message: string, retryable: boolean, retryAfterMs: number | null = null) {
  return { code, message, retryable, retryAfterMs, correlationId: 'c_test' }
}

function respond(status: number, body: unknown, headers: Record<string, string> = {}): FetchLike {
  return async () =>
    ({
      ok: status >= 200 && status < 300,
      status,
      headers: { get: (name: string) => headers[name] ?? null },
      json: async () => body,
    }) as unknown as Response
}

function send(overrides: Partial<Parameters<typeof sendFeedback>[0]> = {}, fetchImpl?: FetchLike) {
  return sendFeedback(
    {
      apiBase: API_BASE,
      sessionId: 'sess-1',
      sessionToken: 'token-1',
      idempotencyKey: 'key-1',
      input: input(),
      ...overrides,
    },
    fetchImpl ?? respond(201, { accepted: true }),
  )
}

describe('sendFeedback — 요청 모양 (KAN-211 1단계 계약)', () => {
  it('세션 경로로 POST하고 Bearer·멱등 키·Content-Type을 싣는다', async () => {
    const fetchImpl = vi.fn<FetchLike>(respond(201, { accepted: true }))

    await expect(send({}, fetchImpl)).resolves.toEqual({ status: 'saved' })

    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('http://localhost:8080/v0/sessions/sess-1/feedback')
    expect(init?.method).toBe('POST')
    expect(init?.headers).toMatchObject({
      Authorization: 'Bearer token-1',
      'Idempotency-Key': 'key-1',
      'Content-Type': 'application/json',
      Accept: 'application/json',
    })
  })

  it('선택 값이 없으면 키째 뺀다 — 빈 문자열을 보내지 않는다', async () => {
    const fetchImpl = vi.fn<FetchLike>(respond(201, { accepted: true }))

    await send({ input: input({ rating: null, contactEmail: null, body: '  띄어쓰기 있음  ' }) }, fetchImpl)

    const body = JSON.parse(String(fetchImpl.mock.calls[0][1]?.body))
    // 서버가 trim해 주더라도 보내는 쪽이 정리한다 — 저장되는 값이 화면에서 보이던 값과 같아야 한다
    expect(body).toEqual({ body: '띄어쓰기 있음' })
    expect('rating' in body).toBe(false)
    expect('contactEmail' in body).toBe(false)
  })

  it('별점과 이메일을 적었으면 그대로 싣는다', async () => {
    const fetchImpl = vi.fn<FetchLike>(respond(201, { accepted: true }))

    await send({ input: input({ rating: 4, contactEmail: ' me@example.com ' }) }, fetchImpl)

    expect(JSON.parse(String(fetchImpl.mock.calls[0][1]?.body))).toEqual({
      rating: 4,
      body: '화면이 예뻐요',
      contactEmail: 'me@example.com',
    })
  })

  it('세션 값이 비면 네트워크를 타지 않는다', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const fetchImpl = vi.fn<FetchLike>()

    await expect(send({ sessionToken: '' }, fetchImpl)).rejects.toThrow(FeedbackApiError)
    expect(fetchImpl).not.toHaveBeenCalled()
    vi.restoreAllMocks()
  })
})

describe('sendFeedback — 응답 해석', () => {
  it('201은 저장이다', async () => {
    await expect(send({}, respond(201, { accepted: true }))).resolves.toEqual({ status: 'saved' })
  })

  it('200(같은 키 재전송)도 저장으로 본다 — 사용자가 한 일의 결과가 같다', async () => {
    await expect(send({}, respond(200, { accepted: true }))).resolves.toEqual({ status: 'saved' })
  })

  it('409 FEEDBACK_ALREADY_SUBMITTED는 예외가 아니라 정상 완료다', async () => {
    // 새로고침 뒤 다시 보낸 경우다 — 하려던 일은 이미 되어 있다
    const body = envelope(FEEDBACK_ALREADY_SUBMITTED, '이미 후기를 보냈습니다.', false)

    await expect(send({}, respond(409, body))).resolves.toEqual({ status: 'already' })
  })

  it('같은 409라도 RESULT_NOT_READY는 실패다 — 뜻이 정반대다', async () => {
    const body = envelope('RESULT_NOT_READY', '결과를 준비하고 있습니다.', true)

    const error = await send({}, respond(409, body)).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(FeedbackApiError)
    expect((error as FeedbackApiError).code).toBe('RESULT_NOT_READY')
    expect((error as FeedbackApiError).retryable).toBe(true)
  })

  it('400 VALIDATION_FAILED의 서버 문구를 그대로 싣는다 — 앱 배포 없이 안내를 바꿀 수 있어야 한다', async () => {
    const body = envelope('VALIDATION_FAILED', '후기는 500자까지 보낼 수 있어요.', false)

    const error = await send({}, respond(400, body)).catch((e: unknown) => e)
    expect((error as FeedbackApiError).message).toBe('후기는 500자까지 보낼 수 있어요.')
    expect((error as FeedbackApiError).retryable).toBe(false)
  })

  it.each([
    [401, 'SESSION_EXPIRED'],
    [403, 'SESSION_FORBIDDEN'],
  ])('%s의 code를 보존한다 — 시트가 그 값으로 만료 문구를 고른다', async (status, code) => {
    const error = await send({}, respond(status, envelope(code, '세션이 유효하지 않습니다.', false))).catch(
      (e: unknown) => e,
    )

    expect((error as FeedbackApiError).code).toBe(code)
  })

  it('429는 본문의 retryAfterMs를 싣는다', async () => {
    const body = envelope('RATE_LIMITED', '요청이 많아요.', true, 7000)

    const error = await send({}, respond(429, body, { 'Retry-After': '8' })).catch((e: unknown) => e)
    // 본문이 정본이다 — 헤더는 초 단위로 올림되며 최대 999ms의 오차가 생긴다
    expect((error as FeedbackApiError).retryAfterMs).toBe(7000)
  })

  it('봉투 없는 429는 Retry-After 헤더로 내려간다', async () => {
    const error = await send({}, respond(429, 'nope', { 'Retry-After': '3' })).catch((e: unknown) => e)

    expect((error as FeedbackApiError).retryAfterMs).toBe(3000)
    // 봉투가 없으면 재시도 여부는 상태 코드가 판정한다 (net/retryableStatus)
    expect((error as FeedbackApiError).retryable).toBe(true)
  })

  it('네트워크 예외는 재시도 가능한 실패다 — 같은 키로 다시 보내면 안전하다', async () => {
    const fetchImpl: FetchLike = async () => {
      throw new TypeError('Failed to fetch')
    }

    const error = await send({}, fetchImpl).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(FeedbackApiError)
    expect((error as FeedbackApiError).retryable).toBe(true)
    expect((error as FeedbackApiError).message).toBe('네트워크 오류로 후기를 보내지 못했어요')
  })
})

describe('validateFeedbackInput — 서버 왕복 없이 먼저 거른다', () => {
  it('정상 입력은 null이다', () => {
    expect(validateFeedbackInput(input())).toBeNull()
    expect(validateFeedbackInput(input({ rating: 1, contactEmail: 'a@b.co' }))).toBeNull()
    expect(validateFeedbackInput(input({ rating: 5 }))).toBeNull()
  })

  it.each([
    ['빈 본문', input({ body: '' })],
    ['공백뿐인 본문', input({ body: '   \n ' })],
  ])('%s는 막는다', (_name, value) => {
    expect(validateFeedbackInput(value)).not.toBeNull()
  })

  it('본문 경계는 500자다', () => {
    expect(validateFeedbackInput(input({ body: 'ㄱ'.repeat(FEEDBACK_BODY_MAX) }))).toBeNull()
    expect(validateFeedbackInput(input({ body: 'ㄱ'.repeat(FEEDBACK_BODY_MAX + 1) }))).not.toBeNull()
  })

  it.each([0, 6, 2.5, -1])('별점 %s는 막는다', (rating) => {
    expect(validateFeedbackInput(input({ rating }))).not.toBeNull()
  })

  it.each(['nope', 'a@b', 'a b@c.com', '@b.co', 'a@.co'])('이메일 %s는 막는다', (contactEmail) => {
    expect(validateFeedbackInput(input({ contactEmail }))).not.toBeNull()
  })

  it('이메일 경계는 254자다', () => {
    const tail = '@example.com'
    const ok = 'a'.repeat(FEEDBACK_EMAIL_MAX - tail.length) + tail
    expect(ok).toHaveLength(FEEDBACK_EMAIL_MAX)
    expect(validateFeedbackInput(input({ contactEmail: ok }))).toBeNull()
    expect(validateFeedbackInput(input({ contactEmail: 'a' + ok }))).not.toBeNull()
  })

  it('규격 밖 입력은 전송 자체가 막힌다 — 이 모듈의 계약이기도 하다', async () => {
    const fetchImpl = vi.fn<FetchLike>()

    await expect(send({ input: input({ body: '' }) }, fetchImpl)).rejects.toThrow(FeedbackApiError)
    expect(fetchImpl).not.toHaveBeenCalled()
  })
})
