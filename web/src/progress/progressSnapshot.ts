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
 * 이 모듈은 "언제" 저장할지도 "언제" 지울지도 정하지 않는다. visibilitychange 결선은 Stage 3의
 * 훅 몫이고, 삭제 시점은 화면이 안다 — 결과 화면 진입에서 [clearSnapshot], 인트로 진입에서
 * [sweepSnapshots]다 (KAN-198, 결선은 `App.tsx`).
 */

import { createProgressState, submitItem, type ProgressState } from './progressMachine'
import type { TestDefinition } from './testDefinition'

/**
 * 저장 키의 접두사. 같은 오리진에 다른 기능이 쓰는 키와 섞이지 않게 한다.
 * sessionId가 없는 과도기에는 이 값이 곧 키다 ([snapshotKey] 참고).
 */
export const PROGRESS_SNAPSHOT_KEY = 'accentury:progress'

/**
 * 세션별 저장 키.
 *
 * 왜 키를 나누나: 스냅샷은 한 세션의 진행 기록이고, 다른 세션의 기록은 애초에 만나지 않는 편이
 * 맞다. 같은 키를 공유하면 세션 A의 스냅샷이 세션 B에서 재생 시도 대상이 되고, 걸러지든
 * 통과하든 남의 진행을 건드린 것이 된다.
 *
 * 왜 testVersion 대조처럼 "폐기"하지 않나: 폐기는 남의 스냅샷을 지우는 일이다. 세션 A가
 * 백그라운드에서 살아 있는데 세션 B가 그 진행을 지울 권리는 없다. 버전 대조는 같은 키를
 * 나눠 쓰던 시절의 방어이므로 그대로 두되(같은 세션에서 정의가 갈린 경우는 여전히 폐기 대상),
 * 세션 격리는 키 차원에서 한다.
 *
 * @param sessionId 세션 식별자. 빈 문자열이면 접두사만 쓴다 — 세션 클라이언트(KAN-9) 결선 전까지
 *   웹에 sessionId가 오지 않는 과도기라, 그때는 예전처럼 키 하나를 쓰고 testVersion 대조로 버틴다
 */
export function snapshotKey(sessionId = ''): string {
  return sessionId === '' ? PROGRESS_SNAPSHOT_KEY : `${PROGRESS_SNAPSHOT_KEY}:${sessionId}`
}

/**
 * 이 모듈이 저장소에 요구하는 최소 인터페이스.
 * `Storage` 전체가 아니라 쓰는 세 메서드만 받는 이유: 테스트 대역을 가볍게 만들고,
 * 나중에 다른 저장소로 갈아끼울 때 구현 부담을 줄이기 위해서다.
 */
export type SnapshotStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

/**
 * 키를 훑어야 하는 작업([sweepSnapshots])이 요구하는 저장소. 세 메서드에 열거 두 개를 더한다.
 *
 * 타입을 나눠 둔 이유: 저장·복원은 키를 하나만 만지므로 열거를 요구할 이유가 없고, 요구하면
 * 테스트 대역과 미래의 브리지 저장소가 쓰지도 않는 `key`·`length`를 구현해야 한다.
 */
export type EnumerableSnapshotStorage = SnapshotStorage & Pick<Storage, 'key' | 'length'>

/** 저장소가 아예 없는 환경에서 쓰는 빈 저장소. 진행은 메모리로만 이어진다 */
const NO_STORAGE: EnumerableSnapshotStorage = {
  getItem: () => null,
  setItem: () => {},
  removeItem: () => {},
  key: () => null,
  length: 0,
}

/**
 * 이 모듈이 브라우저에서 쓰는 기본 저장소.
 *
 * 아래 함수들은 메서드 호출 실패를 저마다 방어하지만, 쿠키를 막은 브라우저에서는
 * `window.localStorage` **프로퍼티 접근 자체**가 던진다. 그 한 겹을 여기서 막는다.
 * 반환값은 매번 같은 객체라 렌더마다 참조가 바뀌지 않는다 (`useTestProgress`의 기본 인자).
 */
export function defaultSnapshotStorage(): EnumerableSnapshotStorage {
  try {
    return window.localStorage
  } catch {
    return NO_STORAGE
  }
}

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
 * @param sessionId 저장 키를 가르는 세션 식별자 ([snapshotKey])
 */
export function saveSnapshot(
  storage: SnapshotStorage,
  state: ProgressState,
  testVersion: string,
  sessionId = '',
): void {
  const snapshot: ProgressSnapshot = {
    testVersion,
    submittedItemIds: state.items.filter((_, index) => state.submitted[index]).map((item) => item.itemId),
  }
  try {
    storage.setItem(snapshotKey(sessionId), JSON.stringify(snapshot))
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
 * ## 복원하지 못한 스냅샷은 그 자리에서 버린다 (KAN-198)
 *
 * 형태가 깨졌든, 버전이 다르든, 재생이 거부됐든 판정 결과는 같다 — 이 스냅샷으로는 진행을
 * 되살릴 수 없다. 남겨 두면 다음 저장이 덮어쓸 때까지(즉 사용자가 한 문항이라도 더 풀 때까지)
 * 쓸모없는 기록이 브라우저에 남고, 그 세션을 이어 가지 않으면 영원히 남는다. 버리는 대상이
 * **이 세션 자기 키**라는 점이 근거다: 키가 세션별로 갈려 있으므로([snapshotKey]) 남의 진행을
 * 지우는 일이 아니다.
 *
 * 정의가 손상돼 `createProgressState`가 throw하면 그 예외는 그대로 올린다. 정의는 스냅샷과
 * 달리 호출자가 방금 받아 온 자기 입력이고, 손상된 정의로는 복원이든 새 시작이든 어차피
 * 진행할 수 없다. 여기서 null로 감추면 호출자가 새로 시작하려다 같은 예외를 다시 만난다.
 */
export function restoreProgress(
  storage: SnapshotStorage,
  definition: TestDefinition,
  sessionId = '',
): ProgressState | null {
  const snapshot = readSnapshot(storage, sessionId)
  if (snapshot === null) return null

  // 세션이 만료돼 새 버전으로 다시 시작한 경우. itemId가 우연히 겹치면 남의 진행을
  // 이어받는 꼴이 되므로, 재생을 시도하기 전에 버전부터 대조한다.
  if (snapshot.testVersion !== definition.testVersion) {
    clearSnapshot(storage, sessionId)
    return null
  }

  let state = createProgressState(definition)
  for (const itemId of snapshot.submittedItemIds) {
    const next = submitItem(state, itemId)
    // 동일 참조 = 상태 머신이 거부했다는 뜻 (순서 위반·중복·정의에 없는 itemId).
    // 부분 복원은 하지 않는다 — 어디까지가 진짜 진행인지 알 수 없기 때문이다.
    if (next === state) {
      clearSnapshot(storage, sessionId)
      return null
    }
    state = next
  }
  return state
}

/**
 * 저장된 진행을 지운다. 테스트를 끝냈거나 새로 시작할 때 호출한다.
 * 삭제가 실패해도 무시한다 — 남은 스냅샷은 다음 저장이 덮어쓰거나 testVersion 대조에서 걸러진다.
 */
export function clearSnapshot(storage: SnapshotStorage, sessionId = ''): void {
  try {
    storage.removeItem(snapshotKey(sessionId))
  } catch {
    // 저장소가 막힌 환경. 지울 수 없어도 복원 경로가 스스로 방어한다.
  }
}

/**
 * 이 저장소에 남은 진행 기록을 접두사째 훑어 지운다 (KAN-198). 지목한 세션 하나는 남길 수 있다.
 *
 * ## 왜 이 자리가 필요한가
 *
 * [clearSnapshot]은 세션 id를 아는 경우만 덮는다. 결과 화면까지 가지 못하고 끊긴 응시(앱 종료,
 * 탭 닫기)의 키는 아무도 그 id를 다시 들고 오지 않으므로 지울 사람이 없다 — 응시할 때마다 키가
 * 하나씩 쌓이던 원인이 그것이다.
 *
 * ## 왜 하나는 남기나
 *
 * 부르는 자리가 인트로인데, **인트로에 왔다고 응시가 끝난 것은 아니다.** 문항 화면에서 뒤로 가
 * 인트로로 돌아오는 것은 정상 행동이고(`App.tsx`의 [goToResult] 주석 — 인트로→문항 전환은
 * 히스토리를 쌓는다), 그때 앞으로 가면 같은 세션의 문항 화면이 그대로 되살아나야 한다. 전부
 * 지우면 그 복귀가 1번 문항부터 다시가 된다. 그래서 지금 살아 있는 세션 하나는 훑기에서 뺀다 —
 * 티켓의 "현재 세션 것 외에는"이 가리키는 자리다.
 *
 * 남길 세션을 모르는 실행(앱 안 — 세션은 네이티브가 쥐고 있고 인트로 URL에는 실리지 않는다)은
 * `null`을 준다. 그쪽에는 히스토리 뒤로가기가 없어(네이티브가 자기 규칙으로 다룬다) 되살릴
 * 화면도 없다.
 *
 * 지우기 전에 키를 모두 모으는 이유: `removeItem`은 뒤 인덱스를 당기므로, 훑으면서 지우면
 * 한 칸씩 건너뛴다.
 *
 * @param keepSessionId 훑기에서 뺄 세션. `null`이면 접두사에 걸리는 키를 전부 지운다.
 *   빈 문자열은 과도기 키(`snapshotKey('')`)를 남기라는 뜻이라 `null`과 다르다
 */
export function sweepSnapshots(
  storage: EnumerableSnapshotStorage,
  keepSessionId: string | null = null,
): void {
  const keep = keepSessionId === null ? null : snapshotKey(keepSessionId)
  const targets: string[] = []
  try {
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index)
      if (key === null || key === keep) continue
      if (key === PROGRESS_SNAPSHOT_KEY || key.startsWith(`${PROGRESS_SNAPSHOT_KEY}:`)) {
        targets.push(key)
      }
    }
  } catch {
    // 저장소가 막힌 환경. 훑을 수 없으면 지울 것도 없다.
    return
  }
  for (const key of targets) {
    try {
      storage.removeItem(key)
    } catch {
      // 한 키가 막혀도 나머지는 계속 지운다.
    }
  }
}

/**
 * 저장소에서 스냅샷을 읽어 형태까지 확인한다. 읽을 수 없거나 형태가 어긋나면 null.
 *
 * 여기서 보는 것은 "재생을 시도할 수 있는 형태인가"까지다. 내용이 말이 되는지(순서·존재 여부)는
 * 재생이 상태 머신 가드로 판정하므로 중복해서 검사하지 않는다.
 */
function readSnapshot(storage: SnapshotStorage, sessionId: string): ProgressSnapshot | null {
  let raw: string | null
  try {
    raw = storage.getItem(snapshotKey(sessionId))
  } catch {
    return null
  }
  if (raw === null) return null

  const snapshot = parseSnapshot(raw)
  // 재생을 시도할 수조차 없는 값이다. 저장된 게 없는 경우와 달리 지울 대상이 실재하므로
  // 여기서 버린다 ([restoreProgress] "복원하지 못한 스냅샷은 그 자리에서 버린다").
  if (snapshot === null) clearSnapshot(storage, sessionId)
  return snapshot
}

/** 저장된 문자열을 스냅샷 형태로 확인하며 되돌린다. 저장소를 모르는 순수 함수다 */
function parseSnapshot(raw: string): ProgressSnapshot | null {
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
