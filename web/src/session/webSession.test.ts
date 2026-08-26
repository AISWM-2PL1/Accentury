/**
 * 웹 단독 세션 (KAN-31 Stage 1). 확인하는 것은 둘이다 — **서버 계약대로 요청이 나가는가**와
 * **받은 토큰이 리로드를 견디는가**.
 *
 * 저장소는 대역을 쓰지 않고 jsdom의 실물 `sessionStorage`를 쓴다. 이 모듈이 확인하려는 것
 * 자체가 "그 저장소에 남는가"라, 대역으로 갈아치우면 직렬화·키 같은 실제 계약이 검사에서
 * 빠진다. 접근이 던지는 경우(사생활 보호 모드)만 전역을 갈아끼워 재현한다.
 */

import { afterEach, describe, expect, it, vi } from 'vitest'
import type { FetchLike } from '../progress/fetchTestDefinition'
import {
  clearWebSession,
  createWebSession,
  getWebSessionToken,
  loadWebSession,
  saveWebSession,
  WebSessionError,
  type WebSession,
} from './webSession'

const API_BASE = 'http://localhost:8080'

const SESSION: WebSession = {
  sessionId: 's_1',
  sessionToken: 'st_1',
  testVersion: 'gn-2026.08.1',
  expiresAt: '2026-08-26T03:30:00Z',
}

/** §3.1 201 본문. `scoreVersion`도 실어 둔다 — 웹이 읽지 않고 버리는 것까지가 계약이다 */
const CREATED_BODY = { ...SESSION, scoreVersion: 'sv-0.3' }

/** 계약대로 201을 돌려주는 fetch 대역 */
function createdFetch(body: unknown = CREATED_BODY): ReturnType<typeof vi.fn<FetchLike>> {
  return vi.fn<FetchLike>(
    async () =>
      ({
        ok: true,
        status: 201,
        headers: { get: () => null },
        json: async () => body,
      }) as unknown as Response,
  )
}

/** 실패 응답 대역. 봉투를 주지 않으려면 body에 undefined를 넘긴다 */
function failingFetch(status: number, body?: unknown): FetchLike {
  return async () =>
    ({
      ok: false,
      status,
      headers: { get: () => null },
      json: async () => {
        if (body === undefined) throw new SyntaxError('본문이 JSON이 아니다')
        return body
      },
    }) as unknown as Response
}

/** 마지막 요청의 헤더. 대역이 받은 init에서 꺼낸다 */
function headersOf(fetchImpl: ReturnType<typeof vi.fn<FetchLike>>): Record<string, string> {
  return (fetchImpl.mock.calls.at(-1)?.[1]?.headers ?? {}) as Record<string, string>
}

/** 마지막 요청의 본문 */
function bodyOf(fetchImpl: ReturnType<typeof vi.fn<FetchLike>>): Record<string, unknown> {
  return JSON.parse(fetchImpl.mock.calls.at(-1)?.[1]?.body as string)
}

afterEach(() => {
  vi.unstubAllGlobals()
  clearWebSession()
})

describe('createWebSession — 요청 형태 (§3.1)', () => {
  it('인증 없이 POST /v0/sessions로 나가고 플랫폼은 WEB이다', async () => {
    const fetchImpl = createdFetch()

    await createWebSession(API_BASE, {}, fetchImpl)

    expect(fetchImpl.mock.calls[0][0]).toBe(`${API_BASE}/v0/sessions`)
    expect(fetchImpl.mock.calls[0][1]?.method).toBe('POST')
    expect(headersOf(fetchImpl)['Content-Type']).toBe('application/json')
    // 서버 로그와 이 요청을 잇는 값 (녹음 업로드와 같은 규칙)
    expect(headersOf(fetchImpl)['X-Correlation-Id']).toBeTruthy()

    const body = bodyOf(fetchImpl)
    expect(body.client).toMatchObject({ platform: 'WEB' })
    expect((body.client as { appVersion: string }).appVersion.length).toBeGreaterThan(0)
    expect((body.client as { appVersion: string }).appVersion.length).toBeLessThanOrEqual(32)
  })

  it('공유 링크의 유입 코드를 그대로 싣는다', async () => {
    const fetchImpl = createdFetch()

    await createWebSession(API_BASE, { campaignToken: 'kko_a1b2' }, fetchImpl)

    expect(bodyOf(fetchImpl).campaignToken).toBe('kko_a1b2')
  })

  it('형태가 어긋난 유입 코드는 400을 부르는 대신 필드째 뺀다', async () => {
    const fetchImpl = createdFetch()

    // 공백은 서버 @Pattern이 받지 않는다 — 여기서 실어 보내면 응시 자체가 400으로 막힌다
    await createWebSession(API_BASE, { campaignToken: 'kko share!' }, fetchImpl)

    expect(bodyOf(fetchImpl)).not.toHaveProperty('campaignToken')
  })

  it('재응시면 이전 세션 토큰을 Bearer로 함께 보낸다 (KAN-107)', async () => {
    const fetchImpl = createdFetch()

    await createWebSession(API_BASE, { previousToken: 'st_old' }, fetchImpl)

    expect(headersOf(fetchImpl).Authorization).toBe('Bearer st_old')
  })

  it('이전 세션이 없으면 Authorization 헤더 자체가 없다 — 인증 불필요 엔드포인트다 (§2.1)', async () => {
    const fetchImpl = createdFetch()

    await createWebSession(API_BASE, { previousToken: '' }, fetchImpl)

    expect(headersOf(fetchImpl)).not.toHaveProperty('Authorization')
  })
})

describe('createWebSession — 응답 해석', () => {
  it('201이면 세션을 돌려준다 — scoreVersion은 읽지 않는다', async () => {
    const session = await createWebSession(API_BASE, {}, createdFetch())

    expect(session).toEqual(SESSION)
  })

  it('201인데 토큰이 없으면 실패로 본다 — 빈 토큰으로 문항 화면에 들어가지 않는다', async () => {
    const fetchImpl = createdFetch({ ...CREATED_BODY, sessionToken: '' })

    await expect(createWebSession(API_BASE, {}, fetchImpl)).rejects.toBeInstanceOf(WebSessionError)
  })

  it('429면 봉투의 대기 시간을 실은 오류를 던진다 (§2.5)', async () => {
    const rateLimited = failingFetch(429, {
      code: 'RATE_LIMITED',
      message: '요청이 많아요. 잠시 후 다시 시도해 주세요.',
      retryable: true,
      retryAfterMs: 30_000,
    })

    const error = await createWebSession(API_BASE, {}, rateLimited).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(WebSessionError)
    expect((error as WebSessionError).code).toBe('RATE_LIMITED')
    expect((error as WebSessionError).retryAfterMs).toBe(30_000)
    expect((error as WebSessionError).message).toBe('요청이 많아요. 잠시 후 다시 시도해 주세요.')
  })

  it('봉투 없는 실패는 상태 코드로 재시도 여부를 판단한다', async () => {
    const server = await createWebSession(API_BASE, {}, failingFetch(503)).catch((e: unknown) => e)
    const client = await createWebSession(API_BASE, {}, failingFetch(400)).catch((e: unknown) => e)

    expect((server as WebSessionError).retryable).toBe(true)
    expect((client as WebSessionError).retryable).toBe(false)
  })

  it('응답이 아예 오지 않으면 재시도 가능한 실패다', async () => {
    const offline: FetchLike = async () => {
      throw new TypeError('Failed to fetch')
    }

    const error = await createWebSession(API_BASE, {}, offline).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(WebSessionError)
    expect((error as WebSessionError).retryable).toBe(true)
  })
})

describe('세션 저장 — 탭 안에서만, 리로드는 견딘다', () => {
  it('저장한 세션을 그대로 다시 읽는다 (화면 전환이 문서를 다시 로드한다)', () => {
    saveWebSession(SESSION)

    expect(loadWebSession()).toEqual(SESSION)
    expect(getWebSessionToken()).toBe('st_1')
  })

  it('지우면 토큰이 빈 문자열이 된다', () => {
    saveWebSession(SESSION)
    clearWebSession()

    expect(loadWebSession()).toBeNull()
    expect(getWebSessionToken()).toBe('')
  })

  it('저장된 값이 깨져 있으면 세션이 없는 것과 같이 다룬다', () => {
    sessionStorage.setItem('accentury.webSession', '{ 이건 JSON이 아니다')

    expect(loadWebSession()).toBeNull()
  })

  it('저장소 접근이 던져도(사생활 보호 모드) 응시를 막지 않는다', () => {
    vi.stubGlobal('sessionStorage', {
      getItem: () => {
        throw new Error('접근이 거부됐다')
      },
      setItem: () => {
        throw new Error('접근이 거부됐다')
      },
      removeItem: () => {
        throw new Error('접근이 거부됐다')
      },
    })

    expect(() => saveWebSession(SESSION)).not.toThrow()
    expect(() => clearWebSession()).not.toThrow()
    expect(getWebSessionToken()).toBe('')
  })
})
