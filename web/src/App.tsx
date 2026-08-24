import { IntroScreen } from './intro/IntroScreen'
import { getSessionToken, isBridgeCompatible } from './bridge/bridge'
import { TestFlowScreen } from './progress/TestFlowScreen'
import { ResultScreen } from './result/ResultScreen'
import { useRetest } from './result/useRetest'
import type { TestResultView } from './result/testResult'

/**
 * 백엔드 오리진. 에뮬레이터에서 호스트의 백엔드를 가리키는 주소를 개발 기본값으로 둔다
 * (앱의 `DEV_BASE_URL`과 같은 값). 배포 도메인은 환경변수로 주입한다.
 */
const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? 'http://10.0.2.2:8080'

/**
 * 진입 분기 — 화면을 그리기 전에 브리지 버전 스큐부터 판정한다 (webview-layer.md §5).
 * 판단 주체는 웹이다: 앱이 URL로 실어 보낸 브리지 버전이 이 빌드가 요구하는 최소 버전보다
 * 낮으면(또는 없으면) 기능 화면 대신 업데이트 안내를 렌더한다. 구버전 앱은 손대지 않아도 된다.
 */
export default function App() {
  if (!isBridgeCompatible(window.location.search)) {
    return <UpdateRequiredScreen />
  }

  /*
   * `?screen=test&testVersion=...&sessionId=...` — 문항 진행 화면의 **정식 진입 쿼리**다.
   * 인트로 [시작하기] → 네이티브 마이크 권한 게이트(KAN-98)를 통과한 뒤, 네이티브가 이 쿼리를
   * 붙여(기존 bridge·app 파라미터에 더해) WebView를 다시 로드하는 것이 정상 경로다.
   * 그 조립은 네이티브 결선(KAN-100 Stage 4) 몫이고, 웹 쪽 계약은 여기까지다.
   * 브라우저 단독 개발에서도 같은 URL을 손으로 열면 같은 화면에 들어간다 — 개발용 통로를
   * 따로 두지 않는 이유다(경로가 갈리면 개발에서 통과한 것이 앱에서 통과한다는 보장이 없다).
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
        onAnalysisReady={() => goToResult(params.get('sessionId') ?? '')}
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
    return <ResultRoute sessionId={params.get('sessionId') ?? ''} />
  }

  return <IntroScreen />
}

/**
 * 결과 화면 결선 지점 (KAN-34 3단계). 재응시 브리지 왕복을 이 자리가 소유한다.
 *
 * [App]에 직접 두지 않고 컴포넌트를 하나 세운 이유가 둘이다. 하나는 훅 규칙 — 재응시는
 * 수신자 설치와 카운트다운이라 훅인데, [App]은 스큐 판정에서 조기 반환하므로 그 뒤에 훅을
 * 놓을 수 없다. 다른 하나는 §8 지침 — 수신은 부모가 하고 화면에는 값으로 내려보낸다.
 */
function ResultRoute({ sessionId }: { sessionId: string }) {
  // 브리지로 갈 수 없는 환경에서는 예전 동작(인트로 복귀)으로 내려간다.
  const retest = useRetest(goToIntro)

  return (
    <ResultScreen
      apiBase={API_BASE}
      sessionId={sessionId}
      /*
       * 토큰은 쿼리가 아니라 브리지에서 읽는다 — URL에 실으면 WebView 히스토리와 로그에
       * 세션 토큰이 남는다. 브리지가 없으면(브라우저 단독) 빈 문자열이라 조회 전에 막히고
       * 사용자용 문구가 뜬다.
       *
       * 렌더 시점에 읽는 이유: 결과 조회는 이 화면에 들어올 때 한 번뿐이라, 진행 화면처럼
       * 제출 때마다 다시 읽을 필요가 없다.
       */
      sessionToken={getSessionToken() ?? ''}
      onShare={shareResult}
      retest={retest}
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
 * `screen`과 `sessionId`만 갈아끼우고 나머지 진입 파라미터는 남긴다. `bridge`·`app`이
 * 빠지면 스큐 판정(§5)이 구버전 앱으로 보고 업데이트 안내를 띄운다 — 방금 테스트를 끝낸
 * 사용자가 "앱을 업데이트하세요"를 만나는 셈이 된다 ([goToIntro]와 같은 이유).
 *
 * `testVersion`은 지운다. 결과 화면이 읽지 않는 값이고, 남겨 두면 이 URL을 그대로 다시 연
 * 사람이 끝난 세션의 정의 버전을 물고 다니게 된다.
 *
 * 같은 문서를 다시 로드하는 이유도 [goToIntro]와 같다 — 진행 화면이 들고 있던 상태(폴링
 * 타이머·스냅샷 참조)를 확실히 버리기 위해서다.
 */
function goToResult(sessionId: string): void {
  const params = new URLSearchParams(window.location.search)
  params.set('screen', 'result')
  params.set('sessionId', sessionId)
  params.delete('testVersion')
  window.location.href = `${window.location.pathname}?${params.toString()}`
}

/**
 * [다시 테스트하기]의 **폴백** — 진입 쿼리에서 화면 지정만 걷어내고 인트로로 되돌린다.
 *
 * KAN-34 결선 이후 정식 경로는 [useRetest]의 브리지 호출이다. 이 함수는 브리지로 갈 수 없을
 * 때만 남는다: 브라우저 단독 실행, 그리고 `startRetest`가 없는 계약 버전 1 구버전 앱이다
 * (메서드 추가는 버전을 올리지 않으므로 §5 스큐 게이트가 그 앱을 막지 않는다). 지우지 않는
 * 이유가 그것이다 — 지우면 구버전 앱에서 버튼이 아무 일도 하지 않는 버튼이 된다.
 *
 * `bridge`·`app` 파라미터는 남긴다. 그 둘이 없으면 스큐 판정(§5)이 구버전 앱으로 보고
 * 업데이트 안내를 띄운다 — 재응시를 눌렀는데 "앱을 업데이트하세요"가 뜨는 셈이 된다.
 *
 * 새 익명 세션 생성은 여기서 하지 않는다. 세션은 네이티브가 만들고(KAN-9), 인트로의
 * [시작하기]가 권한 게이트를 거쳐 그 흐름을 다시 태운다 — 웹이 세션을 만들면 앱과 웹에
 * 세션 생성 경로가 둘 생긴다.
 */
function goToIntro(): void {
  const params = new URLSearchParams(window.location.search)
  params.delete('screen')
  params.delete('sessionId')
  params.delete('testVersion')

  const query = params.toString()
  // 같은 문서를 다시 로드한다 — 결과 화면이 들고 있던 상태를 확실히 버리기 위해서다.
  window.location.href = query === '' ? window.location.pathname : `${window.location.pathname}?${query}`
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
