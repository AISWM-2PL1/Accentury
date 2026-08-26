import { useCallback } from 'react'
import { detectStorePlatform } from './audio/storeLink'
import { IntroScreen } from './intro/IntroScreen'
import { START_FAILED_MESSAGE } from './intro/introText'
import { getSessionToken, isBridgeCompatible, isStandaloneWeb } from './bridge/bridge'
import { buildIntroUrl, buildResultUrl, buildTestUrl } from './navigation/entryUrl'
import { TestFlowScreen } from './progress/TestFlowScreen'
import { ResultScreen } from './result/ResultScreen'
import { useRetest } from './result/useRetest'
import type { TestResultView } from './result/testResult'
import { readCampaignToken } from './session/campaign'
import {
  createWebSession,
  getWebSessionToken,
  loadWebSession,
  saveWebSession,
  WebSessionError,
} from './session/webSession'

/**
 * 백엔드 오리진. 배포에서는 화면과 API가 같은 도메인이라(CloudFront 단일 출처, KAN-126) 빈
 * 문자열, 즉 상대 경로다. 빌드 산출물이 환경(staging, prod)을 몰라도 되는 근거다 (KAN-127).
 * 개발 서버(vite dev)에서만 에뮬레이터가 호스트의 백엔드를 가리키는 주소를 기본값으로 둔다
 * (앱의 `DEV_BASE_URL`과 같은 값). `VITE_API_BASE`가 있으면 어느 쪽이든 그 값이 이긴다.
 */
const API_BASE =
  (import.meta.env.VITE_API_BASE as string | undefined) ?? (import.meta.env.DEV ? 'http://10.0.2.2:8080' : '')

export interface AppProps {
  /**
   * 문서를 옮기는 지점 (테스트 주입용). 기본값은 `location.href` 대입인데, jsdom은 그 대입을
   * 구현하지 않아 "어디로 보내려 했는지"를 확인할 수 없다. 주입 지점을 하나 두면 화면 전환을
   * 실제 이동 없이 검사할 수 있다 (진입 쿼리 조립 자체는 `navigation/entryUrl`이 소유한다).
   */
  navigate?: (url: string) => void
}

/**
 * 진입 분기 — 화면을 그리기 전에 **어떤 실행인지**부터 판정한다.
 *
 * 1. 웹 단독 실행(앱이 아닌 모바일 브라우저, KAN-31)이면 스큐 게이트를 건너뛴다. 브리지 버전
 *    협상은 앱을 상대로 하는 규칙이라, 앱이 아닌 실행에 적용하면 공유 링크를 연 사람 전원이
 *    "앱을 업데이트하세요"를 만난다.
 * 2. 그 밖에는 지금까지와 같다 (webview-layer.md §5). 판단 주체는 웹이다: 앱이 URL로 실어
 *    보낸 브리지 버전이 이 빌드가 요구하는 최소 버전보다 낮으면(또는 없으면) 기능 화면 대신
 *    업데이트 안내를 렌더한다. 구버전 앱은 손대지 않아도 된다.
 */
export default function App({ navigate = assignHref }: AppProps = {}) {
  const standalone = isStandaloneWeb(window.location.search)

  if (!standalone && !isBridgeCompatible(window.location.search)) {
    return <UpdateRequiredScreen />
  }

  /*
   * `?screen=test&testVersion=...&sessionId=...` — 문항 진행 화면의 **정식 진입 쿼리**다.
   * 두 실행이 같은 쿼리로 들어온다. 앱에서는 인트로 [시작하기] → 네이티브 마이크 권한
   * 게이트(KAN-98) 뒤 네이티브가 이 쿼리를 붙여(기존 bridge·app 파라미터에 더해) WebView를
   * 다시 로드하고(KAN-100 Stage 4), 웹 단독 실행에서는 [startStandaloneTest]가 세션을 만든 뒤
   * 같은 쿼리를 조립한다. 경로가 갈리지 않으므로 한쪽에서 통과한 것이 다른 쪽에서도 통과한다.
   */
  const params = new URLSearchParams(window.location.search)
  if (params.get('screen') === 'test') {
    return (
      <TestFlowScreen
        apiBase={API_BASE}
        testVersion={params.get('testVersion') ?? ''}
        /*
         * 세션 클라이언트(KAN-9) 결선 전까지는 네이티브가 sessionId를 모를 수 있다. 그때는 빈
         * 문자열이 내려가고 진행 스냅샷이 세션별로 나뉘지 않는다 — 과도기의 알려진 한계다.
         */
        sessionId={params.get('sessionId') ?? ''}
        /*
         * 웹 단독 실행에서만 토큰 출처를 넘긴다. 앱에서는 undefined를 줘야 진행 화면이 기존대로
         * 브리지에서 읽는다 — 둘 다 주면 어느 쪽이 정본인지가 화면마다 갈린다.
         */
        webSessionToken={standalone ? getWebSessionToken : undefined}
        onAnalysisReady={() => goToResult(params.get('sessionId') ?? '', navigate)}
      />
    )
  }

  /*
   * `?screen=result&sessionId=...` — 결과 화면(KAN-29)의 진입 쿼리다. 진행 화면과 같은 계약을
   * 쓰는 이유는 같다: 앱과 브라우저가 같은 URL로 같은 화면에 들어가야 개발에서 통과한 것이
   * 앱에서도 통과한다.
   *
   * 이 자리로 사용자를 보내는 것은 분석 대기 화면(KAN-14)이다 — 마지막 문항 제출 뒤
   * `/complete`가 READY를 줄 때까지 기다렸다가 [goToResult]로 넘긴다. 이 URL을 직접 여는
   * 경로도 그대로 살아 있다: 결과 화면만 따로 확인하는 개발 통로다.
   */
  if (params.get('screen') === 'result') {
    return (
      <ResultRoute sessionId={params.get('sessionId') ?? ''} standalone={standalone} navigate={navigate} />
    )
  }

  /*
   * 웹 단독 실행에서만 [시작하기]에 갈 곳이 있다. 앱 안에서는 이 자리가 비어 있어야 한다 —
   * 네이티브가 권한 게이트부터 세션 생성까지 자기 흐름으로 진행하므로, 웹이 세션을 하나 더
   * 만들면 같은 사용자에게 세션이 둘 생긴다.
   */
  return <IntroScreen onWebStart={standalone ? () => startStandaloneTest(navigate) : undefined} />
}

/**
 * 웹 단독 실행의 [시작하기] — 세션을 만들고 문항 화면으로 넘긴다 (KAN-31).
 *
 * 이전 세션의 토큰을 함께 보낸다. 저장된 세션이 있다는 것은 이 탭에서 이미 한 번 응시했다는
 * 뜻이고, 그때는 이 호출이 곧 재응시다 — 서버가 옛 세션을 폐기하고 새 세션을 준다 (§3.1).
 * 만료된 토큰은 조용히 무시되므로 만료 판정을 여기서 하지 않는다.
 *
 * 실패는 던져서 인트로 화면에 문구로 남긴다. 화면을 옮기지 않는 것이 중요하다 — 세션이 없으면
 * 문항 화면은 401만 잔뜩 만들고, 사용자는 시작도 못 한 채 진행 화면에 갇힌다.
 *
 * @throws Error 사용자에게 보일 문구를 담은 오류 ([startFailureMessage] 참고)
 */
async function startStandaloneTest(navigate: (url: string) => void): Promise<void> {
  const search = window.location.search

  let session
  try {
    session = await createWebSession(API_BASE, {
      campaignToken: readCampaignToken(search),
      previousToken: loadWebSession()?.sessionToken,
    })
  } catch (error: unknown) {
    throw new Error(startFailureMessage(error))
  }

  saveWebSession(session)
  // 진입 경로(`/t`)와 나머지 쿼리(`c` 등)는 그대로 두고 화면 지정만 얹는다.
  navigate(
    window.location.pathname +
      buildTestUrl(search, { testVersion: session.testVersion, sessionId: session.sessionId }),
  )
}

/**
 * 시작 실패를 사용자용 한 줄로 바꾼다.
 *
 * 429에는 남은 대기 시간을 적는다 — "잠시 후"보다 "30초 후"가 실제로 기다릴 수 있는 정보이고,
 * 결과 화면의 재응시 대기 문구와 같은 표기를 쓴다. 그 밖에는 서버 봉투의 한국어 문구를 그대로
 * 쓰고, 문구가 없는 실패만 기본 안내로 덮는다.
 */
function startFailureMessage(error: unknown): string {
  if (error instanceof WebSessionError && error.retryAfterMs !== null) {
    return `${Math.max(1, Math.ceil(error.retryAfterMs / 1000))}초 후 다시 시도할 수 있어요`
  }
  if (error instanceof Error && error.message.trim() !== '') return error.message
  return START_FAILED_MESSAGE
}

/**
 * 결과 화면 결선 지점 (KAN-34 3단계). 재응시 브리지 왕복을 이 자리가 소유한다.
 *
 * [App]에 직접 두지 않고 컴포넌트를 하나 세운 이유가 둘이다. 하나는 훅 규칙 — 재응시는
 * 수신자 설치와 카운트다운이라 훅인데, [App]은 스큐 판정에서 조기 반환하므로 그 뒤에 훅을
 * 놓을 수 없다. 다른 하나는 §8 지침 — 수신은 부모가 하고 화면에는 값으로 내려보낸다.
 */
function ResultRoute({
  sessionId,
  standalone,
  navigate,
}: {
  sessionId: string
  standalone: boolean
  navigate: (url: string) => void
}) {
  /*
   * 브리지로 갈 수 없는 환경에서는 예전 동작(인트로 복귀)으로 내려간다.
   *
   * **웹 단독 실행의 재응시가 정확히 이 길이다** (KAN-31 2단계). 브리지 객체가 없으므로
   * [startRetest]가 false를 돌려주고 [useRetest]가 곧바로 이 폴백을 부른다 — 브라우저에서는
   * 네이티브 왕복도, 그 왕복의 실패 회신도 존재하지 않으므로 잠금·카운트다운 UI가 뜰 일이
   * 없다. 실행별 갈래를 따로 두지 않는 이유가 그것이다.
   *
   * 저장된 세션은 **지우지 않는다.** 이전 세션 토큰이 다음 [시작하기]의 Bearer로 나가야 서버가
   * 그 세션을 폐기하는데(§3.1), 여기서 지우면 보낼 토큰이 사라져 옛 세션이 만료될 때까지
   * 고아로 남는다. 낡은 토큰이 남는 걱정도 없다 — 새 세션을 저장하는 순간 같은 키를 덮어쓴다.
   * 30분이 지나 토큰이 만료된 뒤 눌러도 마찬가지다: 서버가 만료된 Bearer를 조용히 무시하고
   * 새 세션을 주므로 클라이언트가 만료를 판정할 일이 없다.
   */
  const backToIntro = useCallback(() => goToIntro(navigate), [navigate])
  const retest = useRetest(backToIntro)

  return (
    <ResultScreen
      apiBase={API_BASE}
      sessionId={sessionId}
      /*
       * 토큰은 실행에 따라 출처가 갈린다. 앱에서는 브리지, 웹 단독 실행에서는 이 탭의
       * 세션 저장소다 (KAN-31). 공통점이 요점이다 — **어느 쪽도 URL에서 읽지 않는다.**
       * 쿼리에 실으면 세션 토큰이 히스토리·액세스 로그·Referer에 남는다.
       *
       * 둘 다 없으면 빈 문자열이라 조회 전에 막히고 사용자용 문구가 뜬다.
       *
       * 렌더 시점에 읽는 이유: 결과 조회는 이 화면에 들어올 때 한 번뿐이라, 진행 화면처럼
       * 제출 때마다 다시 읽을 필요가 없다.
       */
      sessionToken={standalone ? getWebSessionToken() : (getSessionToken() ?? '')}
      onShare={shareResult}
      retest={retest}
      /*
       * [앱 다운로드]는 웹 단독 실행에만 있다 (KAN-31). UA 판별을 여기서 하는 이유는 화면이
       * `navigator`를 읽지 않게 하기 위해서다 — 어느 스토어인지는 환경 조회이고, 결과 화면이
       * 할 일은 받은 값을 그리는 것이다 (마이크 게이트가 [MicBlockedScreen]에 같은 방식으로
       * 판별 결과만 내려보낸다).
       */
      storePlatform={
        standalone ? detectStorePlatform(navigator.userAgent, navigator.maxTouchPoints) : undefined
      }
    />
  )
}

/**
 * [친구에게 공유하기] — 지금 웹이 혼자 할 수 있는 만큼만 한다.
 *
 * **완성은 KAN-30이다.** 카카오 피드 템플릿(v2) 공유가 정식 경로이고, 이 함수는 그때
 * 카카오 SDK 호출로 교체된다. 그 전까지 비워 두지 않는 이유는 버튼이 이미 화면에 있기
 * 때문이다 — 눌러도 아무 일이 없는 버튼보다, 되는 환경에서 실제로 되는 편이 낫다.
 *
 * `navigator.share`는 **보안 컨텍스트(HTTPS)에서만** 존재한다. 개발 WebView가 로드하는
 * `http://10.0.2.2:5173`에는 없다 — `crypto.randomUUID`가 같은 이유로 없었던 것과 같은
 * 상황이다(2026-08-18 에뮬레이터 실증). 그래서 없을 때를 정상 경로로 다룬다.
 *
 * 공유 payload에 점수를 싣지 않는다 (KAN-30 요구). 등급별 문구와 캠페인 URL은 서버가 준
 * 값 그대로다 — 수신자는 남의 결과를 보는 게 아니라 자기 테스트를 새로 응시한다.
 */
function shareResult(result: TestResultView): void {
  const { text, webTestUrl } = result.share

  if (typeof navigator.share === 'function') {
    // 취소는 실패가 아니다 — 사용자가 공유 시트를 닫으면 reject가 오는데, 그걸 오류로
    // 다루면 정상 행동에 오류 로그가 쌓인다. 결과 화면은 그대로 두는 것이 맞다 (KAN-30 AC).
    navigator.share({ text, url: webTestUrl }).catch(() => {})
    return
  }

  // 공유 시트가 없는 환경(개발 http, 구형 WebView). 사용자에게 알릴 길이 이 화면에 아직
  // 없으므로 진단만 남긴다 — 안내 문구와 폴백 UI는 KAN-30이 자기 화면 요구와 함께 정한다.
  console.warn('[share] navigator.share가 없는 환경입니다 (KAN-30 카카오 공유 결선 전)', {
    text,
    webTestUrl,
  })
}

/**
 * 분석이 끝났다 — 결과 화면으로 넘긴다 (KAN-14 → KAN-29).
 *
 * 남기고 지우는 규칙은 [buildResultUrl]이 소유한다. 같은 문서를 다시 로드하는 이유는
 * [goToIntro]와 같다 — 진행 화면이 들고 있던 상태(폴링 타이머·스냅샷 참조)를 확실히
 * 버리기 위해서다.
 */
function goToResult(sessionId: string, navigate: (url: string) => void): void {
  navigate(window.location.pathname + buildResultUrl(window.location.search, sessionId))
}

/**
 * [다시 테스트하기]의 **폴백** — 진입 쿼리에서 화면 지정만 걷어내고 인트로로 되돌린다.
 *
 * KAN-34 결선 이후 정식 경로는 [useRetest]의 브리지 호출이다. 이 함수는 브리지로 갈 수 없을
 * 때만 남는다: 브라우저 단독 실행, 그리고 `startRetest`가 없는 계약 버전 1 구버전 앱이다
 * (메서드 추가는 버전을 올리지 않으므로 §5 스큐 게이트가 그 앱을 막지 않는다). 지우지 않는
 * 이유가 그것이다 — 지우면 구버전 앱에서 버튼이 아무 일도 하지 않는 버튼이 된다.
 *
 * 남기고 지우는 규칙은 [buildIntroUrl]이 소유한다.
 *
 * 새 세션은 여기서 만들지 않는다. 이 함수는 인트로로 되돌리기만 하고, 세션은 그 화면의
 * [시작하기]가 각 실행의 방식대로 만든다 — 앱은 네이티브가(KAN-9), 웹 단독 실행은
 * [startStandaloneTest]가. 여기서 만들면 화면 전환과 세션 생성이 한 덩어리가 되어, 되돌아온
 * 사용자가 시작도 누르기 전에 세션이 하나 생긴다.
 */
function goToIntro(navigate: (url: string) => void): void {
  // 같은 문서를 다시 로드한다 — 결과 화면이 들고 있던 상태를 확실히 버리기 위해서다.
  navigate(window.location.pathname + buildIntroUrl(window.location.search))
}

/**
 * [AppProps.navigate]의 기본값. 대입 한 줄을 함수로 감싸 두면 화면 전환 지점이 전부
 * `navigate` 하나를 거치게 되어, 테스트가 갈아끼울 자리가 한 곳으로 모인다.
 */
function assignHref(url: string): void {
  window.location.href = url
}

/** 신버전 웹 + 구버전 앱 조합에서만 보이는 화면. 비난 없는 카피 톤(ux-ui.md)을 지킨다. */
function UpdateRequiredScreen() {
  return (
    <main
      style={{
        minHeight: '100dvh',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 'var(--space-3)',
        padding: 'var(--space-4)',
        textAlign: 'center',
      }}
    >
      <h1 className="type-title-sm">앱 업데이트가 필요해요</h1>
      <p className="type-label">새로운 테스트를 이용하려면 스토어에서 최신 버전으로 업데이트해 주세요.</p>
    </main>
  )
}
