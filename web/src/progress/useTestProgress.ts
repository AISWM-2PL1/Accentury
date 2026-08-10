/**
 * 진행 상태 머신을 React에 붙이는 훅 (KAN-99 Stage 3).
 *
 * 하는 일은 두 가지뿐이다: 순수 상태 머신(`progressMachine`)을 React state로 들고 있는 것,
 * 그리고 진행이 바뀌거나 화면이 사라질 때 스냅샷(`progressSnapshot`)을 저장하는 것.
 * 전이 규칙도 스냅샷 형식도 여기서 다시 정의하지 않는다 — 이 훅은 두 순수 모듈의 **결선**이다.
 *
 * **네트워크·타이머·폴링이 없다.** 이건 빠뜨린 게 아니라 이 훅의 계약이다.
 * KAN-14 폴링 규칙 2항("문항 진행 중에는 분석 상태를 폴링하지 않는다")을 웹에서 보증하는 지점이
 * 바로 여기다 — 문항 화면이 쓰는 상태 소스에 fetch도 setInterval도 없으면, 진행 중 폴링은
 * 실수로도 생기지 않는다. 폴링은 페이즈가 `AWAITING_ANALYSIS`로 넘어간 뒤 분석 대기 화면(KAN-14)이 시작한다.
 * 정의 로딩(`fetchTestDefinition`)도 훅 바깥(화면)에 둔 이유가 같다.
 *
 * **`clearSnapshot`을 여기서 부르지 않는다.** 마지막 문항을 제출해도 스냅샷은 남긴다 —
 * 분석 대기 중 백그라운드로 갔다가 복귀하면 `AWAITING_ANALYSIS` 상태로 되살아나야 하기 때문이다.
 * 삭제 시점은 결과 화면(KAN-25)에 진입해 이 진행이 완전히 끝났을 때다.
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  createProgressState,
  currentItem,
  progress,
  submitItem,
  type Progress,
  type ProgressState,
} from './progressMachine'
import { restoreProgress, saveSnapshot, type SnapshotStorage } from './progressSnapshot'
import type { TestDefinition, TestItem } from './testDefinition'

/** 화면이 진행을 그리고 움직이는 데 필요한 것 전부 */
export interface UseTestProgressResult {
  state: ProgressState
  /** 지금 풀어야 할 문항. 전부 제출한 뒤에는 null (= 분석 대기) */
  current: TestItem | null
  /** 진행바용 n/N. 첫 문항이 1/10이다 (endowed progress — ux-ui.md §3 Goal-Gradient) */
  progress: Progress
  /** 현재 문항의 제출 완료 통지. 상태 머신이 거부하면 아무 일도 일어나지 않는다 */
  submit: (itemId: string) => void
}

/** 저장소가 아예 없는 환경에서 쓰는 빈 저장소. 진행은 메모리로만 이어진다 */
const NO_STORAGE: SnapshotStorage = {
  getItem: () => null,
  setItem: () => {},
  removeItem: () => {},
}

/**
 * 기본 저장소. `progressSnapshot`은 메서드 호출 실패를 방어하지만, 쿠키를 막은 브라우저에서는
 * `window.localStorage` **프로퍼티 접근 자체**가 던진다. 그 한 겹만 여기서 막는다.
 * 반환값은 매번 같은 객체라 렌더마다 참조가 바뀌지 않는다.
 */
function defaultStorage(): SnapshotStorage {
  try {
    return window.localStorage
  } catch {
    return NO_STORAGE
  }
}

export function useTestProgress(
  definition: TestDefinition,
  storage: SnapshotStorage = defaultStorage(),
): UseTestProgressResult {
  // lazy initializer — 마운트당 한 번만 복원을 시도한다. 매 렌더 복원하면 방금 진행한 상태를
  // 스냅샷으로 덮어써 되감기가 된다.
  const [state, setState] = useState<ProgressState>(
    () => restoreProgress(storage, definition) ?? createProgressState(definition),
  )

  // 저장 시점(제출·화면 이탈)에 필요한 최신 값들. ref에 모아두는 이유: 상태가 바뀔 때마다
  // visibilitychange 리스너를 떼었다 붙였다 하지 않기 위해서다. 이탈 직전에 리스너가 없는
  // 순간이 생기면 그 진행이 통째로 날아간다.
  const latest = useRef({ state, storage, testVersion: definition.testVersion })
  latest.current = { state, storage, testVersion: definition.testVersion }

  const submit = useCallback((itemId: string) => {
    const previous = latest.current.state
    const next = submitItem(previous, itemId)
    // 동일 참조 = 상태 머신이 거부했다(중복 제출·순서 위반·모르는 itemId).
    // 진행이 그대로이므로 저장도 리렌더도 하지 않는다.
    if (next === previous) return

    latest.current.state = next
    saveSnapshot(latest.current.storage, next, latest.current.testVersion)
    setState(next)
  }, [])

  useEffect(() => {
    const persist = () => {
      saveSnapshot(latest.current.storage, latest.current.state, latest.current.testVersion)
    }
    // 백그라운드 진입 신호. WebView가 뒤로 밀린 뒤 OS가 렌더러를 죽여도 여기까지는 실행된다.
    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') persist()
    }
    document.addEventListener('visibilitychange', onVisibilityChange)
    // pagehide도 같이 건다. 페이지 자체가 내려가는 경로(리로드·이탈)는 visibilitychange가
    // 오지 않을 수 있고, 저장은 멱등이라 두 번 불려도 손해가 없다.
    window.addEventListener('pagehide', persist)
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange)
      window.removeEventListener('pagehide', persist)
    }
  }, [])

  return {
    state,
    current: currentItem(state),
    progress: progress(state),
    submit,
  }
}
