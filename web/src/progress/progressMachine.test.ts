import { describe, expect, it } from 'vitest'
import {
  createProgressState,
  currentItem,
  progress,
  submitItem,
  type ProgressState,
} from './progressMachine'
import type { TestDefinition, TestItem } from './testDefinition'

/** seq를 지정해 문항을 만든다. 유형은 seq 홀짝으로 갈라 두 유형이 섞인 실제 정의에 가깝게 둔다 */
function item(seq: number): TestItem {
  if (seq % 2 === 1) {
    return {
      itemId: `item-${seq}`,
      seq,
      type: 'VOICE',
      prompt: `음성 문항 ${seq}`,
      maxDurationMs: 10_000,
      guideF0: { unit: 'semitone', frameIntervalMs: 10, values: [0, 1], bandLow: [-1, 0], bandHigh: [1, 2] },
    }
  }
  return {
    itemId: `item-${seq}`,
    seq,
    type: 'VOCABULARY',
    prompt: `어휘 문항 ${seq}`,
    choices: [
      { choiceId: 'c1', text: '보기1' },
      { choiceId: 'c2', text: '보기2' },
    ],
  }
}

function definitionOf(items: TestItem[]): TestDefinition {
  return {
    testVersion: 'gn-2026.08.1',
    scoreVersion: 'sv-0.3',
    dialect: 'GYEONGNAM',
    estimatedDurationSec: 180,
    items,
  }
}

/** KAN-10 확정 구성인 10문항 정의 */
function tenItemDefinition(): TestDefinition {
  return definitionOf([1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(item))
}

/** 현재 문항을 순서대로 계속 제출해 끝까지 진행시킨다 */
function submitAll(state: ProgressState): ProgressState {
  let next = state
  for (let i = 0; i < state.items.length; i += 1) {
    const target = currentItem(next)
    if (target === null) break
    next = submitItem(next, target.itemId)
  }
  return next
}

describe('createProgressState — 정의가 정한 순서를 그대로 확정한다', () => {
  it('진행 순서는 정의의 seq 순서와 일치한다', () => {
    const state = createProgressState(tenItemDefinition())
    expect(state.items.map((i) => i.itemId)).toEqual([
      'item-1', 'item-2', 'item-3', 'item-4', 'item-5',
      'item-6', 'item-7', 'item-8', 'item-9', 'item-10',
    ])
  })

  it('items가 seq 순으로 오지 않아도 seq를 정본으로 삼아 정렬한다', () => {
    const state = createProgressState(definitionOf([item(3), item(1), item(2)]))
    expect(state.items.map((i) => i.seq)).toEqual([1, 2, 3])
    expect(currentItem(state)?.itemId).toBe('item-1')
  })

  it('첫 문항이 현재 문항이고 페이즈는 진행 중이다', () => {
    const state = createProgressState(tenItemDefinition())
    expect(currentItem(state)?.itemId).toBe('item-1')
    expect(state.phase).toBe('IN_PROGRESS')
    expect(state.submitted).toEqual(Array(10).fill(false))
  })

  it('상태는 JSON 왕복이 가능하다 (Stage 2 sessionStorage 스냅샷 전제)', () => {
    const state = createProgressState(tenItemDefinition())
    expect(JSON.parse(JSON.stringify(state))).toEqual(state)
  })
})

describe('createProgressState — 손상된 정의 방어', () => {
  it('문항이 0개면 진행을 시작하지 않는다', () => {
    expect(() => createProgressState(definitionOf([]))).toThrow('문항이 없습니다')
  })

  it('seq가 중복이면 순서가 불확정이라 거부한다', () => {
    const duplicated = definitionOf([item(1), { ...item(2), seq: 1 }])
    expect(() => createProgressState(duplicated)).toThrow('seq가 중복')
  })

  it('itemId가 중복이면 제출 통지 대상이 불확정이라 거부한다', () => {
    const duplicated = definitionOf([item(1), { ...item(2), itemId: 'item-1' }])
    expect(() => createProgressState(duplicated)).toThrow('itemId가 중복')
  })
})

describe('progress — 진행바용 n/N', () => {
  it('첫 문항은 1/10이다', () => {
    expect(progress(createProgressState(tenItemDefinition()))).toEqual({ current: 1, total: 10 })
  })

  it('제출할 때마다 n이 1씩 오른다', () => {
    let state = createProgressState(tenItemDefinition())
    state = submitItem(state, 'item-1')
    expect(progress(state)).toEqual({ current: 2, total: 10 })
    state = submitItem(state, 'item-2')
    expect(progress(state)).toEqual({ current: 3, total: 10 })
  })

  it('전부 제출한 뒤에도 N/N을 넘지 않는다', () => {
    const state = submitAll(createProgressState(tenItemDefinition()))
    expect(progress(state)).toEqual({ current: 10, total: 10 })
  })
})

describe('submitItem — 전이', () => {
  it('현재 문항을 제출하면 다음 문항으로 전진하고 제출 여부가 기록된다', () => {
    const state = createProgressState(tenItemDefinition())
    const next = submitItem(state, 'item-1')
    expect(currentItem(next)?.itemId).toBe('item-2')
    expect(next.submitted[0]).toBe(true)
    expect(next.submitted[1]).toBe(false)
  })

  it('원래 상태를 변형하지 않는다 (순수 전이)', () => {
    const state = createProgressState(tenItemDefinition())
    submitItem(state, 'item-1')
    expect(state.currentIndex).toBe(0)
    expect(state.submitted[0]).toBe(false)
  })

  it('이미 제출한 문항의 재통지는 상태를 바꾸지 않는다 (중복 제출 차단)', () => {
    const state = submitItem(createProgressState(tenItemDefinition()), 'item-1')
    const again = submitItem(state, 'item-1')
    expect(again).toBe(state)
    expect(progress(again)).toEqual({ current: 2, total: 10 })
  })

  it('현재 문항이 아닌 뒷 문항의 통지는 거부한다 (순서 건너뛰기 금지)', () => {
    const state = createProgressState(tenItemDefinition())
    expect(submitItem(state, 'item-5')).toBe(state)
  })

  it('정의에 없는 itemId 통지는 거부한다', () => {
    const state = createProgressState(tenItemDefinition())
    expect(submitItem(state, 'item-999')).toBe(state)
  })

  it('전부 제출된 뒤의 통지는 거부한다', () => {
    const state = submitAll(createProgressState(tenItemDefinition()))
    expect(submitItem(state, 'item-10')).toBe(state)
    expect(submitItem(state, 'item-1')).toBe(state)
  })
})

describe('페이즈 — 분석 대기(KAN-14) 전환 신호', () => {
  it('마지막 문항 직전까지는 진행 중이다', () => {
    let state = createProgressState(tenItemDefinition())
    for (const id of ['item-1', 'item-2', 'item-3', 'item-4', 'item-5',
      'item-6', 'item-7', 'item-8', 'item-9']) {
      state = submitItem(state, id)
      expect(state.phase).toBe('IN_PROGRESS')
    }
  })

  it('마지막 문항을 제출하면 분석 대기로 전환되고 보여줄 문항이 없다', () => {
    const state = submitAll(createProgressState(tenItemDefinition()))
    expect(state.phase).toBe('AWAITING_ANALYSIS')
    expect(currentItem(state)).toBeNull()
    expect(state.submitted).toEqual(Array(10).fill(true))
  })
})

describe('경계 정의', () => {
  it('문항이 1개면 첫 제출이 곧 마지막 제출이다', () => {
    const state = createProgressState(definitionOf([item(1)]))
    expect(progress(state)).toEqual({ current: 1, total: 1 })

    const done = submitItem(state, 'item-1')
    expect(done.phase).toBe('AWAITING_ANALYSIS')
    expect(progress(done)).toEqual({ current: 1, total: 1 })
    expect(currentItem(done)).toBeNull()
  })

  it('seq가 비연속(10, 20, 30)이어도 대소 관계만으로 순서를 잡는다', () => {
    const state = createProgressState(definitionOf([item(30), item(10), item(20)]))
    expect(state.items.map((i) => i.seq)).toEqual([10, 20, 30])
    expect(progress(state)).toEqual({ current: 1, total: 3 })

    const next = submitItem(state, 'item-10')
    expect(currentItem(next)?.seq).toBe(20)
    expect(progress(next)).toEqual({ current: 2, total: 3 })
  })
})
