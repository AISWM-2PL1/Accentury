/**
 * 문항 진행 상태 머신 (KAN-99 — 오케스트레이션 코어).
 *
 * 서버 테스트 정의(KAN-10)가 정한 순서대로 문항을 하나씩 진행시키고, 진행바용 n/N과
 * 전체 페이즈를 산출한다. 순서를 이 모듈이 임의로 만들지 않는다 — 정의의 `seq`가 정본이다.
 *
 * 설계 규칙 두 가지:
 * 1. **네트워크 호출이 없다.** 문항 진행 중 분석 상태 폴링을 하지 않는다는 요구를 코드 구조로
 *    보장하기 위해, 이 모듈은 fetch도 타이머도 갖지 않는 순수 함수 모음이다. 폴링은 분석
 *    대기 화면(KAN-14)이 페이즈 전환을 본 뒤에 시작한다.
 * 2. **상태는 JSON 왕복 가능한 plain object다.** Set/Map/클래스 인스턴스를 쓰지 않는 이유:
 *    Stage 2에서 백그라운드 복귀 대응으로 이 상태를 sessionStorage 스냅샷에 그대로
 *    저장·복원할 것이기 때문이다. 직렬화가 값을 잃으면 복원된 진행이 어긋난다.
 */

import type { TestDefinition, TestItem } from './testDefinition'

/**
 * 전체 진행 페이즈.
 * - `IN_PROGRESS`: 아직 남은 문항이 있다
 * - `AWAITING_ANALYSIS`: 마지막 문항까지 제출됐다 = 분석 대기(KAN-14) 전환 신호
 */
export type ProgressPhase = 'IN_PROGRESS' | 'AWAITING_ANALYSIS'

/**
 * 진행 상태 스냅샷.
 *
 * 정의에서 뽑은 정렬된 문항 배열을 상태가 직접 들고 있다. 정의를 매번 같이 넘기지 않아도
 * 조회 함수가 완결되고, Stage 2에서 스냅샷 하나만 복원하면 진행이 그대로 살아나기 때문이다.
 *
 * @property items 진행 순서로 정렬된 문항. 이 배열의 인덱스가 곧 진행 순번이다
 * @property submitted items와 같은 길이의 제출 여부 배열 (itemId 집합 대신 배열을 쓰는 이유는 직렬화 안전성)
 * @property currentIndex 현재 문항의 인덱스. 전부 제출되면 items.length가 되어 유효 범위를 벗어난다
 * @property phase 전체 페이즈
 */
export interface ProgressState {
  readonly items: readonly TestItem[]
  readonly submitted: readonly boolean[]
  readonly currentIndex: number
  readonly phase: ProgressPhase
}

/** 진행바용 진척도. `current`는 사람이 읽는 값이라 1-based다 (첫 문항이 1/10) */
export interface Progress {
  current: number
  total: number
}

/**
 * 정의로부터 초기 상태를 만든다.
 *
 * 손상된 정의는 여기서 바로 막는다 — 진행 도중에 드러나면 사용자가 이미 답을 몇 개 낸
 * 뒤라 되돌릴 방법이 없기 때문이다. 방어 대상은 "순서를 확정할 수 없게 만드는" 손상뿐이다:
 * 문항 0개, seq 중복(순서 불확정), itemId 중복(제출 통지의 대상이 불확정).
 * seq가 연속인지는 보지 않는다 — 10, 20, 30처럼 띄워 쓰는 정의도 정상이다.
 *
 * @throws Error 정의가 손상된 경우
 */
export function createProgressState(definition: TestDefinition): ProgressState {
  const items = [...definition.items].sort((a, b) => a.seq - b.seq)

  if (items.length === 0) {
    throw new Error('테스트 정의에 문항이 없습니다')
  }
  const duplicateSeq = findDuplicate(items.map((item) => item.seq))
  if (duplicateSeq !== null) {
    throw new Error(`테스트 정의의 seq가 중복입니다: ${duplicateSeq}`)
  }
  const duplicateItemId = findDuplicate(items.map((item) => item.itemId))
  if (duplicateItemId !== null) {
    throw new Error(`테스트 정의의 itemId가 중복입니다: ${duplicateItemId}`)
  }

  return {
    items,
    submitted: items.map(() => false),
    currentIndex: 0,
    phase: 'IN_PROGRESS',
  }
}

/** 지금 풀어야 할 문항. 전부 제출된 뒤에는 null이다 (보여줄 문항이 없다는 뜻) */
export function currentItem(state: ProgressState): TestItem | null {
  return state.items[state.currentIndex] ?? null
}

/**
 * 진행바용 n/N.
 * 전부 제출된 뒤에는 N/N으로 고정한다 — 인덱스는 범위를 벗어나지만 진행바는 가득 찬 상태를
 * 보여야 하고, N+1/N 같은 값이 화면에 나가면 안 되기 때문이다.
 */
export function progress(state: ProgressState): Progress {
  const total = state.items.length
  return { current: Math.min(state.currentIndex + 1, total), total }
}

/**
 * 현재 문항의 제출 완료 통지 — KAN-100(화면 전환 브리지)이 이 머신을 움직이는 유일한 진입점이다.
 *
 * 받아들이면 다음 문항으로 전진한 **새 상태**를, 거부하면 **받은 상태 객체를 그대로** 돌려준다.
 * 새 객체를 만들지 않는 이유: 호출자가 `next === state`로 거부를 판별할 수 있고, React state로
 * 물려도 무의미한 리렌더가 생기지 않기 때문이다.
 *
 * 거부하는 통지는 두 가지이며, 둘 다 상태를 바꾸지 않는다:
 * - **이미 제출한 문항**의 재통지 (중복 제출 차단 — 네트워크 재시도나 버튼 연타로 실제로 들어온다)
 * - **현재 문항이 아닌 문항**의 통지. 앞 문항이든 뒤 문항이든 거부한다 — 정의가 정한 순서를
 *   호출자가 건너뛰게 두면 이 머신이 순서를 보장한다고 말할 수 없다. 정의에 없는 itemId도 같다.
 */
export function submitItem(state: ProgressState, itemId: string): ProgressState {
  const current = currentItem(state)
  if (current === null || current.itemId !== itemId) return state
  // 정상 진행에서는 currentIndex가 제출된 문항을 가리키는 일이 없지만, Stage 2에서 복원할
  // 스냅샷은 외부(sessionStorage)에서 오므로 제출 여부를 한 번 더 본다.
  if (state.submitted[state.currentIndex]) return state

  const submitted = state.submitted.map((done, index) => (index === state.currentIndex ? true : done))
  const nextIndex = state.currentIndex + 1
  return {
    items: state.items,
    submitted,
    currentIndex: nextIndex,
    phase: nextIndex >= state.items.length ? 'AWAITING_ANALYSIS' : 'IN_PROGRESS',
  }
}

/** 중복된 첫 값을 찾는다. 없으면 null */
function findDuplicate<T>(values: T[]): T | null {
  for (let i = 0; i < values.length; i += 1) {
    if (values.indexOf(values[i]) !== i) return values[i]
  }
  return null
}
