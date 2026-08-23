import { describe, expect, it, vi } from 'vitest'
import type { FetchLike } from '../progress/fetchTestDefinition'
import {
  completeSession,
  RESULT_INCOMPLETE,
  RESULT_RETAKE_REQUIRED,
  type CompleteRequest,
} from './completeSession'
import type { AnalysisApiError } from './errorEnvelope'

function request(overrides: Partial<CompleteRequest> = {}): CompleteRequest {
  return {
    apiBase: 'http://localhost:8080',
    sessionId: 'sess-1',
    sessionToken: 'token-1',
    idempotencyKey: 'key-1',
    ...overrides,
  }
}

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name: string) => headers[name] ?? null },
    json: async () => body,
  } as Response
}

/** 문항 목록 확장이 붙은 오류 봉투 (§2.3, §3.6) */
function itemsEnvelope(code: string, message: string, retryable: boolean, fields: Record<string, unknown>) {
  return { code, message, retryable, retryAfterMs: null, correlationId: 'c_test', ...fields }
}

describe('요청 형태', () => {
  it('명세 §3.6 그대로 보낸다 — POST, Bearer, Idempotency-Key', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, { status: 'READY' }))

    await completeSession(request(), fetchImpl)

    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('http://localhost:8080/v0/sessions/sess-1/complete')
    expect(init?.method).toBe('POST')
    expect(init?.headers).toEqual({
      Accept: 'application/json',
      Authorization: 'Bearer token-1',
      'Idempotency-Key': 'key-1',
    })
  })

  it('본문이 없다 — 완료는 보낼 값이 없는 요청이다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, { status: 'READY' }))

    await completeSession(request(), fetchImpl)

    expect(fetchImpl.mock.calls[0][1]?.body).toBeUndefined()
  })

  it('키를 주지 않으면 만들어 쓴다 — 계약상 필수 헤더라 비울 수 없다 (§2.2)', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, { status: 'READY' }))

    await completeSession(request({ idempotencyKey: undefined }), fetchImpl)

    const headers = fetchImpl.mock.calls[0][1]?.headers as Record<string, string>
    expect(headers['Idempotency-Key']).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    )
  })

  it('같은 키를 넘기면 그대로 다시 쓴다 — 폴링 20회가 로그에서 한 줄기로 남는다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, { status: 'PROCESSING' }))

    await completeSession(request({ idempotencyKey: 'same' }), fetchImpl)
    await completeSession(request({ idempotencyKey: 'same' }), fetchImpl)

    const keys = fetchImpl.mock.calls.map(
      ([, init]) => (init?.headers as Record<string, string>)['Idempotency-Key'],
    )
    expect(keys).toEqual(['same', 'same'])
  })

  it('세션 ID를 URL에 넣기 전에 인코딩한다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, { status: 'READY' }))

    await completeSession(request({ sessionId: 'a/b' }), fetchImpl)

    expect(fetchImpl.mock.calls[0][0]).toBe('http://localhost:8080/v0/sessions/a%2Fb/complete')
  })
})

describe('빈 값 가드', () => {
  it.each(['sessionId', 'sessionToken', 'idempotencyKey'] as const)('%s가 비면 요청하지 않는다', async (field) => {
    const fetchImpl = vi.fn<FetchLike>()

    await expect(completeSession(request({ [field]: '  ' }), fetchImpl)).rejects.toMatchObject({
      code: `CLIENT_MISSING_${field}`,
      retryable: false,
    })
    expect(fetchImpl).not.toHaveBeenCalled()
  })
})

describe('200 응답', () => {
  it('READY면 결과가 확정된 것이다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, { status: 'READY' }))

    await expect(completeSession(request(), fetchImpl)).resolves.toEqual({ status: 'READY' })
  })

  it('PROCESSING이면 대기 문항과 다음 간격을 싣는다', async () => {
    const body = { status: 'PROCESSING', pendingItems: ['v2', 'v3'], pollAfterMs: 1200 }
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, body))

    await expect(completeSession(request(), fetchImpl)).resolves.toEqual({
      status: 'PROCESSING',
      pendingItems: ['v2', 'v3'],
      pollAfterMs: 1200,
    })
  })

  it('PROCESSING인데 pollAfterMs가 없으면 null — 스케줄러의 백오프 폴백이 받는다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, { status: 'PROCESSING' }))

    await expect(completeSession(request(), fetchImpl)).resolves.toEqual({
      status: 'PROCESSING',
      pendingItems: [],
      pollAfterMs: null,
    })
  })

  it('READY 응답에 점수가 없다 — 대기 화면이 점수를 그릴 값 자체가 없다 (KAN-12)', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, { status: 'READY' }))

    const result = await completeSession(request(), fetchImpl)

    expect(Object.keys(result)).toEqual(['status'])
  })

  it.each([
    ['모르는 status', { status: 'ALMOST' }],
    ['status 없음', { pendingItems: [] }],
    ['본문이 객체가 아님', 'READY'],
  ])('%s → retryable=false로 끊는다 (READY를 놓치면 영원히 대기한다)', async (_label, body) => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, body))

    await expect(completeSession(request(), fetchImpl)).rejects.toMatchObject({ retryable: false })
  })
})

describe('실패 응답 — 분기는 봉투의 code로 한다', () => {
  it('409 RESULT_RETAKE_REQUIRED는 재녹음 대상 문항을 함께 싣는다', async () => {
    const body = itemsEnvelope(
      RESULT_RETAKE_REQUIRED,
      '실패한 문항이 있습니다. 다시 녹음해 주세요.',
      true,
      { retakeItems: ['v3', 'v4'] },
    )
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(409, body))

    const error = (await completeSession(request(), fetchImpl).catch((e: unknown) => e)) as AnalysisApiError

    expect(error.code).toBe(RESULT_RETAKE_REQUIRED)
    expect(error.retakeItems).toEqual(['v3', 'v4'])
    expect(error.missingItems).toEqual([])
  })

  it('422 RESULT_INCOMPLETE는 미제출 문항을 싣는다', async () => {
    const body = itemsEnvelope(RESULT_INCOMPLETE, '아직 완료하지 않은 문항이 있습니다.', false, {
      missingItems: ['v5'],
    })
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(422, body))

    const error = (await completeSession(request(), fetchImpl).catch((e: unknown) => e)) as AnalysisApiError

    expect(error.code).toBe(RESULT_INCOMPLETE)
    expect(error.missingItems).toEqual(['v5'])
    expect(error.retryable).toBe(false)
  })

  it('같은 409라도 코드가 다르면 다른 실패다 — 상태 코드로 갈랐으면 한 문구가 됐을 자리', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () =>
      jsonResponse(409, itemsEnvelope('SESSION_COMPLETED', '이미 완료된 테스트입니다.', false, {})),
    )

    const error = (await completeSession(request(), fetchImpl).catch((e: unknown) => e)) as AnalysisApiError

    expect(error.code).toBe('SESSION_COMPLETED')
    expect(error.retakeItems).toEqual([])
  })

  it('429는 retryAfterMs를 싣는다 (요구 6항)', async () => {
    const body = itemsEnvelope('RATE_LIMITED', '요청이 너무 많습니다.', true, { retryAfterMs: 4000 })
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(429, body))

    const error = (await completeSession(request(), fetchImpl).catch((e: unknown) => e)) as AnalysisApiError

    expect(error.retryAfterMs).toBe(4000)
    expect(error.rateLimited).toBe(true)
  })

  it('문항 목록이 문자열 배열이 아니면 빈 배열로 접는다', async () => {
    const body = itemsEnvelope(RESULT_RETAKE_REQUIRED, '실패한 문항이 있습니다.', true, {
      retakeItems: [1, 2],
    })
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(409, body))

    const error = (await completeSession(request(), fetchImpl).catch((e: unknown) => e)) as AnalysisApiError

    expect(error.retakeItems).toEqual([])
  })

  it('봉투를 못 읽은 HTTP 오류는 상태 코드 폴백으로 간다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(503, 'unavailable'))

    await expect(completeSession(request(), fetchImpl)).rejects.toMatchObject({
      code: null,
      retryable: true,
      message: '테스트를 마치지 못했습니다 (HTTP 503)',
    })
  })

  it('네트워크 실패는 재시도 가능이다 — 완료는 자연 멱등이라 다시 보내도 안전하다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => {
      throw new TypeError('Failed to fetch')
    })

    await expect(completeSession(request(), fetchImpl)).rejects.toMatchObject({ retryable: true })
  })
})
