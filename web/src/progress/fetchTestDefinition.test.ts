import { describe, expect, it, vi } from 'vitest'
import { fetchTestDefinition, type FetchLike } from './fetchTestDefinition'
import type { TestDefinition } from './testDefinition'

const TEST_VERSION = 'gn-2026.08.1'

function definition(): TestDefinition {
  return {
    testVersion: TEST_VERSION,
    scoreVersion: 'sv-0.3',
    dialect: 'GYEONGNAM',
    estimatedDurationSec: 180,
    items: [
      {
        itemId: 'item-1',
        seq: 1,
        type: 'VOICE',
        prompt: '음성 문항 1',
        maxDurationMs: 10_000,
        guideF0: { unit: 'semitone', frameIntervalMs: 10, values: [0, 1] },
      },
    ],
  }
}

/**
 * Response 대역. 실물 Response를 만들지 않는 이유: 이 함수가 실제로 쓰는 건
 * ok·status·json 셋뿐이고, 그 셋만 흉내내면 런타임 환경(jsdom의 fetch 미구현)에 기대지 않는다.
 */
function response(init: { ok?: boolean; status?: number; json?: () => Promise<unknown> }): Response {
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    json: init.json ?? (async () => definition()),
  } as Response
}

describe('fetchTestDefinition — 정상 응답', () => {
  it('버전 경로로 요청하고 정의를 그대로 돌려준다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => response({}))

    const result = await fetchTestDefinition('http://localhost:8080', TEST_VERSION, fetchImpl)

    expect(result).toEqual(definition())
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(fetchImpl.mock.calls[0][0]).toBe(`http://localhost:8080/v0/tests/${TEST_VERSION}`)
  })

  it('apiBase 끝 슬래시가 있어도 경로가 겹치지 않는다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => response({}))

    await fetchTestDefinition('http://localhost:8080/', TEST_VERSION, fetchImpl)

    expect(fetchImpl.mock.calls[0][0]).toBe(`http://localhost:8080/v0/tests/${TEST_VERSION}`)
  })

  it('testVersion은 경로에 넣기 전에 인코딩한다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => response({}))

    await fetchTestDefinition('http://localhost:8080', 'gn/2026 08', fetchImpl)

    expect(fetchImpl.mock.calls[0][0]).toBe('http://localhost:8080/v0/tests/gn%2F2026%2008')
  })
})

describe('fetchTestDefinition — 비정상 응답은 명확한 Error다', () => {
  it('HTTP 오류는 상태 코드를 담아 던진다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => response({ ok: false, status: 404 }))

    await expect(fetchTestDefinition('http://localhost:8080', TEST_VERSION, fetchImpl)).rejects.toThrow(
      'HTTP 404',
    )
  })

  it('본문이 JSON이 아니면 해석 실패로 던진다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () =>
      response({
        json: async () => {
          throw new SyntaxError('Unexpected token <')
        },
      }),
    )

    await expect(fetchTestDefinition('http://localhost:8080', TEST_VERSION, fetchImpl)).rejects.toThrow(
      'JSON 아님',
    )
  })

  it('items가 없는 응답은 계약 위반으로 던진다 (상태 머신이 TypeError로 죽기 전에 막는다)', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () =>
      response({ json: async () => ({ code: 'RESOURCE_NOT_FOUND' }) }),
    )

    await expect(fetchTestDefinition('http://localhost:8080', TEST_VERSION, fetchImpl)).rejects.toThrow(
      '계약과 다릅니다',
    )
  })

  it('testVersion이 비어 있으면 요청을 보내지도 않는다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => response({}))

    await expect(fetchTestDefinition('http://localhost:8080', '  ', fetchImpl)).rejects.toThrow('testVersion')
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  it('네트워크 실패를 재시도하지 않는다 (호출은 1회뿐)', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => {
      throw new TypeError('Failed to fetch')
    })

    await expect(fetchTestDefinition('http://localhost:8080', TEST_VERSION, fetchImpl)).rejects.toThrow(
      'Failed to fetch',
    )
    expect(fetchImpl).toHaveBeenCalledTimes(1)
  })
})
