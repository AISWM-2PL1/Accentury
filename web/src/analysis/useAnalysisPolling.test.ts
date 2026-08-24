import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { FetchLike } from '../progress/fetchTestDefinition'
import { POLL_BUDGET_MS } from './pollSchedule'
import { useAnalysisPolling, type UseAnalysisPollingOptions } from './useAnalysisPolling'
import type { Random } from './pollSchedule'

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name: string) => headers[name] ?? null },
    json: async () => body,
  } as Response
}

function statusesBody(pollAfterMs = 800) {
  return {
    pollAfterMs,
    items: [
      { itemId: 'v1', status: 'COMPLETED', quality: 'OK' },
      { itemId: 'v2', status: 'PROCESSING' },
    ],
  }
}

function envelope(code: string, message: string, retryable: boolean, extra: Record<string, unknown> = {}) {
  return { code, message, retryable, retryAfterMs: null, correlationId: 'c_test', ...extra }
}

/** URL로 갈라 응답을 주는 fetch 대역. 호출 횟수를 세어 폴링 회차를 검증한다 */
function fetchFor(handlers: {
  analyses?: () => Response
  complete?: () => Response
}): ReturnType<typeof vi.fn<FetchLike>> {
  return vi.fn<FetchLike>(async (input) => {
    const url = String(input)
    if (url.endsWith('/analyses')) return (handlers.analyses ?? (() => jsonResponse(200, statusesBody())))()
    if (url.endsWith('/complete')) {
      return (handlers.complete ?? (() => jsonResponse(200, { status: 'PROCESSING' })))()
    }
    throw new Error(`예상 못한 요청: ${url}`)
  })
}

/**
 * @param random 기본값은 중앙값(0.5) — 양방향 지터가 상쇄돼 간격이 사다리 값 그대로가 되므로
 *   회차를 셀 수 있다. 429 검증만 0을 쓴다: Retry-After 지터는 늘리는 쪽으로만 주므로,
 *   0이어야 서버가 지시한 값과 정확히 같은 지점을 겨눌 수 있다.
 */
function options(fetchImpl: FetchLike, random: Random = () => 0.5): UseAnalysisPollingOptions {
  return {
    apiBase: 'http://localhost:8080',
    sessionId: 'sess-1',
    sessionToken: 'token-1',
    fetchImpl,
    random,
  }
}

/** 지터 없음 — 간격이 계산값과 정확히 같아진다 */
const noJitter: Random = () => 0

function callsTo(fetchImpl: ReturnType<typeof vi.fn<FetchLike>>, suffix: string): number {
  return fetchImpl.mock.calls.filter(([input]) => String(input).endsWith(suffix)).length
}

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })
  document.dispatchEvent(new Event('visibilitychange'))
}

beforeEach(() => {
  vi.useFakeTimers()
  Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
})

afterEach(() => {
  vi.useRealTimers()
})

describe('첫 회차', () => {
  it('마운트 즉시 두 엔드포인트를 한 번씩 부른다 — 첫 간격을 기다리면 빈 화면이 생긴다', async () => {
    const fetchImpl = fetchFor({})
    renderHook(() => useAnalysisPolling(options(fetchImpl)))

    await act(async () => {})

    expect(callsTo(fetchImpl, '/analyses')).toBe(1)
    expect(callsTo(fetchImpl, '/complete')).toBe(1)
  })

  it('상태를 먼저 받고 완료를 판정한다 — 409가 와도 문항 목록이 이미 화면에 있다', async () => {
    const order: string[] = []
    const fetchImpl = vi.fn<FetchLike>(async (input) => {
      const url = String(input)
      order.push(url.endsWith('/analyses') ? 'analyses' : 'complete')
      return url.endsWith('/analyses')
        ? jsonResponse(200, statusesBody())
        : jsonResponse(200, { status: 'PROCESSING' })
    })
    renderHook(() => useAnalysisPolling(options(fetchImpl)))

    await act(async () => {})

    expect(order).toEqual(['analyses', 'complete'])
  })

  it('받은 문항 상태를 그대로 싣는다', async () => {
    const fetchImpl = fetchFor({})
    const { result } = renderHook(() => useAnalysisPolling(options(fetchImpl)))

    await act(async () => {})

    expect(result.current.items.map((item) => item.itemId)).toEqual(['v1', 'v2'])
    expect(result.current.status).toEqual({ kind: 'POLLING' })
  })
})

describe('READY — 폴링이 멈춘다', () => {
  it('완료가 READY면 상태가 READY가 되고 더 두드리지 않는다', async () => {
    const fetchImpl = fetchFor({ complete: () => jsonResponse(200, { status: 'READY' }) })
    const { result } = renderHook(() => useAnalysisPolling(options(fetchImpl)))

    await act(async () => {})
    expect(result.current.status).toEqual({ kind: 'READY' })

    const before = fetchImpl.mock.calls.length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000)
    })
    expect(fetchImpl.mock.calls.length).toBe(before)
  })
})

describe('간격 — 서버 지시와 백오프', () => {
  it('첫 간격은 백오프 사다리의 800ms다', async () => {
    const fetchImpl = fetchFor({})
    renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})

    await act(async () => {
      await vi.advanceTimersByTimeAsync(799)
    })
    expect(callsTo(fetchImpl, '/analyses')).toBe(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(callsTo(fetchImpl, '/analyses')).toBe(2)
  })

  it('서버가 큰 pollAfterMs를 주면 그 값을 기다린다 (요구 1항)', async () => {
    const fetchImpl = fetchFor({ analyses: () => jsonResponse(200, statusesBody(4000)) })
    renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3999)
    })
    expect(callsTo(fetchImpl, '/analyses')).toBe(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(callsTo(fetchImpl, '/analyses')).toBe(2)
  })

  it('두 응답이 다른 간격을 주면 느린 쪽을 따른다 — 둘 다 하한이라 짧은 쪽을 고르면 한쪽을 어긴다', async () => {
    const fetchImpl = fetchFor({
      analyses: () => jsonResponse(200, statusesBody(800)),
      complete: () => jsonResponse(200, { status: 'PROCESSING', pollAfterMs: 4500 }),
    })
    renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4499)
    })
    expect(callsTo(fetchImpl, '/analyses')).toBe(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(callsTo(fetchImpl, '/analyses')).toBe(2)
  })
})

describe('429 — Retry-After를 지킨다 (요구 6항)', () => {
  it('지시한 시간 전에는 다시 요청하지 않는다', async () => {
    const fetchImpl = fetchFor({
      analyses: () =>
        jsonResponse(429, envelope('RATE_LIMITED', '요청이 너무 많습니다.', true, { retryAfterMs: 9000 })),
    })
    renderHook(() => useAnalysisPolling(options(fetchImpl, noJitter)))
    await act(async () => {})

    await act(async () => {
      await vi.advanceTimersByTimeAsync(8999)
    })
    expect(callsTo(fetchImpl, '/analyses')).toBe(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(callsTo(fetchImpl, '/analyses')).toBe(2)
  })

  it('지터는 Retry-After를 앞당기지 않는다 — 늘리는 쪽으로만 준다', async () => {
    const fetchImpl = fetchFor({
      analyses: () =>
        jsonResponse(429, envelope('RATE_LIMITED', '요청이 너무 많습니다.', true, { retryAfterMs: 9000 })),
    })
    // 지터 상한(+20%)을 뽑아도 9000ms 전에는 요청이 나가지 않는다
    renderHook(() => useAnalysisPolling(options(fetchImpl, () => 1)))
    await act(async () => {})

    await act(async () => {
      await vi.advanceTimersByTimeAsync(9000)
    })
    expect(callsTo(fetchImpl, '/analyses')).toBe(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1800)
    })
    expect(callsTo(fetchImpl, '/analyses')).toBe(2)
  })

  it('상태 조회가 429면 같은 회차의 완료 요청도 보내지 않는다', async () => {
    const fetchImpl = fetchFor({
      analyses: () =>
        jsonResponse(429, envelope('RATE_LIMITED', '요청이 너무 많습니다.', true, { retryAfterMs: 9000 })),
    })
    renderHook(() => useAnalysisPolling(options(fetchImpl, noJitter)))
    await act(async () => {})

    // 제한을 건 쪽이 프록시면 오리진 단위로 세므로, 한 회차를 통째로 미룬다
    expect(callsTo(fetchImpl, '/analyses')).toBe(1)
    expect(callsTo(fetchImpl, '/complete')).toBe(0)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(9000)
    })
    expect(callsTo(fetchImpl, '/analyses')).toBe(2)
    expect(callsTo(fetchImpl, '/complete')).toBe(0)
  })

  it('429가 아닌 조회 실패는 완료 요청을 막지 않는다 — 완료는 독립적으로 끝날 수 있다', async () => {
    const fetchImpl = fetchFor({
      analyses: () => {
        throw new TypeError('Failed to fetch')
      },
      complete: () => jsonResponse(200, { status: 'READY' }),
    })
    const { result } = renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})

    expect(callsTo(fetchImpl, '/complete')).toBe(1)
    expect(result.current.status).toEqual({ kind: 'READY' })
  })

  it('제한이 풀리면 지시를 소진하고 백오프로 돌아간다', async () => {
    let limited = true
    const fetchImpl = fetchFor({
      analyses: () => {
        if (limited) {
          limited = false
          return jsonResponse(429, envelope('RATE_LIMITED', '요청이 너무 많습니다.', true, { retryAfterMs: 9000 }))
        }
        return jsonResponse(200, statusesBody())
      },
    })
    renderHook(() => useAnalysisPolling(options(fetchImpl, noJitter)))
    await act(async () => {})

    // 9초 뒤 2회차(정상 응답), 그 다음은 사다리 2회차 값(1200ms - 지터 20%)으로 돌아온다
    await act(async () => {
      await vi.advanceTimersByTimeAsync(9000)
    })
    expect(callsTo(fetchImpl, '/analyses')).toBe(2)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(960)
    })
    expect(callsTo(fetchImpl, '/analyses')).toBe(3)
  })
})

describe('누적 60초 예산 (요구 5항)', () => {
  it('예산을 다 쓰면 EXHAUSTED로 멈추고 [다시 시도]로 넘긴다', async () => {
    const fetchImpl = fetchFor({})
    const { result } = renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})

    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_BUDGET_MS + 10_000)
    })

    expect(result.current.status).toEqual({ kind: 'EXHAUSTED' })
    const stopped = fetchImpl.mock.calls.length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })
    expect(fetchImpl.mock.calls.length).toBe(stopped)
  })

  it('restart가 예산과 회차를 초기화해 다시 돈다', async () => {
    const fetchImpl = fetchFor({})
    const { result } = renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_BUDGET_MS + 10_000)
    })
    expect(result.current.status).toEqual({ kind: 'EXHAUSTED' })

    const before = callsTo(fetchImpl, '/analyses')
    await act(async () => {
      result.current.restart()
    })
    await act(async () => {})

    expect(result.current.status).toEqual({ kind: 'POLLING' })
    expect(callsTo(fetchImpl, '/analyses')).toBe(before + 1)
  })
})

describe('사용자가 움직여야 하는 실패 — 폴링을 멈춘다', () => {
  it('409 RESULT_RETAKE_REQUIRED는 재녹음 대상과 함께 멈춘다', async () => {
    const fetchImpl = fetchFor({
      complete: () =>
        jsonResponse(
          409,
          envelope('RESULT_RETAKE_REQUIRED', '실패한 문항이 있습니다. 다시 녹음해 주세요.', true, {
            retakeItems: ['v2'],
          }),
        ),
    })
    const { result } = renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})

    expect(result.current.status).toEqual({ kind: 'ACTION_REQUIRED', reason: 'RETAKE', itemIds: ['v2'] })

    const stopped = fetchImpl.mock.calls.length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000)
    })
    // 서버가 retryable=true를 줬지만 재시도의 주체는 사용자다 — 우리가 두드려도 같은 409다
    expect(fetchImpl.mock.calls.length).toBe(stopped)
  })

  it('422 RESULT_INCOMPLETE는 미제출 문항과 함께 멈춘다', async () => {
    const fetchImpl = fetchFor({
      complete: () =>
        jsonResponse(
          422,
          envelope('RESULT_INCOMPLETE', '아직 완료하지 않은 문항이 있습니다.', false, {
            missingItems: ['v5'],
          }),
        ),
    })
    const { result } = renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})

    expect(result.current.status).toEqual({ kind: 'ACTION_REQUIRED', reason: 'MISSING', itemIds: ['v5'] })
  })
})

describe('재시도로 고쳐지지 않는 실패', () => {
  it('401 SESSION_EXPIRED는 봉투 문구를 그대로 들고 멈춘다', async () => {
    const fetchImpl = fetchFor({
      analyses: () =>
        jsonResponse(401, envelope('SESSION_EXPIRED', '세션이 만료되었습니다. 테스트를 다시 시작해 주세요.', false)),
    })
    const { result } = renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})

    expect(result.current.status).toEqual({
      kind: 'FAILED',
      message: '세션이 만료되었습니다. 테스트를 다시 시작해 주세요.',
    })
  })
})

describe('일시적 실패 — 폴링을 이어 간다', () => {
  it('네트워크가 끊겨도 직전 문항 상태를 지우지 않는다', async () => {
    let healthy = true
    const fetchImpl = fetchFor({
      analyses: () => {
        if (healthy) return jsonResponse(200, statusesBody())
        throw new TypeError('Failed to fetch')
      },
    })
    const { result } = renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})
    expect(result.current.items).toHaveLength(2)

    healthy = false
    await act(async () => {
      await vi.advanceTimersByTimeAsync(800)
    })

    expect(result.current.status).toEqual({ kind: 'POLLING' })
    expect(result.current.items).toHaveLength(2)
    expect(result.current.lastError).toMatch(/네트워크 오류/)
  })

  it('회복하면 오류 문구가 사라진다', async () => {
    let healthy = false
    const fetchImpl = fetchFor({
      analyses: () => {
        if (healthy) return jsonResponse(200, statusesBody())
        throw new TypeError('Failed to fetch')
      },
    })
    const { result } = renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})
    expect(result.current.lastError).not.toBeNull()

    healthy = true
    await act(async () => {
      await vi.advanceTimersByTimeAsync(800)
    })

    expect(result.current.lastError).toBeNull()
  })
})

describe('앱 생명주기 (요구 4항)', () => {
  it('백그라운드로 가면 폴링이 멈춘다', async () => {
    const fetchImpl = fetchFor({})
    renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})
    const before = fetchImpl.mock.calls.length

    act(() => setVisibility('hidden'))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000)
    })

    expect(fetchImpl.mock.calls.length).toBe(before)
  })

  it('복귀하면 1회 즉시 조회하고 재개한다', async () => {
    const fetchImpl = fetchFor({})
    renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})
    act(() => setVisibility('hidden'))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000)
    })
    const before = callsTo(fetchImpl, '/analyses')

    await act(async () => {
      setVisibility('visible')
    })

    expect(callsTo(fetchImpl, '/analyses')).toBe(before + 1)
  })

  it('백그라운드에 머문 시간은 예산을 쓰지 않는다 — 요청을 한 번도 안 보낸 시간이다', async () => {
    const fetchImpl = fetchFor({})
    const { result } = renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})

    act(() => setVisibility('hidden'))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10 * POLL_BUDGET_MS)
    })
    await act(async () => {
      setVisibility('visible')
    })

    // 벽시계로 쟀다면 진작 소진됐을 시간이 흘렀지만 폴링은 계속된다
    expect(result.current.status).toEqual({ kind: 'POLLING' })
  })
})

describe('언마운트', () => {
  it('화면을 떠나면 타이머가 남지 않는다', async () => {
    const fetchImpl = fetchFor({})
    const { unmount } = renderHook(() => useAnalysisPolling(options(fetchImpl)))
    await act(async () => {})

    unmount()
    const after = fetchImpl.mock.calls.length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000)
    })

    expect(fetchImpl.mock.calls.length).toBe(after)
  })
})
