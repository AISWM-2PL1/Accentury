import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AccenturyBridge } from '../bridge/bridge'
import type { RetestFailure } from '../bridge/retestFailure'
import { useRetest } from './useRetest'

/**
 * `startRetest`를 가진 브리지 대역. 재응시에 필요한 메서드만 있으면 되지만 계약 타입을
 * 그대로 만족시킨다 — 필수 메서드가 늘어나면 여기서 컴파일이 깨져야 한다.
 */
function stubBridge(): ReturnType<typeof vi.fn> {
  const startRetest = vi.fn()
  const bridge: AccenturyBridge = {
    requestMicPermission: vi.fn(),
    startVoiceItem: vi.fn(),
    getContractVersion: () => 1,
    startRetest,
  }
  window.AccenturyBridge = bridge
  return startRetest
}

/** 네이티브가 실패를 회신하는 자리. 실제 경로와 같게 JSON 문자열로 넣는다 */
function deliverFailure(failure: Partial<RetestFailure> = {}) {
  const payload: RetestFailure = {
    code: 'RETEST_FAILED',
    message: '다시 시작하지 못했어요. 잠시 후 다시 시도해 주세요.',
    retryable: true,
    retryAfterMs: null,
    ...failure,
  }
  act(() => {
    window.AccenturyWeb?.onRetestFailed?.(JSON.stringify(payload))
  })
}

afterEach(() => {
  delete window.AccenturyBridge
  delete window.AccenturyWeb
  delete window.gtag
  vi.useRealTimers()
})

/** GA4 태그 자리의 대역 (KAN-33). 도착한 이벤트를 순서대로 모은다 */
function stubGtag(): Record<string, unknown>[] {
  const events: Record<string, unknown>[] = []
  window.gtag = (...args: unknown[]) => {
    if (args[0] !== 'event') return
    events.push({ event: args[1] as string, ...(args[2] as Record<string, unknown>) })
  }
  return events
}


describe('브리지 분기', () => {
  it('브리지가 있으면 네이티브 재응시를 부르고 폴백은 타지 않는다', () => {
    const startRetest = stubBridge()
    const fallback = vi.fn()
    const { result } = renderHook(() => useRetest(fallback))

    act(() => result.current.onRetest())

    expect(startRetest).toHaveBeenCalledTimes(1)
    expect(fallback).not.toHaveBeenCalled()
  })

  it('브리지가 없으면(브라우저 단독) 폴백으로 내려가고 버튼을 잠그지 않는다', () => {
    const fallback = vi.fn()
    const { result } = renderHook(() => useRetest(fallback))

    act(() => result.current.onRetest())

    expect(fallback).toHaveBeenCalledTimes(1)
    // 잠그면 페이지가 안 넘어간 환경에서 버튼이 영영 죽는다 — 실패 회신도 오지 않는다
    expect(result.current.disabled).toBe(false)
    expect(result.current.pending).toBe(false)
  })

  it('startRetest가 없는 구버전 앱(계약 버전 1)도 폴백으로 내려간다', () => {
    window.AccenturyBridge = {
      requestMicPermission: vi.fn(),
      startVoiceItem: vi.fn(),
      getContractVersion: () => 1,
    }
    const fallback = vi.fn()
    const { result } = renderHook(() => useRetest(fallback))

    act(() => result.current.onRetest())

    expect(fallback).toHaveBeenCalledTimes(1)
  })
})

describe('잠금 — 더블탭 방지 (KAN-107이 서버 멱등 장치를 두지 않는다)', () => {
  it('호출이 성사된 순간부터 잠기고 준비 중이 된다', () => {
    stubBridge()
    const { result } = renderHook(() => useRetest(vi.fn()))

    act(() => result.current.onRetest())

    expect(result.current.pending).toBe(true)
    expect(result.current.disabled).toBe(true)
    expect(result.current.message).toBeNull()
  })

  it('잠긴 동안 다시 불러도 두 번째 요청이 나가지 않는다', () => {
    const startRetest = stubBridge()
    const { result } = renderHook(() => useRetest(vi.fn()))

    act(() => result.current.onRetest())
    act(() => result.current.onRetest())

    // 두 번째가 나가면 첫 요청이 만든 세션이 곧바로 고아가 된다
    expect(startRetest).toHaveBeenCalledTimes(1)
  })

  it('성공은 회신이 없으므로 잠금이 저절로 풀리지 않는다', () => {
    stubBridge()
    vi.useFakeTimers()
    const { result } = renderHook(() => useRetest(vi.fn()))

    act(() => result.current.onRetest())
    act(() => vi.advanceTimersByTime(60_000))

    // 시간으로 풀면 아직 살아 있는 create 왕복 위에 두 번째 요청이 겹친다
    expect(result.current.disabled).toBe(true)
    expect(result.current.pending).toBe(true)
  })
})

describe('실패 회신', () => {
  it('retryable이면 문구를 내리고 버튼을 다시 연다', () => {
    stubBridge()
    const { result } = renderHook(() => useRetest(vi.fn()))
    act(() => result.current.onRetest())

    deliverFailure({ message: '잠시 후 다시 시도해 주세요.' })

    expect(result.current.message).toBe('잠시 후 다시 시도해 주세요.')
    expect(result.current.disabled).toBe(false)
    expect(result.current.pending).toBe(false)
  })

  it('다시 열린 뒤 누르면 요청이 실제로 다시 나간다', () => {
    const startRetest = stubBridge()
    const { result } = renderHook(() => useRetest(vi.fn()))
    act(() => result.current.onRetest())
    deliverFailure()

    act(() => result.current.onRetest())

    expect(startRetest).toHaveBeenCalledTimes(2)
    expect(result.current.pending).toBe(true)
  })

  it('retryable이 아니면 문구만 남기고 잠금을 유지한다', () => {
    const startRetest = stubBridge()
    const { result } = renderHook(() => useRetest(vi.fn()))
    act(() => result.current.onRetest())

    deliverFailure({ retryable: false, message: '지금은 테스트를 시작할 수 없어요.' })

    expect(result.current.message).toBe('지금은 테스트를 시작할 수 없어요.')
    expect(result.current.disabled).toBe(true)
    // 눌러도 소용없다는 판정이라 상태 쪽 문도 닫혀 있어야 한다
    act(() => result.current.onRetest())
    expect(startRetest).toHaveBeenCalledTimes(1)
  })

  it('불량 payload는 화면을 바꾸지 않는다 — 잠긴 채로 남는다', () => {
    stubBridge()
    const { result } = renderHook(() => useRetest(vi.fn()))
    act(() => result.current.onRetest())

    act(() => {
      window.AccenturyWeb?.onRetestFailed?.('{ 이건 JSON이 아니다')
    })

    expect(result.current.message).toBeNull()
    expect(result.current.pending).toBe(true)
  })
})

describe('429 대기 카운트다운 (§2.5)', () => {
  it('남은 초를 올림해서 세고, 0이 되면 버튼이 열린다', () => {
    vi.useFakeTimers()
    stubBridge()
    const { result } = renderHook(() => useRetest(vi.fn()))
    act(() => result.current.onRetest())

    deliverFailure({ retryAfterMs: 2_500 })

    // 2.5초는 3초로 올린다 — 내림하면 "2초"라고 적고 그 말을 믿은 사용자가 다시 429를 맞는다
    expect(result.current.retryAfterSec).toBe(3)
    expect(result.current.disabled).toBe(true)

    act(() => vi.advanceTimersByTime(1_000))
    expect(result.current.retryAfterSec).toBe(2)
    expect(result.current.disabled).toBe(true)

    act(() => vi.advanceTimersByTime(2_000))
    expect(result.current.retryAfterSec).toBe(0)
    expect(result.current.disabled).toBe(false)
  })

  it('대기가 끝나기 전에는 눌러도 요청이 나가지 않는다', () => {
    vi.useFakeTimers()
    const startRetest = stubBridge()
    const { result } = renderHook(() => useRetest(vi.fn()))
    act(() => result.current.onRetest())
    deliverFailure({ retryAfterMs: 5_000 })

    act(() => result.current.onRetest())
    expect(startRetest).toHaveBeenCalledTimes(1)

    act(() => vi.advanceTimersByTime(5_000))
    act(() => result.current.onRetest())
    expect(startRetest).toHaveBeenCalledTimes(2)
  })

  it('retryAfterMs가 없는 실패는 카운트다운을 띄우지 않는다', () => {
    stubBridge()
    const { result } = renderHook(() => useRetest(vi.fn()))
    act(() => result.current.onRetest())

    deliverFailure({ retryAfterMs: null })

    expect(result.current.retryAfterSec).toBe(0)
  })

  it('다시 눌러도 소용없는 실패에는 남은 시간을 띄우지 않는다', () => {
    stubBridge()
    const { result } = renderHook(() => useRetest(vi.fn()))
    act(() => result.current.onRetest())

    // 기다리면 된다는 뜻으로 읽히면 안 된다 — 잠금은 유지되고 초는 나오지 않는다
    deliverFailure({ retryable: false, retryAfterMs: 5_000 })

    expect(result.current.retryAfterSec).toBe(0)
    expect(result.current.disabled).toBe(true)
  })

  it('다 센 뒤에는 타이머가 남지 않는다', () => {
    vi.useFakeTimers()
    stubBridge()
    const { result } = renderHook(() => useRetest(vi.fn()))
    act(() => result.current.onRetest())
    deliverFailure({ retryAfterMs: 1_000 })

    act(() => vi.advanceTimersByTime(1_000))

    expect(result.current.retryAfterSec).toBe(0)
    expect(vi.getTimerCount()).toBe(0)
  })
})

describe('수신자 설치·해제 (§8)', () => {
  it('마운트하면 수신 지점이 걸리고, 언마운트하면 설치 전으로 정확히 되돌아간다', () => {
    const { unmount } = renderHook(() => useRetest(vi.fn()))

    expect(typeof window.AccenturyWeb?.onRetestFailed).toBe('function')

    unmount()
    expect(window.AccenturyWeb).toBeUndefined()
  })

  it('같은 슬롯의 다른 수신자를 지우지 않는다', () => {
    const onItemResult = vi.fn()
    window.AccenturyWeb = { onItemResult }

    const { unmount } = renderHook(() => useRetest(vi.fn()))
    expect(window.AccenturyWeb?.onItemResult).toBe(onItemResult)

    unmount()
    expect(window.AccenturyWeb?.onItemResult).toBe(onItemResult)
    expect(window.AccenturyWeb?.onRetestFailed).toBeUndefined()
  })
})

describe('재응시 계측 (KAN-33)', () => {
  it('앱 안에서는 브리지를 타고 네이티브로 간다 — 웹 스트림으로 새지 않는다', () => {
    const events = stubGtag()
    const logEvent = vi.fn()
    stubBridge()
    window.AccenturyBridge!.logEvent = logEvent
    const { result } = renderHook(() => useRetest(vi.fn()))

    act(() => result.current.onRetest())

    expect(logEvent).toHaveBeenCalledWith('retest_started', '{}')
    expect(events).toEqual([])
  })

  it('폴백(브라우저 단독·구버전 앱)도 같은 사건이라 함께 센다', () => {
    const events = stubGtag()
    const fallback = vi.fn()
    const { result } = renderHook(() => useRetest(fallback))

    act(() => result.current.onRetest())

    expect(fallback).toHaveBeenCalledTimes(1)
    expect(events).toEqual([{ event: 'retest_started' }])
  })

  it('잠긴 버튼을 두드린 것은 새 응시가 아니다 — 세지 않는다', () => {
    stubBridge()
    const { result } = renderHook(() => useRetest(vi.fn()))
    act(() => result.current.onRetest())
    const events = stubGtag()

    act(() => result.current.onRetest())

    expect(events).toEqual([])
  })
})
