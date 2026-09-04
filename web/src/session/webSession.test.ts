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
  isWebSessionStorageAvailable,
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

  /*
   * 목소리 점검이 잰 중심 (KAN-31 4단계). 서버가 주는 값이 아니라 웹이 얹는 값이라
   * 저장소를 왕복해도 남아야 문항 화면의 곡선이 같은 축을 쓴다.
   */
  it('목소리 점검이 잰 중심을 세션과 함께 나른다', () => {
    saveWebSession({ ...SESSION, userCurveCenterHz: 187.5 })

    expect(loadWebSession()?.userCurveCenterHz).toBe(187.5)
  })

  it('중심이 없거나 성립하지 않으면 없는 것으로 읽는다 — 곡선이 폴백으로 내려간다', () => {
    saveWebSession(SESSION)
    expect(loadWebSession()?.userCurveCenterHz).toBeUndefined()

    // 0이나 NaN을 그대로 내려보내면 y축 중심이 성립하지 않아 곡선이 통째로 사라진다
    sessionStorage.setItem(
      'accentury.webSession',
      JSON.stringify({ ...SESSION, userCurveCenterHz: 0 }),
    )
    expect(loadWebSession()?.userCurveCenterHz).toBeUndefined()
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

/*
 * 저장소 가용성 판정 (KAN-31). 전역을 통째로 갈아끼우지 않고 프로토타입의 메서드만 바꾼다 —
 * 판정이 보는 것은 저장소의 **동작**이고, 실물 `sessionStorage` 인스턴스를 그대로 두어야
 * "메서드는 있는데 결과가 다르다"는 실제 상황과 같은 모양이 된다.
 *
 * 전역 `Storage.prototype`이 아니라 인스턴스의 프로토타입을 잡는다 — jsdom에서 둘은 같은
 * 객체가 아니라, 전역 쪽에 스파이를 걸면 실제 호출이 원본으로 그대로 지나간다 (2026-08-26 실증).
 */
const storageProto = Object.getPrototypeOf(sessionStorage) as Storage

describe('저장소 가용성 판정 — 토큰이 리로드를 넘을 수 있는가', () => {
  it('평범한 브라우저에서는 쓸 수 있다고 답하고 시험값을 남기지 않는다', () => {
    expect(isWebSessionStorageAvailable()).toBe(true)
    // 잰 자리에서 지운다 — 진단용 값이 탭 수명 내내 남을 이유가 없다
    expect(sessionStorage.getItem('accentury.webSession.probe')).toBeNull()
  })

  it('쓰기가 던지면 쓸 수 없다 (사생활 보호 모드·쿠키 전면 차단)', () => {
    const setItem = vi.spyOn(storageProto, 'setItem').mockImplementation(() => {
      throw new Error('접근이 거부됐다')
    })

    try {
      expect(isWebSessionStorageAvailable()).toBe(false)
    } finally {
      setItem.mockRestore()
    }
  })

  /*
   * 던지지 않고 조용히 버리는 저장소가 이 판정의 존재 이유다. 예외만 보면 통과시키게 되고,
   * 그러면 세션은 만들어졌는데 다음 문서에 토큰이 없는 상태로 사용자를 문항 화면에 보낸다.
   */
  it('쓰기가 조용히 버려져도(되읽기가 비었으면) 쓸 수 없다', () => {
    const setItem = vi.spyOn(storageProto, 'setItem').mockImplementation(() => {})
    const getItem = vi.spyOn(storageProto, 'getItem').mockReturnValue(null)

    try {
      expect(isWebSessionStorageAvailable()).toBe(false)
    } finally {
      getItem.mockRestore()
      setItem.mockRestore()
    }
  })
})
