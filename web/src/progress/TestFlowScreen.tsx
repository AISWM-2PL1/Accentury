/**
 * 문항 진행 화면 (KAN-99 Stage 3). 무디자인 — 인트로·업데이트 안내와 같은 최소 인라인 스타일 톤이다.
 *
 * 화면은 네 상태를 가진다: 정의 로딩 중 / 로딩 실패(다시 시도) / 문항 진행 / 분석 대기 자리 표시.
 * 로딩과 진행을 컴포넌트 두 개로 나눈 이유: 진행 훅은 정의가 있어야 초기화되는데, 같은
 * 컴포넌트에 두면 정의가 오기 전 렌더에서 훅을 부를 수 없어 조건부 훅이 된다. 정의가 확정된
 * 뒤에 `TestRunner`를 마운트하면 훅이 항상 유효한 입력으로 시작한다.
 *
 * **이 화면에는 폴링이 없다** (KAN-14 폴링 규칙 2항). 네트워크 호출은 정의 조회 1회뿐이고,
 * 그 외에는 타이머도 주기 요청도 없다. 상태 확인 폴링은 분석 대기 화면(KAN-14)이 시작한다.
 */

import { useCallback, useEffect, useState } from 'react'
import { fetchTestDefinition, type FetchLike } from './fetchTestDefinition'
import type { SnapshotStorage } from './progressSnapshot'
import type { TestDefinition, TestItem } from './testDefinition'
import { useTestProgress } from './useTestProgress'

/** 유형 뱃지 문구. 인트로의 "🎤 음성 / 📝 단어" 표기와 같은 어휘를 쓴다 */
const TYPE_BADGE: Record<TestItem['type'], string> = {
  VOICE: '🎤 음성 문항',
  VOCABULARY: '📝 단어 문항',
}

const SCREEN_STYLE = {
  minHeight: '100dvh',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  gap: '12px',
  padding: '16px',
  textAlign: 'center',
} as const

export interface TestFlowScreenProps {
  /** 백엔드 오리진. 출처 결정은 호출자 몫이다 (fetchTestDefinition 헤더 주석의 열린 질문) */
  apiBase: string
  /** 세션에 고정된 정의 버전. 정식 출처는 KAN-100에서 확정된다 */
  testVersion: string
  /** 스냅샷 저장소. 기본값(localStorage)은 훅이 정한다 */
  storage?: SnapshotStorage
  /** 주입용 fetch (테스트용) */
  fetchImpl?: FetchLike
}

type LoadState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; definition: TestDefinition }

export function TestFlowScreen({ apiBase, testVersion, storage, fetchImpl }: TestFlowScreenProps) {
  const [load, setLoad] = useState<LoadState>({ status: 'loading' })
  // [다시 시도]는 이 값을 올려 로딩 이펙트를 다시 돌린다. 재시도 로직을 fetch 쪽에 두지 않은 채
  // 사용자가 재시도 주체가 되게 하는 방식이다 (fetchTestDefinition 주석 참고).
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    // 재시도로 요청이 겹칠 때 먼저 뜬 응답이 뒤늦게 화면을 덮지 않도록 버린다.
    let cancelled = false
    setLoad({ status: 'loading' })
    fetchTestDefinition(apiBase, testVersion, fetchImpl)
      .then((definition) => {
        if (!cancelled) setLoad({ status: 'ready', definition })
      })
      .catch((error: unknown) => {
        if (!cancelled) setLoad({ status: 'error', message: errorMessage(error) })
      })
    return () => {
      cancelled = true
    }
  }, [apiBase, testVersion, fetchImpl, attempt])

  const retry = useCallback(() => setAttempt((n) => n + 1), [])

  if (load.status === 'loading') {
    return (
      <main style={SCREEN_STYLE}>
        <p style={{ fontSize: '16px', margin: 0 }}>문항을 불러오는 중…</p>
      </main>
    )
  }

  if (load.status === 'error') {
    return (
      <main style={SCREEN_STYLE}>
        {/* 비난 없는 카피 톤(ux-ui.md). 원인 문구는 개발 중 진단용으로 함께 보인다 */}
        <p style={{ fontSize: '16px', margin: 0 }}>문항을 불러오지 못했어요</p>
        <p style={{ fontSize: '13px', margin: 0, color: '#666' }}>{load.message}</p>
        <button
          type="button"
          onClick={retry}
          style={{ minHeight: '48px', minWidth: '120px', fontSize: '16px', cursor: 'pointer' }}
        >
          다시 시도
        </button>
      </main>
    )
  }

  // 정의가 바뀌면(=다른 세션) 진행도 처음부터여야 하므로 훅 상태째로 갈아끼운다.
  return <TestRunner key={load.definition.testVersion} definition={load.definition} storage={storage} />
}

function TestRunner({ definition, storage }: { definition: TestDefinition; storage?: SnapshotStorage }) {
  const { state, current, progress, submit } = useTestProgress(definition, storage)

  // 마지막 문항까지 제출됨. 분석 대기 화면(KAN-14)은 아직 없어 자리만 잡아 둔다 —
  // 폴링·복구 UX는 그 티켓에서 이 자리에 붙는다.
  if (state.phase === 'AWAITING_ANALYSIS' || current === null) {
    return (
      <main style={SCREEN_STYLE}>
        <h1 style={{ fontSize: '20px', fontWeight: 600, margin: 0 }}>분석 대기 화면 (KAN-14 예정)</h1>
        <p style={{ fontSize: '14px', margin: 0 }}>
          {progress.total}문항을 모두 제출했어요. 여기서 분석 상태 폴링이 시작됩니다.
        </p>
      </main>
    )
  }

  return (
    <main style={SCREEN_STYLE}>
      {/*
        진행바. `<progress>`의 내장 의미론(role=progressbar + value/max)을 그대로 쓴다 —
        직접 div로 그리면 aria를 손으로 채워야 하고 무디자인 단계에서 얻는 게 없다.
        값이 1부터 시작하는 건 의도다: 첫 문항을 0/10으로 보이면 아직 시작도 안 한 느낌이라
        이탈이 는다 (ux-ui.md §3 Goal-Gradient — endowed progress).
      */}
      <progress
        aria-label="문항 진행률"
        value={progress.current}
        max={progress.total}
        style={{ width: '100%', maxWidth: '320px' }}
      />
      <p style={{ fontSize: '14px', margin: 0 }}>
        {progress.current}/{progress.total}
      </p>
      <p style={{ fontSize: '13px', margin: 0 }}>{TYPE_BADGE[current.type]}</p>
      <h1 style={{ fontSize: '20px', fontWeight: 600, margin: 0 }}>{current.prompt}</h1>
      {/*
        임시 [다음] — 제출 완료 통지가 들어올 자리다. 실제로는 네이티브 녹음 화면(KAN-87)이나
        어휘 선택 화면이 답을 받고 돌아오면서 이 통지를 보내야 하고, 그 결선이 KAN-100이다.
        그때 이 버튼은 통째로 사라진다.
      */}
      <button
        type="button"
        onClick={() => submit(current.itemId)}
        style={{ minHeight: '48px', minWidth: '120px', fontSize: '16px', marginTop: '8px', cursor: 'pointer' }}
      >
        다음
      </button>
      <p style={{ fontSize: '12px', margin: 0, color: '#666' }}>
        임시 버튼입니다 — 실제 녹음·단어 선택 화면 연결은 KAN-100에서 붙습니다.
      </p>
    </main>
  )
}

/** Error가 아닌 값이 던져질 수 있으므로(문자열 reject 등) 문구 추출을 한 곳에 모은다 */
function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
