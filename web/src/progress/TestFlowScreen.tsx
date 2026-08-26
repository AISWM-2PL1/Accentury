/**
 * 문항 진행 화면 (KAN-99 Stage 3). 무디자인 — 인트로·업데이트 안내와 같은 최소 인라인 스타일 톤이다.
 *
 * 화면은 네 상태를 가진다: 정의 로딩 중 / 로딩 실패(다시 시도) / 문항 진행 / 분석 대기(KAN-14).
 * 로딩과 진행을 컴포넌트 두 개로 나눈 이유: 진행 훅은 정의가 있어야 초기화되는데, 같은
 * 컴포넌트에 두면 정의가 오기 전 렌더에서 훅을 부를 수 없어 조건부 훅이 된다. 정의가 확정된
 * 뒤에 `TestRunner`를 마운트하면 훅이 항상 유효한 입력으로 시작한다.
 *
 * **문항이 떠 있는 동안에는 폴링이 없다** (KAN-14 폴링 규칙 2항). 정의 조회 1회 말고는
 * 타이머도 주기 요청도 없고, 폴링은 마지막 문항까지 제출된 뒤 이 화면이 내주는 분석 대기
 * 화면에서 비로소 시작한다 — 그 화면이 마운트돼야 폴링 훅이 서기 때문에, 진행 중 폴링은
 * 실수로도 생기지 않는다.
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import { AnalysisWaitingScreen } from '../analysis/AnalysisWaitingScreen'
import type { CaptureFactory, Recording } from '../audio'
import { uploadRecording } from '../audio/uploadRecording'
import { getSessionToken, installItemResultReceiver, startVoiceItem } from '../bridge/bridge'
import type { ItemResult } from '../bridge/itemResult'
import { fetchTestDefinition, type FetchLike } from './fetchTestDefinition'
import type { SnapshotStorage } from './progressSnapshot'
import { submitVocabAnswer } from './submitVocabAnswer'
import type { TestDefinition, VoiceItem } from './testDefinition'
import { useTestProgress } from './useTestProgress'
import { VocabularyItemScreen } from './VocabularyItemScreen'
import { VoiceItemScreen } from './VoiceItemScreen'
import { Button, ProgressIndicator, StatusBlock } from '../ui'

export interface TestFlowScreenProps {
  /** 백엔드 오리진. 출처 결정은 호출자 몫이다 (fetchTestDefinition 헤더 주석의 열린 질문) */
  apiBase: string
  /** 세션에 고정된 정의 버전. 네이티브가 진입 쿼리로 실어 준다 (App.tsx) */
  testVersion: string
  /** 진행 스냅샷을 세션별로 가르는 식별자. KAN-9 결선 전까지는 빈 문자열이 온다 */
  sessionId?: string
  /** 스냅샷 저장소. 기본값(localStorage)은 훅이 정한다 */
  storage?: SnapshotStorage
  /**
   * 분석이 끝나 결과가 확정됐다 (KAN-14). 결과 화면으로 보내는 것은 호출자 몫이다 —
   * 진입 쿼리 계약은 App이 들고 있고, 이 화면이 URL을 알 필요가 없다.
   */
  onAnalysisReady?: () => void
  /**
   * 브리지가 없는 환경(웹 단독 실행)의 세션 토큰 출처 (KAN-56 Stage 3 → KAN-31).
   *
   * 브리지가 있으면 토큰은 거기서 오고, 없으면 웹 단독 세션(`session/webSession`)에서 온다.
   * 그래도 여기서 저장소를 직접 읽지 않고 함수로 주입받는 이유는 그대로다 — 토큰의 출처를
   * 고르는 것은 진입 분기(App)의 일이고, 이 화면은 "요청할 때마다 한 번 부른다"는 규칙만
   * 안다 (`fetchTestDefinition`이 apiBase·testVersion 출처를 호출자에게 미룬 것과 같은 이유).
   */
  webSessionToken?: () => string
  /** 주입용 캡처 (테스트용). 브라우저 녹음 경로에만 닿는다 */
  capture?: CaptureFactory
  /** 주입용 fetch (테스트용) */
  fetchImpl?: FetchLike
}

type LoadState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; definition: TestDefinition }

export function TestFlowScreen({
  apiBase,
  testVersion,
  sessionId = '',
  storage,
  onAnalysisReady,
  webSessionToken,
  capture,
  fetchImpl,
}: TestFlowScreenProps) {
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
      <main className="screen">
        <StatusBlock tone="waiting" message="문항을 불러오는 중…" />
      </main>
    )
  }

  if (load.status === 'error') {
    return (
      <main className="screen">
        {/* 비난 없는 카피 톤(ux-ui.md). 원인 문구는 개발 중 진단용으로 함께 보인다 */}
        <StatusBlock
          tone="error"
          message="문항을 불러오지 못했어요"
          detail={load.message}
          action={<Button onClick={retry}>다시 시도</Button>}
        />
      </main>
    )
  }

  // 세션이나 정의가 바뀌면 진행도 처음부터여야 하므로 훅 상태째로 갈아끼운다.
  return (
    <TestRunner
      key={`${sessionId}:${load.definition.testVersion}`}
      definition={load.definition}
      apiBase={apiBase}
      sessionId={sessionId}
      storage={storage}
      onAnalysisReady={onAnalysisReady}
      webSessionToken={webSessionToken}
      capture={capture}
      fetchImpl={fetchImpl}
    />
  )
}

function TestRunner({
  definition,
  apiBase,
  sessionId,
  storage,
  onAnalysisReady,
  webSessionToken,
  capture,
  fetchImpl,
}: {
  definition: TestDefinition
  apiBase: string
  sessionId: string
  storage?: SnapshotStorage
  onAnalysisReady?: () => void
  webSessionToken?: () => string
  capture?: CaptureFactory
  fetchImpl?: FetchLike
}) {
  const { state, current, progress, submit } = useTestProgress(definition, storage, sessionId)
  /*
   * 네이티브가 문항 결과를 돌려줄 때마다 오른다. 대기 화면은 이 값의 변화를 재녹음 완료
   * 신호로 읽어 폴링을 다시 세운다 — 진행 중에 오르는 것은 무해하다. 그때는 대기 화면이
   * 마운트되어 있지 않아 아무도 보지 않는다.
   */
  const [resultNonce, setResultNonce] = useState(0)

  /*
   * 네이티브 → 웹 결과 수신 지점. 화면이 살아 있는 동안 한 번만 설치한다 — 문항마다 갈아끼우면
   * 전환 도중에 결과가 들어왔을 때 받을 사람이 없는 순간이 생긴다.
   *
   * 받은 itemId를 그대로 상태 머신에 넘긴다. 지금 문항인지·이미 제출했는지·순서를 건너뛰지
   * 않았는지는 submitItem의 가드가 이미 판정하므로, 같은 검증을 여기서 되풀이하지 않는다
   * (규칙이 두 곳에 생기면 언젠가 어긋난다).
   *
   * attemptId·analysisJobId·durationMs·qualityStatus는 여전히 읽지 않는다. 대기 화면은
   * 문항 상태를 서버(`/analyses`)에서 받으므로, 브리지가 실어 보낸 사본을 믿을 이유가 없다 —
   * 채점 대상을 정하는 것은 서버이고, 두 출처가 어긋나면 화면이 서버와 다른 말을 하게 된다.
   * 여기서 쓰는 것은 "무언가 돌아왔다"는 사실뿐이다.
   */
  /*
   * 문항 결과 하나를 받는다 — 브리지(네이티브 녹음)와 브라우저 녹음 업로드가 **같은 함수로**
   * 들어온다. 두 경로가 만드는 것이 같은 [ItemResult]라 받는 쪽을 나눌 이유가 없고, 나누면
   * 진행을 미는 규칙이 두 벌이 되어 언젠가 어긋난다.
   */
  const receiveResult = useCallback(
    (result: ItemResult) => {
      submit(result.itemId)
      setResultNonce((n) => n + 1)
    },
    [submit],
  )

  useEffect(() => installItemResultReceiver(receiveResult), [receiveResult])

  /**
   * 이 화면이 서버로 나갈 때 쓰는 세션 토큰 — **녹음 업로드·어휘 제출·분석 폴링이 전부 이
   * 함수를 거친다** (KAN-31).
   *
   * 세 곳이 각자 토큰을 읽던 시절에는 웹 단독 실행에서 업로드만 되고 나머지는 빈 토큰으로
   * 막혔다. 출처를 고르는 규칙이 셋으로 흩어져 있었기 때문인데, 규칙이 흩어지면 실행 환경이
   * 하나 늘 때마다 세 곳을 같이 고쳐야 하고 한 곳을 빠뜨리면 그 경로만 조용히 막힌다.
   *
   * 읽는 시점은 **요청할 때마다**다. 미리 잡아 두면 페이지 전환으로 브리지 판정이나 세션이
   * 바뀐 뒤에도 낡은 값을 계속 쓴다.
   */
  const readToken = useCallback(() => webSessionToken?.() ?? getSessionToken() ?? '', [webSessionToken])

  /**
   * 브라우저 녹음의 업로드 통로.
   *
   * 브리지가 있는 앱에서도 이 함수는 만들어지지만 호출되지 않는다: 브리지가 있으면
   * [VoiceItemScreen]이 네이티브 경로로 가고 녹음 패널 자체를 그리지 않는다.
   */
  const uploadWebRecording = useCallback(
    (itemId: string, recording: Recording, attemptId: string) =>
      uploadRecording(
        { apiBase, sessionId, itemId, sessionToken: readToken(), attemptId, recording },
        fetchImpl,
      ),
    [apiBase, sessionId, readToken, fetchImpl],
  )

  /*
   * 재녹음 — 대기 화면이 실패한 문항을 짚으면 그 문항으로 녹음 화면을 다시 연다.
   *
   * 브리지 계약을 늘리지 않는다. `startVoiceItem`은 문항 컨텍스트를 통째로 받는 호출이라
   * 어느 문항으로든 다시 부를 수 있고, 네이티브 입장에서 이것은 "그 문항을 녹음하라"는 같은
   * 지시다 — 재녹음 전용 메서드를 새로 만들면 계약 버전이 올라가고(§5), 구버전 앱에서
   * 대기 화면 전체가 업데이트 안내로 막힌다.
   *
   * 서버 쪽에서도 이것은 새 시도(attempt)일 뿐이다. 채점 대상은 문항당 최신 성공 시도 1건이라
   * (§5.1) 이전 시도를 지울 필요가 없고, 진행 상태 머신도 건드리지 않는다 — 그 문항은 이미
   * 제출된 것으로 남아 있어야 진행률 분모가 흔들리지 않는다.
   */
  const retake = useCallback(
    (itemId: string) => {
      const index = state.items.findIndex((item) => item.itemId === itemId)
      const item = state.items[index]
      if (item === undefined || item.type !== 'VOICE') return
      startVoiceItem({
        itemId: item.itemId,
        prompt: item.prompt,
        // 첫 녹음 때 네이티브가 그린 번호와 같아야 한다 — 사용자가 "3번 문항"으로 기억한다
        itemNumber: index + 1,
        totalItems: state.items.length,
        maxDurationMs: item.maxDurationMs,
        guideF0: item.guideF0,
      })
    },
    [state.items],
  )

  /*
   * 대기 화면이 그릴 음성 문항. 순번은 **전체 문항 기준**으로 매겨서 넘긴다 — 음성 안에서
   * 몇 번째인지(1~5)로 부르면, 그 줄의 [다시 녹음]을 눌렀을 때 네이티브 녹음 화면이 그리는
   * 번호("7 / 10")와 어긋난다. 사용자에게는 다른 문항으로 간 것처럼 보인다.
   */
  const voiceItems = useMemo(
    () =>
      state.items
        .map((item, index) => ({ item, itemNumber: index + 1 }))
        .filter((entry): entry is { item: VoiceItem; itemNumber: number } => entry.item.type === 'VOICE'),
    [state.items],
  )

  // 마지막 문항까지 제출됨 — 여기서부터 분석 대기 화면이 폴링을 맡는다 (KAN-14).
  if (state.phase === 'AWAITING_ANALYSIS' || current === null) {
    return (
      <AnalysisWaitingScreen
        apiBase={apiBase}
        sessionId={sessionId}
        /*
         * 토큰은 렌더 시점에 읽는다 — 폴링이 도는 동안 화면이 여러 번 그려지지만, 훅은
         * 토큰 값이 바뀔 때만 루프를 다시 세운다. 미리 잡아 두면 origin 허용이 바뀐 뒤에도
         * 낡은 판정을 계속 쓴다.
         */
        sessionToken={readToken()}
        voiceItems={voiceItems}
        totalItems={progress.total}
        onReady={onAnalysisReady ?? noop}
        /*
         * 브리지가 없는 브라우저 단독 실행에서는 재녹음 버튼을 그리지 않는다. 눌러도 네이티브
         * 녹음 화면이 열리지 않아 아무 일도 일어나지 않는 버튼이 된다 (어휘 문항의 개발용
         * 통로와 같은 판정).
         */
        onRetake={window.AccenturyBridge === undefined ? undefined : retake}
        refreshNonce={resultNonce}
        fetchImpl={fetchImpl}
      />
    )
  }

  return (
    <main className="item-screen">
      {/*
        진행바와 "3 / 10" 표기. 값이 1부터 시작하는 건 의도다 — 첫 문항을 0/10으로 보이면
        아직 시작도 안 한 느낌이라 이탈이 는다 (ux-ui.md §3 Goal-Gradient, endowed progress).
      */}
      <ProgressIndicator current={progress.current} total={progress.total} />
      {/*
        본문은 유형이 정한다. 두 화면 모두 문항이 바뀔 때 새로 마운트되도록 itemId를 key로 준다 —
        음성 화면은 그 마운트가 곧 "네이티브에 전환을 알리는" 시점이다.
      */}
      {current.type === 'VOICE' ? (
        <VoiceItemScreen
          key={current.itemId}
          item={current}
          itemNumber={progress.current}
          totalItems={progress.total}
          webRecording={{ upload: uploadWebRecording, capture }}
          onWebUploaded={receiveResult}
        />
      ) : (
        <VocabularyItemScreen
          key={current.itemId}
          item={current}
          /*
           * 답안은 실행 환경과 무관하게 **항상 서버로 나간다**. 브리지가 없을 때 저장된 셈
           * 치고 진행만 밀던 개발용 통로가 있었는데, 웹 단독 실행(KAN-31)이 정식 경로가 된
           * 지금은 그 통로가 곧 "어휘 5문항이 채점에서 통째로 빠지는" 길이다.
           *
           * 토큰이 없는 실행에서는 제출이 실패하고 화면에 오류가 보인다 — 조용히 건너뛰어
           * 채점에서 빠지는 것보다 실패가 보이는 편이 낫다.
           */
          submitAnswer={(choiceId, idempotencyKey) =>
            submitVocabAnswer(
              {
                apiBase,
                sessionId,
                itemId: current.itemId,
                choiceId,
                sessionToken: readToken(),
                idempotencyKey,
              },
              fetchImpl,
            )
          }
          onSubmitted={() => submit(current.itemId)}
        />
      )}
    </main>
  )
}

/**
 * `onAnalysisReady`를 주지 않은 호출자용. 결과가 확정돼도 화면을 옮기지 않는다 —
 * 브라우저 단독 개발과 이 화면만 띄우는 테스트가 그 경로다.
 */
function noop(): void {}

/** Error가 아닌 값이 던져질 수 있으므로(문자열 reject 등) 문구 추출을 한 곳에 모은다 */
function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
