import { describe, expect, it, vi } from 'vitest'
import type { FetchLike } from '../progress/fetchTestDefinition'
import { AnalysisApiError } from './errorEnvelope'
import { fetchAnalysisStatuses, type AnalysisStatusQuery } from './fetchAnalysisStatuses'

function query(overrides: Partial<AnalysisStatusQuery> = {}): AnalysisStatusQuery {
  return { apiBase: 'http://localhost:8080', sessionId: 'sess-1', sessionToken: 'token-1', ...overrides }
}

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name: string) => headers[name] ?? null },
    json: async () => body,
  } as Response
}

/** §3.4 200 본문 대역. 음성 5문항이 seq 순서로 전부 실린다 */
function statusesBody(overrides: Record<string, unknown> = {}) {
  return {
    pollAfterMs: 800,
    items: [
      { itemId: 'v1', status: 'COMPLETED', quality: 'OK' },
      { itemId: 'v2', status: 'PROCESSING' },
      { itemId: 'v3', status: 'RETRYABLE_FAILED', error: { code: 'AUDIO_TOO_QUIET', retryable: true } },
      { itemId: 'v4', status: 'FAILED', error: { code: 'INTERNAL_ERROR', retryable: false } },
      { itemId: 'v5', status: 'NOT_SUBMITTED' },
    ],
    ...overrides,
  }
}

/** 오류 봉투(§2.3) 대역 */
function envelope(code: string, message: string, retryable: boolean, retryAfterMs: number | null = null) {
  return { code, message, retryable, retryAfterMs, correlationId: 'c_test' }
}

describe('요청 형태', () => {
  it('명세 §3.4 그대로 보낸다 — GET, 일괄 조회 URL, Bearer', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, statusesBody()))

    await fetchAnalysisStatuses(query(), fetchImpl)

    expect(fetchImpl).toHaveBeenCalledTimes(1)
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('http://localhost:8080/v0/sessions/sess-1/analyses')
    expect(init?.method).toBeUndefined()
    expect(init?.headers).toEqual({ Accept: 'application/json', Authorization: 'Bearer token-1' })
  })

  it('문항 수와 무관하게 요청은 1회다 (요구 7항)', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, statusesBody()))

    const result = await fetchAnalysisStatuses(query(), fetchImpl)

    expect(result.items).toHaveLength(5)
    expect(fetchImpl).toHaveBeenCalledTimes(1)
  })

  it('apiBase 끝의 슬래시가 URL을 겹치게 만들지 않는다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, statusesBody()))

    await fetchAnalysisStatuses(query({ apiBase: 'http://localhost:8080/' }), fetchImpl)

    expect(fetchImpl.mock.calls[0][0]).toBe('http://localhost:8080/v0/sessions/sess-1/analyses')
  })

  it('세션 ID를 URL에 넣기 전에 인코딩한다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, statusesBody()))

    await fetchAnalysisStatuses(query({ sessionId: 'a/b?c' }), fetchImpl)

    expect(fetchImpl.mock.calls[0][0]).toBe('http://localhost:8080/v0/sessions/a%2Fb%3Fc/analyses')
  })
})

describe('빈 값 가드 — 네트워크를 타기 전에 끊는다', () => {
  it.each(['sessionId', 'sessionToken'] as const)('%s가 비면 요청하지 않는다', async (field) => {
    const fetchImpl = vi.fn<FetchLike>()

    await expect(fetchAnalysisStatuses(query({ [field]: '  ' }), fetchImpl)).rejects.toMatchObject({
      code: `CLIENT_MISSING_${field}`,
      // 폴링이 60초 동안 같은 실패를 두드리지 않도록 재시도 불가로 준다
      retryable: false,
    })
    expect(fetchImpl).not.toHaveBeenCalled()
  })
})

describe('성공 응답 파싱', () => {
  it('다섯 상태를 그대로 싣고 quality·error를 붙인다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, statusesBody()))

    const result = await fetchAnalysisStatuses(query(), fetchImpl)

    expect(result.pollAfterMs).toBe(800)
    expect(result.items).toEqual([
      { itemId: 'v1', status: 'COMPLETED', quality: 'OK', error: null },
      { itemId: 'v2', status: 'PROCESSING', quality: null, error: null },
      {
        itemId: 'v3',
        status: 'RETRYABLE_FAILED',
        quality: null,
        error: { code: 'AUDIO_TOO_QUIET', retryable: true },
      },
      {
        itemId: 'v4',
        status: 'FAILED',
        quality: null,
        error: { code: 'INTERNAL_ERROR', retryable: false },
      },
      { itemId: 'v5', status: 'NOT_SUBMITTED', quality: null, error: null },
    ])
  })

  it('서버 계약대로 seq 순서를 그대로 둔다 — 클라이언트가 다시 정렬하지 않는다', async () => {
    const body = statusesBody({
      items: [
        { itemId: 'v5', status: 'PROCESSING' },
        { itemId: 'v1', status: 'COMPLETED', quality: 'OK' },
      ],
    })
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, body))

    const result = await fetchAnalysisStatuses(query(), fetchImpl)

    expect(result.items.map((item) => item.itemId)).toEqual(['v5', 'v1'])
  })

  it('서버가 나중에 더한 모르는 필드는 무시한다 (§2.3)', async () => {
    const body = statusesBody({ nextThing: 42 })
    body.items[0] = { ...body.items[0], somethingNew: 'x' } as never
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, body))

    await expect(fetchAnalysisStatuses(query(), fetchImpl)).resolves.toMatchObject({ pollAfterMs: 800 })
  })

  it('error 형태가 이상하면 응답 전체를 버리지 않고 null로 접는다 — status만으로 그릴 수 있다', async () => {
    const body = statusesBody({
      items: [{ itemId: 'v1', status: 'RETRYABLE_FAILED', error: { code: 42 } }],
    })
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, body))

    const result = await fetchAnalysisStatuses(query(), fetchImpl)

    expect(result.items[0]).toEqual({
      itemId: 'v1',
      status: 'RETRYABLE_FAILED',
      quality: null,
      error: null,
    })
  })
})

describe('계약과 다른 200 — 재시도로 고쳐지지 않는다', () => {
  it.each([
    ['본문이 객체가 아님', 'not json'],
    ['pollAfterMs 없음', { items: [] }],
    ['items가 배열이 아님', { pollAfterMs: 800, items: {} }],
    ['itemId 없음', { pollAfterMs: 800, items: [{ status: 'COMPLETED' }] }],
    ['모르는 status', { pollAfterMs: 800, items: [{ itemId: 'v1', status: 'ALMOST_DONE' }] }],
    ['quality가 문자열이 아님', { pollAfterMs: 800, items: [{ itemId: 'v1', status: 'COMPLETED', quality: 1 }] }],
  ])('%s → retryable=false로 끊는다', async (_label, body) => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, body))

    const error = await fetchAnalysisStatuses(query(), fetchImpl).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(AnalysisApiError)
    expect((error as AnalysisApiError).retryable).toBe(false)
  })

  it('모르는 status를 통과시키면 그 문항이 영영 대기로 남는다 — 그래서 집합까지 본다', async () => {
    const body = { pollAfterMs: 800, items: [{ itemId: 'v1', status: 'COMPLETED_MAYBE' }] }
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, body))

    await expect(fetchAnalysisStatuses(query(), fetchImpl)).rejects.toThrow(/형태가 계약과 다릅니다/)
  })

  it('JSON이 아니면 재시도 가능으로 본다 — 일시적 프록시 응답일 수 있다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => ({
      ok: true,
      status: 200,
      headers: { get: () => null },
      json: async () => {
        throw new Error('not json')
      },
    }) as unknown as Response)

    await expect(fetchAnalysisStatuses(query(), fetchImpl)).rejects.toMatchObject({ retryable: true })
  })
})

describe('실패 응답 — 분기는 봉투의 code로 한다', () => {
  it('401 SESSION_EXPIRED를 봉투 그대로 올린다', async () => {
    const body = envelope('SESSION_EXPIRED', '세션이 만료되었습니다. 테스트를 다시 시작해 주세요.', false)
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(401, body))

    await expect(fetchAnalysisStatuses(query(), fetchImpl)).rejects.toMatchObject({
      code: 'SESSION_EXPIRED',
      retryable: false,
      message: '세션이 만료되었습니다. 테스트를 다시 시작해 주세요.',
    })
  })

  it('403 SESSION_FORBIDDEN도 같은 경로로 올라온다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () =>
      jsonResponse(403, envelope('SESSION_FORBIDDEN', '이 세션에 접근할 수 없습니다.', false)),
    )

    await expect(fetchAnalysisStatuses(query(), fetchImpl)).rejects.toMatchObject({
      code: 'SESSION_FORBIDDEN',
    })
  })

  it('429는 본문의 retryAfterMs를 싣는다 (요구 6항)', async () => {
    const body = envelope('RATE_LIMITED', '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.', true, 3000)
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(429, body, { 'Retry-After': '3' }))

    const error = (await fetchAnalysisStatuses(query(), fetchImpl).catch((e: unknown) => e)) as AnalysisApiError

    expect(error.retryAfterMs).toBe(3000)
    expect(error.rateLimited).toBe(true)
  })

  it('본문이 헤더보다 정확하다 — 헤더는 초 단위 올림이라 최대 999ms가 어긋난다', async () => {
    const body = envelope('RATE_LIMITED', '요청이 너무 많습니다.', true, 2100)
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(429, body, { 'Retry-After': '3' }))

    const error = (await fetchAnalysisStatuses(query(), fetchImpl).catch((e: unknown) => e)) as AnalysisApiError

    expect(error.retryAfterMs).toBe(2100)
  })

  it('봉투 없는 429도 헤더에서 대기 시간을 건진다 — 프록시가 우리 본문 없이 돌려줄 수 있다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(429, 'Too Many Requests', { 'Retry-After': '5' }))

    const error = (await fetchAnalysisStatuses(query(), fetchImpl).catch((e: unknown) => e)) as AnalysisApiError

    expect(error.code).toBeNull()
    expect(error.retryAfterMs).toBe(5000)
  })

  it('봉투를 못 읽은 HTTP 오류는 상태 코드 폴백으로 간다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(500, 'boom'))

    await expect(fetchAnalysisStatuses(query(), fetchImpl)).rejects.toMatchObject({
      code: null,
      retryable: true,
      message: '분석 상태를 확인하지 못했습니다 (HTTP 500)',
    })
  })

  it('네트워크 실패는 재시도 가능이다 — 부작용 없는 GET이라 다시 두드려도 무해하다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => {
      throw new TypeError('Failed to fetch')
    })

    await expect(fetchAnalysisStatuses(query(), fetchImpl)).rejects.toMatchObject({ retryable: true })
  })
})
