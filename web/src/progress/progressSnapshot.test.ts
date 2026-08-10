import { describe, expect, it } from 'vitest'
import { createProgressState, currentItem, progress, submitItem, type ProgressState } from './progressMachine'
import {
  PROGRESS_SNAPSHOT_KEY,
  clearSnapshot,
  restoreProgress,
  saveSnapshot,
  type SnapshotStorage,
} from './progressSnapshot'
import type { TestDefinition, TestItem } from './testDefinition'

const TEST_VERSION = 'gn-2026.08.1'

/** progressMachine.test.ts와 같은 규칙으로 문항을 만든다 (seq 홀짝으로 유형 분리) */
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

/** KAN-10 확정 구성인 10문항 정의. 복귀 후 재fetch를 흉내내려고 매번 새 객체를 만든다 */
function tenItemDefinition(testVersion = TEST_VERSION): TestDefinition {
  return {
    testVersion,
    scoreVersion: 'sv-0.3',
    dialect: 'GYEONGNAM',
    estimatedDurationSec: 180,
    items: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(item),
  }
}

/** 현재 문항부터 n개를 순서대로 제출한다 */
function submitCount(state: ProgressState, count: number): ProgressState {
  let next = state
  for (let i = 0; i < count; i += 1) {
    const target = currentItem(next)
    if (target === null) break
    next = submitItem(next, target.itemId)
  }
  return next
}

/** localStorage 대역. 실제 브라우저 저장소 없이 왕복을 검증한다 */
function fakeStorage(initial?: string): SnapshotStorage {
  const map = new Map<string, string>()
  if (initial !== undefined) map.set(PROGRESS_SNAPSHOT_KEY, initial)
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
  }
}

/** 지정한 메서드가 던지는 저장소. 시크릿 모드·쿼터 초과 환경을 흉내낸다 */
function throwingStorage(failing: keyof SnapshotStorage): SnapshotStorage {
  const base = fakeStorage()
  const boom = (): never => {
    throw new DOMException('storage is disabled', 'SecurityError')
  }
  return {
    getItem: failing === 'getItem' ? boom : base.getItem,
    setItem: failing === 'setItem' ? boom : base.setItem,
    removeItem: failing === 'removeItem' ? boom : base.removeItem,
  }
}

describe('왕복 — 백그라운드 복귀 후에도 현재 문항이 유지된다', () => {
  it('1문항 제출 뒤 복귀하면 다음 문항에서 이어진다', () => {
    const storage = fakeStorage()
    const before = submitCount(createProgressState(tenItemDefinition()), 1)
    saveSnapshot(storage, before, TEST_VERSION)

    // 프로세스 킬 → 페이지 재로드 → 정의 재fetch(Stage 3)를 흉내낸 새 정의 객체
    const restored = restoreProgress(storage, tenItemDefinition())

    expect(currentItem(restored!)?.itemId).toBe('item-2')
    expect(progress(restored!)).toEqual({ current: 2, total: 10 })
    expect(restored!.phase).toBe('IN_PROGRESS')
  })

  it('중간 진행(3문항)도 제출 여부까지 그대로 복원된다', () => {
    const storage = fakeStorage()
    const before = submitCount(createProgressState(tenItemDefinition()), 3)
    saveSnapshot(storage, before, TEST_VERSION)

    const restored = restoreProgress(storage, tenItemDefinition())

    expect(restored).toEqual(before)
    expect(restored!.submitted).toEqual([true, true, true, false, false, false, false, false, false, false])
  })

  it('아직 한 문항도 제출하지 않았으면 첫 문항 상태로 복원된다', () => {
    const storage = fakeStorage()
    const before = createProgressState(tenItemDefinition())
    saveSnapshot(storage, before, TEST_VERSION)

    expect(restoreProgress(storage, tenItemDefinition())).toEqual(before)
  })

  it('전부 제출한 뒤 복귀하면 분석 대기(AWAITING_ANALYSIS) 그대로 복원된다', () => {
    const storage = fakeStorage()
    const before = submitCount(createProgressState(tenItemDefinition()), 10)
    saveSnapshot(storage, before, TEST_VERSION)

    const restored = restoreProgress(storage, tenItemDefinition())

    expect(restored!.phase).toBe('AWAITING_ANALYSIS')
    expect(currentItem(restored!)).toBeNull()
    expect(progress(restored!)).toEqual({ current: 10, total: 10 })
  })

  it('저장은 문항 본문 없이 testVersion과 제출한 itemId만 담는다', () => {
    let stored: string | null = null
    const storage: SnapshotStorage = {
      getItem: () => stored,
      setItem: (_key, value) => {
        stored = value
      },
      removeItem: () => {
        stored = null
      },
    }
    saveSnapshot(storage, submitCount(createProgressState(tenItemDefinition()), 2), TEST_VERSION)

    expect(JSON.parse(stored!)).toEqual({
      testVersion: TEST_VERSION,
      submittedItemIds: ['item-1', 'item-2'],
    })
  })
})

describe('복원 거부 — 믿을 수 없는 스냅샷은 폐기한다', () => {
  it('저장된 스냅샷이 없으면 null이다 (첫 진입)', () => {
    expect(restoreProgress(fakeStorage(), tenItemDefinition())).toBeNull()
  })

  it('JSON이 깨져 있으면 null이다', () => {
    expect(restoreProgress(fakeStorage('{"testVersion":'), tenItemDefinition())).toBeNull()
  })

  it('testVersion이 다르면 폐기한다 (세션 만료 후 새 버전으로 재시작)', () => {
    const storage = fakeStorage()
    saveSnapshot(storage, submitCount(createProgressState(tenItemDefinition()), 3), 'gn-2026.07.9')

    expect(restoreProgress(storage, tenItemDefinition(TEST_VERSION))).toBeNull()
  })

  it('제출 순서를 건너뛴 스냅샷은 폐기한다 (부분 복원 금지)', () => {
    const tampered = JSON.stringify({
      testVersion: TEST_VERSION,
      submittedItemIds: ['item-1', 'item-4'],
    })
    expect(restoreProgress(fakeStorage(tampered), tenItemDefinition())).toBeNull()
  })

  it('정의에 없는 itemId가 섞이면 폐기한다', () => {
    const tampered = JSON.stringify({
      testVersion: TEST_VERSION,
      submittedItemIds: ['item-1', 'item-999'],
    })
    expect(restoreProgress(fakeStorage(tampered), tenItemDefinition())).toBeNull()
  })

  it('같은 itemId가 중복으로 들어 있으면 폐기한다', () => {
    const tampered = JSON.stringify({
      testVersion: TEST_VERSION,
      submittedItemIds: ['item-1', 'item-1'],
    })
    expect(restoreProgress(fakeStorage(tampered), tenItemDefinition())).toBeNull()
  })

  it('문항 수보다 많은 제출 기록은 폐기한다', () => {
    const tampered = JSON.stringify({
      testVersion: TEST_VERSION,
      submittedItemIds: [...Array(11)].map((_, i) => `item-${i + 1}`),
    })
    expect(restoreProgress(fakeStorage(tampered), tenItemDefinition())).toBeNull()
  })

  it('필드 타입이 오염된 스냅샷은 폐기한다', () => {
    const definition = tenItemDefinition()
    const polluted = [
      '"just a string"',
      'null',
      JSON.stringify({ testVersion: 1, submittedItemIds: [] }),
      JSON.stringify({ testVersion: TEST_VERSION }),
      JSON.stringify({ testVersion: TEST_VERSION, submittedItemIds: 'item-1' }),
      JSON.stringify({ testVersion: TEST_VERSION, submittedItemIds: ['item-1', 7] }),
      JSON.stringify({ testVersion: TEST_VERSION, submittedItemIds: [{ itemId: 'item-1' }] }),
    ]
    for (const raw of polluted) {
      expect(restoreProgress(fakeStorage(raw), definition), raw).toBeNull()
    }
  })

  it('clearSnapshot 뒤에는 복원할 것이 없다', () => {
    const storage = fakeStorage()
    saveSnapshot(storage, submitCount(createProgressState(tenItemDefinition()), 3), TEST_VERSION)
    clearSnapshot(storage)

    expect(restoreProgress(storage, tenItemDefinition())).toBeNull()
  })
})

describe('graceful degrade — 저장소가 막혀도 크래시하지 않는다', () => {
  it('setItem이 던져도 저장 호출은 조용히 넘어간다 (진행은 메모리로 계속)', () => {
    const state = submitCount(createProgressState(tenItemDefinition()), 2)
    expect(() => saveSnapshot(throwingStorage('setItem'), state, TEST_VERSION)).not.toThrow()
    expect(currentItem(state)?.itemId).toBe('item-3')
  })

  it('getItem이 던지면 복원은 null이다', () => {
    const storage = throwingStorage('getItem')
    expect(() => restoreProgress(storage, tenItemDefinition())).not.toThrow()
    expect(restoreProgress(storage, tenItemDefinition())).toBeNull()
  })

  it('removeItem이 던져도 삭제 호출은 조용히 넘어간다', () => {
    expect(() => clearSnapshot(throwingStorage('removeItem'))).not.toThrow()
  })
})

describe('손상된 정의 — 스냅샷 문제가 아니므로 감추지 않는다', () => {
  it('정의 자체가 손상됐으면 createProgressState의 예외를 그대로 올린다', () => {
    const valid = JSON.stringify({ testVersion: TEST_VERSION, submittedItemIds: ['item-1'] })
    const broken: TestDefinition = { ...tenItemDefinition(), items: [] }
    expect(() => restoreProgress(fakeStorage(valid), broken)).toThrow('문항이 없습니다')
  })
})
