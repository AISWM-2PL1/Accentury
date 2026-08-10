import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PROGRESS_SNAPSHOT_KEY, type SnapshotStorage } from './progressSnapshot'
import type { TestDefinition, TestItem } from './testDefinition'
import { useTestProgress } from './useTestProgress'

const TEST_VERSION = 'gn-2026.08.1'

/** progressSnapshot.test.ts와 같은 규칙 (seq 홀짝으로 유형 분리) */
function item(seq: number): TestItem {
  if (seq % 2 === 1) {
    return {
      itemId: `item-${seq}`,
      seq,
      type: 'VOICE',
      prompt: `음성 문항 ${seq}`,
      maxDurationMs: 10_000,
      guideF0: { unit: 'semitone', frameIntervalMs: 10, values: [0, 1] },
    }
  }
  return {
    itemId: `item-${seq}`,
    seq,
    type: 'VOCABULARY',
    prompt: `어휘 문항 ${seq}`,
    choices: [{ choiceId: 'c1', text: '보기1' }],
  }
}

function tenItemDefinition(): TestDefinition {
  return {
    testVersion: TEST_VERSION,
    scoreVersion: 'sv-0.3',
    dialect: 'GYEONGNAM',
    estimatedDurationSec: 180,
    items: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(item),
  }
}

interface SpyStorage extends SnapshotStorage {
  /** 저장 호출마다 쌓이는 원문. 저장 "횟수"까지 봐야 거부된 제출을 판별할 수 있다 */
  writes: string[]
}

function spyStorage(initial?: string): SpyStorage {
  const map = new Map<string, string>()
  if (initial !== undefined) map.set(PROGRESS_SNAPSHOT_KEY, initial)
  const writes: string[] = []
  return {
    writes,
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => {
      writes.push(value)
      map.set(key, value)
    },
    removeItem: (key) => void map.delete(key),
  }
}

function snapshotOf(storage: SpyStorage): { testVersion: string; submittedItemIds: string[] } | null {
  const raw = storage.getItem(PROGRESS_SNAPSHOT_KEY)
  return raw === null ? null : JSON.parse(raw)
}

/** 백그라운드 진입 흉내. jsdom의 visibilityState는 읽기 전용이라 정의를 갈아끼운다 */
function goHidden() {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
  document.dispatchEvent(new Event('visibilitychange'))
}

function goVisible() {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
  document.dispatchEvent(new Event('visibilitychange'))
}

afterEach(() => {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
})

describe('초기화 — 새 시작과 복원', () => {
  it('저장된 진행이 없으면 첫 문항 1/10에서 시작한다', () => {
    const { result } = renderHook(() => useTestProgress(tenItemDefinition(), spyStorage()))

    expect(result.current.current?.itemId).toBe('item-1')
    expect(result.current.progress).toEqual({ current: 1, total: 10 })
    expect(result.current.state.phase).toBe('IN_PROGRESS')
  })

  it('스냅샷이 있으면 그 다음 문항부터 이어진다 (백그라운드 복귀 AC)', () => {
    const storage = spyStorage(
      JSON.stringify({ testVersion: TEST_VERSION, submittedItemIds: ['item-1', 'item-2', 'item-3'] }),
    )

    const { result } = renderHook(() => useTestProgress(tenItemDefinition(), storage))

    expect(result.current.current?.itemId).toBe('item-4')
    expect(result.current.progress).toEqual({ current: 4, total: 10 })
  })

  it('믿을 수 없는 스냅샷은 폐기하고 처음부터 시작한다', () => {
    const storage = spyStorage(JSON.stringify({ testVersion: 'gn-2026.07.9', submittedItemIds: ['item-1'] }))

    const { result } = renderHook(() => useTestProgress(tenItemDefinition(), storage))

    expect(result.current.current?.itemId).toBe('item-1')
  })

  it('리렌더가 복원을 다시 돌려 진행을 되감지 않는다', () => {
    const storage = spyStorage()
    const { result, rerender } = renderHook(() => useTestProgress(tenItemDefinition(), storage))

    act(() => result.current.submit('item-1'))
    rerender()

    expect(result.current.current?.itemId).toBe('item-2')
  })
})

describe('submit — 진행과 저장', () => {
  it('제출할 때마다 스냅샷을 저장한다', () => {
    const storage = spyStorage()
    const { result } = renderHook(() => useTestProgress(tenItemDefinition(), storage))

    act(() => result.current.submit('item-1'))
    expect(snapshotOf(storage)).toEqual({ testVersion: TEST_VERSION, submittedItemIds: ['item-1'] })

    act(() => result.current.submit('item-2'))
    expect(snapshotOf(storage)).toEqual({ testVersion: TEST_VERSION, submittedItemIds: ['item-1', 'item-2'] })
    expect(result.current.progress).toEqual({ current: 3, total: 10 })
  })

  it('거부된 제출은 상태도 저장도 건드리지 않는다 (참조 동일)', () => {
    const storage = spyStorage()
    const { result } = renderHook(() => useTestProgress(tenItemDefinition(), storage))
    const before = result.current.state

    act(() => result.current.submit('item-5')) // 순서 위반
    act(() => result.current.submit('unknown')) // 정의에 없는 itemId

    expect(result.current.state).toBe(before)
    expect(storage.writes).toHaveLength(0)
  })

  it('같은 문항을 연타해도 한 번만 전진한다', () => {
    const storage = spyStorage()
    const { result } = renderHook(() => useTestProgress(tenItemDefinition(), storage))

    act(() => {
      result.current.submit('item-1')
      result.current.submit('item-1')
    })

    expect(result.current.current?.itemId).toBe('item-2')
    expect(storage.writes).toHaveLength(1)
  })

  it('마지막 문항까지 제출하면 분석 대기로 넘어간다', () => {
    const storage = spyStorage()
    const { result } = renderHook(() => useTestProgress(tenItemDefinition(), storage))

    for (let seq = 1; seq <= 10; seq += 1) {
      act(() => result.current.submit(`item-${seq}`))
    }

    expect(result.current.state.phase).toBe('AWAITING_ANALYSIS')
    expect(result.current.current).toBeNull()
    expect(result.current.progress).toEqual({ current: 10, total: 10 })
  })

  it('완주해도 스냅샷을 지우지 않는다 (삭제는 결과 화면 KAN-25 몫)', () => {
    const storage = spyStorage()
    const { result } = renderHook(() => useTestProgress(tenItemDefinition(), storage))

    for (let seq = 1; seq <= 10; seq += 1) {
      act(() => result.current.submit(`item-${seq}`))
    }

    expect(snapshotOf(storage)?.submittedItemIds).toHaveLength(10)
  })
})

describe('화면 이탈 시 저장', () => {
  it('백그라운드로 들어가면(visibilitychange hidden) 현재 진행을 저장한다', () => {
    const storage = spyStorage()
    const { result } = renderHook(() => useTestProgress(tenItemDefinition(), storage))
    act(() => result.current.submit('item-1'))
    storage.writes.length = 0

    act(() => goHidden())

    expect(storage.writes).toHaveLength(1)
    expect(snapshotOf(storage)).toEqual({ testVersion: TEST_VERSION, submittedItemIds: ['item-1'] })
  })

  it('다시 보이게 되는 전환에서는 저장하지 않는다', () => {
    const storage = spyStorage()
    renderHook(() => useTestProgress(tenItemDefinition(), storage))

    act(() => goVisible())

    expect(storage.writes).toHaveLength(0)
  })

  it('pagehide에서도 저장한다 (visibilitychange가 오지 않는 이탈 경로)', () => {
    const storage = spyStorage()
    renderHook(() => useTestProgress(tenItemDefinition(), storage))

    act(() => void window.dispatchEvent(new Event('pagehide')))

    expect(storage.writes).toHaveLength(1)
  })

  it('언마운트하면 리스너를 떼어 더 이상 저장하지 않는다', () => {
    const storage = spyStorage()
    const { unmount } = renderHook(() => useTestProgress(tenItemDefinition(), storage))

    unmount()
    goHidden()
    window.dispatchEvent(new Event('pagehide'))

    expect(storage.writes).toHaveLength(0)
  })
})

describe('폴링 금지 — 이 훅은 네트워크를 타지 않는다 (KAN-14 규칙 2항)', () => {
  it('마운트·제출·백그라운드 전환 어디서도 fetch가 불리지 않는다', () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)
    try {
      const { result } = renderHook(() => useTestProgress(tenItemDefinition(), spyStorage()))
      act(() => result.current.submit('item-1'))
      act(() => goHidden())

      expect(fetchSpy).not.toHaveBeenCalled()
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
