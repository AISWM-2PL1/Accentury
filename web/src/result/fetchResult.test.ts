import { describe, expect, it, vi } from 'vitest'
import type { FetchLike } from '../progress/fetchTestDefinition'
import { fetchResult, ResultFetchError, type ResultQuery } from './fetchResult'

function query(overrides: Partial<ResultQuery> = {}): ResultQuery {
  return {
    apiBase: 'http://localhost:8080',
    sessionId: 'sess-1',
    sessionToken: 'token-1',
    ...overrides,
  }
}

function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}

/** §3.7 200 본문 대역. 화면이 안 읽는 필드도 실물처럼 실어 둔다 */
function readyBody(overrides: Record<string, unknown> = {}) {
  return {
    status: 'READY',
    scores: { intonation: 78, vocabulary: 60, overall: 72 },
    tier: { code: 'HONORARY', name: '명예주민', rank: 4, of: 5 },
    comment: '억양은 거의 토박이인데 단어에서 들켰습니다.',
    share: {
      imageUrl: 'https://static.accentury.app/tier/honorary.png',
      text: '나는 명예주민! 너도 시도해볼래?',
      webTestUrl: 'https://accentury.app/t?c=kko_share',
    },
    testVersion: 'gn-2026.08.1',
    scoreVersion: 'sv-0.3',
    expiresAt: '2026-08-22T03:00:00Z',
    ...overrides,
  }
}

/** 오류 봉투(§2.3) 대역. 클라이언트가 읽지 않는 필드도 실물처럼 실어 둔다 */
function envelope(code: string, message: string, retryable: boolean) {
  return { code, message, retryable, retryAfterMs: null, correlationId: 'c_test' }
}

describe('요청 형태', () => {
  it('명세 §3.7 그대로 보낸다 — GET, URL, Bearer', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, readyBody()))

    await fetchResult(query(), fetchImpl)

    expect(fetchImpl).toHaveBeenCalledTimes(1)
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('http://localhost:8080/v0/sessions/sess-1/result')
    // GET이라 method를 적지 않는다 — fetch 기본값이다
    expect(init?.method).toBeUndefined()
    expect(init?.headers).toEqual({
      Accept: 'application/json',
      Authorization: 'Bearer token-1',
    })
  })

  it('apiBase 끝의 슬래시가 URL을 겹치게 만들지 않는다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, readyBody()))

    await fetchResult(query({ apiBase: 'http://localhost:8080//' }), fetchImpl)

    expect(fetchImpl.mock.calls[0][0]).toBe('http://localhost:8080/v0/sessions/sess-1/result')
  })

  it('빈 값이 있으면 네트워크를 타기 전에 끊는다', async () => {
    const fetchImpl = vi.fn<FetchLike>()
    vi.spyOn(console, 'error').mockImplementation(() => {})

    await expect(fetchResult(query({ sessionToken: '  ' }), fetchImpl)).rejects.toMatchObject({
      name: 'ResultFetchError',
      retryable: false,
      code: 'CLIENT_MISSING_sessionToken',
    })
    expect(fetchImpl).not.toHaveBeenCalled()
    vi.restoreAllMocks()
  })

  it('가드 실패 문구는 사용자용이다 — 내부 필드 이름이 화면에 나가지 않는다', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})

    const error = await fetchResult(query({ sessionId: '' }), vi.fn<FetchLike>()).catch((e: unknown) => e)

    expect((error as Error).message).not.toContain('sessionId')
    vi.restoreAllMocks()
  })
})

describe('성공 응답', () => {
  it('서버가 준 점수와 등급을 그대로 돌려준다 — 재계산하지 않는다', async () => {
    // 종합 72는 (78 × 2 + 60) / 3 이지만, 클라이언트는 그 식을 모른다.
    // 서버가 다른 값을 줘도 그대로 실어 나른다는 것이 이 테스트의 요지다 (AC 1항).
    const body = readyBody({ scores: { intonation: 78, vocabulary: 60, overall: 99 } })
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, body))

    const result = await fetchResult(query(), fetchImpl)

    expect(result.scores).toEqual({ intonation: 78, vocabulary: 60, overall: 99 })
    expect(result.tier).toEqual({ code: 'HONORARY', name: '명예주민', rank: 4, of: 5 })
    expect(result.testVersion).toBe('gn-2026.08.1')
    expect(result.scoreVersion).toBe('sv-0.3')
  })

  it('공유 자산과 코멘트를 그대로 싣는다 — KAN-30이 쓸 값이다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(200, readyBody()))

    const result = await fetchResult(query(), fetchImpl)

    expect(result.share.webTestUrl).toBe('https://accentury.app/t?c=kko_share')
    expect(result.comment).toContain('단어에서 들켰습니다')
  })
})

describe('오류 분기 — 상태 코드가 아니라 봉투의 code로 가른다', () => {
  it('410 RESULT_EXPIRED는 expired로 표시된다 — 재시도가 아니라 다시 테스트로 보낸다', async () => {
    const body = envelope('RESULT_EXPIRED', '결과 보관 기간(24시간)이 지났습니다. 다시 테스트해 주세요.', false)
    const fetchImpl = vi.fn<FetchLike>(async () => jsonResponse(410, body))

    const error = (await fetchResult(query(), fetchImpl).catch((e: unknown) => e)) as ResultFetchError

    expect(error).toBeInstanceOf(ResultFetchError)
    expect(error.expired).toBe(true)
    expect(error.retryable).toBe(false)
    // 서버 문구를 그대로 쓴다 — 앱 배포 없이 안내를 바꿀 수 있어야 한다
    expect(error.message).toContain('다시 테스트해 주세요')
  })

  it('같은 409라도 NOT_READY와 RETAKE_REQUIRED를 구분한다', async () => {
    const notReady = envelope('RESULT_NOT_READY', '결과를 준비하고 있습니다.', true)
    const retake = envelope('RESULT_RETAKE_REQUIRED', '실패한 문항이 있습니다. 다시 녹음해 주세요.', true)

    const first = (await fetchResult(query(), async () => jsonResponse(409, notReady)).catch(
      (e: unknown) => e,
    )) as ResultFetchError
    const second = (await fetchResult(query(), async () => jsonResponse(409, retake)).catch(
      (e: unknown) => e,
    )) as ResultFetchError

    expect(first.code).toBe('RESULT_NOT_READY')
    expect(second.code).toBe('RESULT_RETAKE_REQUIRED')
    expect(first.expired).toBe(false)
    expect(second.expired).toBe(false)
  })

  it('401 SESSION_EXPIRED는 재시도 불가로 전달된다', async () => {
    const body = envelope('SESSION_EXPIRED', '세션이 만료되었습니다. 테스트를 다시 시작해 주세요.', false)

    const error = (await fetchResult(query(), async () => jsonResponse(401, body)).catch(
      (e: unknown) => e,
    )) as ResultFetchError

    expect(error.code).toBe('SESSION_EXPIRED')
    expect(error.retryable).toBe(false)
    expect(error.expired).toBe(false)
  })

  it('422 RESULT_INCOMPLETE의 문항 목록은 읽지 않는다 — 복구 UX는 KAN-14 몫이다', async () => {
    const body = { ...envelope('RESULT_INCOMPLETE', '아직 완료하지 않은 문항이 있습니다.', false), missingItems: ['v3', 'w5'] }

    const error = (await fetchResult(query(), async () => jsonResponse(422, body)).catch(
      (e: unknown) => e,
    )) as ResultFetchError

    expect(error.code).toBe('RESULT_INCOMPLETE')
    expect(error).not.toHaveProperty('missingItems')
  })

  it('봉투를 못 읽는 오류는 상태 코드 폴백으로 간다', async () => {
    // 프록시가 끼어들어 HTML 오류 페이지를 준 경우
    const fetchImpl: FetchLike = async () =>
      ({
        ok: false,
        status: 502,
        json: async () => {
          throw new SyntaxError('Unexpected token <')
        },
      }) as unknown as Response

    const error = (await fetchResult(query(), fetchImpl).catch((e: unknown) => e)) as ResultFetchError

    expect(error.code).toBeNull()
    expect(error.message).toContain('502')
    expect(error.retryable).toBe(true)
  })

  it('fetch 거부는 재시도 가능한 네트워크 오류다', async () => {
    const fetchImpl: FetchLike = async () => {
      throw new TypeError('Failed to fetch')
    }

    const error = (await fetchResult(query(), fetchImpl).catch((e: unknown) => e)) as ResultFetchError

    expect(error.retryable).toBe(true)
    expect(error.code).toBeNull()
  })
})

describe('200인데 본문이 계약과 다른 경우', () => {
  it('JSON이 아니면 실패로 끊는다', async () => {
    const fetchImpl: FetchLike = async () =>
      ({
        ok: true,
        status: 200,
        json: async () => {
          throw new SyntaxError('Unexpected token <')
        },
      }) as unknown as Response

    await expect(fetchResult(query(), fetchImpl)).rejects.toMatchObject({ name: 'ResultFetchError' })
  })

  it('점수가 빠진 응답을 성공으로 통과시키지 않는다 — 점수 자리에 undefined를 그리면 안 된다', async () => {
    const broken = { ...readyBody(), scores: { intonation: 78, vocabulary: 60 } }

    await expect(fetchResult(query(), async () => jsonResponse(200, broken))).rejects.toMatchObject({
      message: '결과 응답의 형태가 계약과 다릅니다',
      retryable: false,
    })
  })

  it('등급이 빠진 응답도 막는다', async () => {
    const broken = { ...readyBody(), tier: { rank: 4, of: 5 } }

    await expect(fetchResult(query(), async () => jsonResponse(200, broken))).rejects.toMatchObject({
      name: 'ResultFetchError',
    })
  })

  it('점수가 숫자가 아니면 막는다 — 문자열 "78"은 계산도 너비 계산도 깨뜨린다', async () => {
    const broken = { ...readyBody(), scores: { intonation: '78', vocabulary: 60, overall: 72 } }

    await expect(fetchResult(query(), async () => jsonResponse(200, broken))).rejects.toMatchObject({
      name: 'ResultFetchError',
    })
  })

  /*
   * 소비 필드 전수 방어. 하나씩 빼 보는 이유는 "점수만 보면 된다"는 초기 판단이 공유 결선에서
   * 실제로 깨졌기 때문이다 — share가 없는 응답이 통과하면 공유 버튼에서 TypeError가 났다.
   * 새 필드를 화면이 읽기 시작하면 이 표에 한 줄을 더하는 것이 검증을 붙이는 절차가 된다.
   */
  const CONSUMED: ReadonlyArray<readonly [string, Record<string, unknown>]> = [
    ['tier.rank 누락 — "5개 등급 중 ?번째"가 깨진다', { tier: { code: 'HONORARY', name: '명예주민', of: 5 } }],
    ['tier.of 누락', { tier: { code: 'HONORARY', name: '명예주민', rank: 4 } }],
    ['comment 누락 — 등급 진단 문단이 빈다', { comment: undefined }],
    ['share 누락 — 공유 버튼이 구조 분해에서 터진다', { share: undefined }],
    ['share.text 누락', { share: { imageUrl: 'https://img/x.png', webTestUrl: 'https://accentury.app/t' } }],
    ['share.webTestUrl 누락', { share: { imageUrl: 'https://img/x.png', text: '나는 명예주민!' } }],
    [
      'share.imageUrl 누락 — 공유 카드가 이미지 없이 나간다 (KAN-30)',
      { share: { text: '나는 명예주민!', webTestUrl: 'https://accentury.app/t' } },
    ],
    ['testVersion 누락 — 버전 꼬리표가 undefined가 된다', { testVersion: undefined }],
    ['scoreVersion 누락', { scoreVersion: undefined }],
  ]

  for (const [label, patch] of CONSUMED) {
    it(`소비 필드가 빠진 200을 성공으로 통과시키지 않는다: ${label}`, async () => {
      const broken: Record<string, unknown> = { ...readyBody(), ...patch }
      for (const [key, value] of Object.entries(patch)) {
        if (value === undefined) delete broken[key]
      }

      await expect(fetchResult(query(), async () => jsonResponse(200, broken))).rejects.toMatchObject({
        name: 'ResultFetchError',
        retryable: false,
      })
    })
  }

  it('아무도 읽지 않는 필드는 없어도 통과한다 — 계약이 늘 때 고칠 곳을 늘리지 않는다', async () => {
    // status는 READY 하나뿐이라 분기가 없고, expiresAt은 만료 판정이 서버의 410이라 안 읽는다.
    // share.imageUrl은 이 목록에서 빠졌다 — KAN-30 공유 카드가 읽기 시작하면서 소비 필드가 됐다.
    const body: Record<string, unknown> = { ...readyBody() }
    delete body.status
    delete body.expiresAt

    const result = await fetchResult(query(), async () => jsonResponse(200, body))

    expect(result.tier.name).toBe('명예주민')
  })
})
