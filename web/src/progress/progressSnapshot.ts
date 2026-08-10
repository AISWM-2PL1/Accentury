/**
 * 진행 상태 스냅샷 저장·복원 (KAN-99 Stage 2 — 백그라운드 복귀 영속화).
 *
 * 왜 필요한가: Android WebView는 앱이 백그라운드에 있는 동안 OS가 렌더러를 죽일 수 있고,
 * 복귀하면 페이지가 처음부터 다시 로드된다. 메모리에만 있던 진행 상태는 그때 사라진다.
 * "앱이 백그라운드에서 복귀해도 현재 문항이 유지된다"는 AC를 지키려면 진행이 프로세스
 * 바깥에 남아 있어야 한다.
 *
 * 저장소로 localStorage를 기본으로 삼는 이유: sessionStorage는 탭 세션에 묶여 있어
 * 프로세스 킬 후 복귀에서 살아남는다는 보장이 없다. 다만 이 모듈은 저장소를 인자로 받는다 —
 * 테스트에서 실물 없이 검증하고, 나중에 네이티브 브리지 저장소 등으로 갈아끼울 수 있게 하기 위해서다.
 *
 * 설계 두 가지:
 * 1. **저장은 최소, 복원은 재구성.** 스냅샷에는 `testVersion`과 제출한 itemId 목록만 담는다.
 *    복원은 저장된 값을 상태로 되살리는 게 아니라, 새로 받은 정의로 초기 상태를 만든 뒤
 *    itemId를 순서대로 재생(replay)한다. 그래서 손상·변조된 스냅샷은 상태 머신이 이미 가진
 *    가드(순서·중복·미지 itemId 거부)를 통과해야만 복원되고, 여기에 별도의 무결성 검증
 *    코드를 둘 필요가 없다. 재생 중 하나라도 거부되면 스냅샷 전체를 폐기한다 —
 *    어중간하게 복원된 진행보다 처음부터 다시 푸는 쪽이 안전하다.
 * 2. **실패해도 크래시하지 않는다** (bridge.ts §5 graceful degrade와 같은 방침).
 *    시크릿 모드·쿼터 초과·저장소 비활성처럼 localStorage 접근 자체가 throw하는 환경이 있다.
 *    저장 실패는 조용히 무시하고(진행은 메모리로 계속된다), 복원 실패는 null이다.
 *
 * 이 모듈은 "언제" 저장할지를 정하지 않는다. visibilitychange 결선은 Stage 3의 훅 몫이다.
 */

import { createProgressState, submitItem, type ProgressState } from './progressMachine'
import type { TestDefinition } from './testDefinition'

/**
 * 저장 키. 같은 오리진에 다른 기능이 쓰는 키와 섞이지 않도록 접두사를 둔다.
 * 세션이 바뀌어도 키는 같다 — 진행 중인 테스트는 한 번에 하나뿐이고,
 * 세션이 바뀐 경우는 testVersion 대조로 걸러진다.
 */
export const PROGRESS_SNAPSHOT_KEY = 'accentury:progress'

/**
 * 이 모듈이 저장소에 요구하는 최소 인터페이스.
 * `Storage` 전체가 아니라 쓰는 세 메서드만 받는 이유: 테스트 대역을 가볍게 만들고,
 * 나중에 다른 저장소로 갈아끼울 때 구현 부담을 줄이기 위해서다.
 */
export type SnapshotStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

/**
 * 저장되는 스냅샷의 형태.
 *
 * @property testVersion 이 진행이 딛고 선 정의 버전. 세션에 고정된 값이다 (KAN-10 §5.4 발행 후 불변)
 * @property submittedItemIds 제출을 마친 문항의 itemId — 제출 순서대로
 */
interface ProgressSnapshot {
  testVersion: string
  submittedItemIds: string[]
}

/**
 * 현재 진행을 저장한다.
 *
 * 상태 객체 전체가 아니라 제출한 itemId만 뽑아 넣는다. 문항 본문(prompt·guideF0·choices)은
 * 복원 시 어차피 새 정의에서 다시 오므로 저장할 이유가 없고, 저장량이 커질수록 쿼터 초과로
 * 저장 자체가 실패할 위험만 늘어난다.
 *
 * 저장에 실패해도 알리지 않는다 — 진행 자체는 메모리에서 정상이고, 여기서 예외를 올리면
 * 저장 시점(백그라운드 진입 직전)에 화면이 죽는다. 잃는 것은 "복귀 시 복원"뿐이다.
 *
 * @param testVersion 진행 중인 세션의 정의 버전 (상태 머신은 이 값을 들고 있지 않아 따로 받는다)
 */
export function saveSnapshot(storage: SnapshotStorage, state: ProgressState, testVersion: string): void {
  const snapshot: ProgressSnapshot = {
    testVersion,
    submittedItemIds: state.items.filter((_, index) => state.submitted[index]).map((item) => item.itemId),
  }
  try {
    storage.setItem(PROGRESS_SNAPSHOT_KEY, JSON.stringify(snapshot))
  } catch {
    // 저장소가 막힌 환경(시크릿 모드·쿼터 초과). 복원을 포기할 뿐 진행은 계속된다.
  }
}

/**
 * 저장된 진행을 새 정의 위에서 재구성한다. 복원할 게 없거나 신뢰할 수 없으면 null이다.
 *
 * null이 돌아오면 호출자는 `createProgressState(definition)`으로 처음부터 시작하면 된다.
 * 반대로 상태가 돌아오면 그 상태가 `AWAITING_ANALYSIS`일 수 있다 — 마지막 문항까지 제출한
 * 직후 백그라운드로 갔다가 복귀한 경우다. 이 모듈은 그 상태를 그대로 재구성해 줄 뿐이고,
 * 분석 대기 화면(KAN-14)으로 보낼지는 페이즈를 보는 호출자의 판단이다.
 *
 * 정의가 손상돼 `createProgressState`가 throw하면 그 예외는 그대로 올린다. 정의는 스냅샷과
 * 달리 호출자가 방금 받아 온 자기 입력이고, 손상된 정의로는 복원이든 새 시작이든 어차피
 * 진행할 수 없다. 여기서 null로 감추면 호출자가 새로 시작하려다 같은 예외를 다시 만난다.
 */
export function restoreProgress(storage: SnapshotStorage, definition: TestDefinition): ProgressState | null {
  const snapshot = readSnapshot(storage)
  if (snapshot === null) return null

  // 세션이 만료돼 새 버전으로 다시 시작한 경우. itemId가 우연히 겹치면 남의 진행을
  // 이어받는 꼴이 되므로, 재생을 시도하기 전에 버전부터 대조한다.
  if (snapshot.testVersion !== definition.testVersion) return null

  let state = createProgressState(definition)
  for (const itemId of snapshot.submittedItemIds) {
    const next = submitItem(state, itemId)
    // 동일 참조 = 상태 머신이 거부했다는 뜻 (순서 위반·중복·정의에 없는 itemId).
    // 부분 복원은 하지 않는다 — 어디까지가 진짜 진행인지 알 수 없기 때문이다.
    if (next === state) return null
    state = next
  }
  return state
}

/**
 * 저장된 진행을 지운다. 테스트를 끝냈거나 새로 시작할 때 호출한다.
 * 삭제가 실패해도 무시한다 — 남은 스냅샷은 다음 저장이 덮어쓰거나 testVersion 대조에서 걸러진다.
 */
export function clearSnapshot(storage: SnapshotStorage): void {
  try {
    storage.removeItem(PROGRESS_SNAPSHOT_KEY)
  } catch {
    // 저장소가 막힌 환경. 지울 수 없어도 복원 경로가 스스로 방어한다.
  }
}

/**
 * 저장소에서 스냅샷을 읽어 형태까지 확인한다. 읽을 수 없거나 형태가 어긋나면 null.
 *
 * 여기서 보는 것은 "재생을 시도할 수 있는 형태인가"까지다. 내용이 말이 되는지(순서·존재 여부)는
 * 재생이 상태 머신 가드로 판정하므로 중복해서 검사하지 않는다.
 */
function readSnapshot(storage: SnapshotStorage): ProgressSnapshot | null {
  let raw: string | null
  try {
    raw = storage.getItem(PROGRESS_SNAPSHOT_KEY)
  } catch {
    return null
  }
  if (raw === null) return null

  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }

  // 저장소 내용은 외부 입력이라 타입을 믿지 않는다. `unknown`으로 받아 필드마다 실제로 확인한다.
  if (typeof parsed !== 'object' || parsed === null) return null
  const { testVersion, submittedItemIds } = parsed as Record<keyof ProgressSnapshot, unknown>
  if (typeof testVersion !== 'string') return null
  if (!Array.isArray(submittedItemIds)) return null
  if (!submittedItemIds.every((id): id is string => typeof id === 'string')) return null

  return { testVersion, submittedItemIds }
}
