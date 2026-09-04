import { useCallback, useEffect, useRef, useState } from 'react'
import { overallBucket } from './analytics/events'
import { clearTestId, ensureTestId } from './analytics/testId'
import { track } from './analytics/track'
import type { CaptureFactory } from './audio'
import { detectStorePlatform } from './audio/storeLink'
import { IntroScreen } from './intro/IntroScreen'
import { START_FAILED_MESSAGE, STORAGE_UNAVAILABLE_MESSAGE } from './intro/introText'
import { getSessionToken, isBridgeCompatible, isStandaloneWeb } from './bridge/bridge'
import { buildIntroUrl, buildResultUrl, buildTestUrl } from './navigation/entryUrl'
import { TestFlowScreen } from './progress/TestFlowScreen'
import { ResultScreen } from './result/ResultScreen'
import { useRetest } from './result/useRetest'
import { readCampaignToken, sanitizeCampaignToken } from './session/campaign'
import { shareResult } from './share/shareResult'
import { VoiceCheckScreen } from './voicecheck/VoiceCheckScreen'
import {
  createWebSession,
  getWebSessionToken,
  isWebSessionStorageAvailable,
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

/**
 * 문서를 옮기는 지점 (테스트 주입용). 기본값은 `location.href` 대입인데, jsdom은 그 대입을
 * 구현하지 않아 "어디로 보내려 했는지"를 확인할 수 없다. 주입 지점을 하나 두면 화면 전환을
 * 실제 이동 없이 검사할 수 있다 (진입 쿼리 조립 자체는 `navigation/entryUrl`이 소유한다).
 *
 * `replace`는 히스토리에 새 항목을 쌓는 대신 지금 항목을 덮어쓴다. 뒤로 가기가 돌아가면
 * **안 되는** 전환에만 쓴다 ([goToResult] 참고).
 */
export type Navigate = (url: string, options?: { replace?: boolean }) => void

export interface AppProps {
  navigate?: Navigate
  /**
   * 목소리 점검 화면에 주입할 캡처 (테스트용). [navigate]와 같은 자리의 주입 지점이다 —
   * jsdom에는 `AudioContext`가 없어 실물 캡처로는 시작 게이트 전 구간을 한 번에 볼 수 없다.
   */
  voiceCheckCapture?: CaptureFactory
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
export default function App({ navigate = assignHref, voiceCheckCapture }: AppProps = {}) {
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
    /*
     * 이 응시의 계측 상관 키를 확보한다 (KAN-33 AC 1). 세션 id를 아는 자리가 진입 분기라
     * 여기서 부른다 — [track]은 세션을 모르고, 알게 만들면 계측 창구가 진입 쿼리 규칙까지
     * 알아야 한다 (`analytics/testId.ts`).
     *
     * 렌더 중에 부르는 것이 걸리지만, 같은 세션이면 저장된 값을 그대로 돌려주는 멱등 호출이고
     * 이펙트로 미루면 첫 문항 노출(`item_shown`)이 키 없이 나간다 — 자식의 이펙트가 부모의
     * 이펙트보다 먼저 돌기 때문이다.
     */
    startedTest(params.get('sessionId') ?? '', trackedCampaign())
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
        /*
         * 목소리 점검이 잰 중심 음높이 (KAN-31 4단계). 웹 단독 실행에만 있다 — 앱 안에서는
         * 네이티브가 자기 점검 화면에서 재서 자기 녹음 화면에 물려주므로 이 WebView가 알 필요가
         * 없고, 값을 주면 두 곳이 각자 잰 중심을 들고 다니게 된다.
         *
         * 렌더 시점에 한 번 읽는다. 토큰과 달리 요청마다 다시 읽을 이유가 없다 — 이 값은 세션이
         * 만들어질 때 함께 확정돼 세션이 바뀌기 전에는 변하지 않는다.
         */
        userCurveCenterHz={standalone ? (loadWebSession()?.userCurveCenterHz ?? null) : null}
        onAnalysisReady={() => {
          // 완주 계측 (퍼널). 결과가 실제로 나온 자리라 "끝까지 갔다"를 여기서만 확실히
          // 말할 수 있다 — 마지막 문항 제출은 아직 분석 실패로 갈 수 있다.
          //
          // 실행을 가리지 않는다 (KAN-33). 앱 안에서는 [track]이 브리지로 넘겨 네이티브
          // Firebase가 앱 스트림에 싣는다 — 완주율은 앱에서도 봐야 하는 값이다.
          track({ name: 'test_completed', campaign: trackedCampaign() })
          goToResult(params.get('sessionId') ?? '', navigate)
        }}
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
    /*
     * 문항 화면과 같은 이유로 키를 확보한다 — 결과 도착 계측(`result_viewed`·`tier_assigned`)이
     * 같은 응시로 묶여야 «시작한 사람 중 몇이 결과까지 왔나»를 셀 수 있다.
     *
     * 여기서는 시작을 세지 않는다. 결과 화면만 따로 여는 경로(개발 통로, 공유된 결과 URL)에서
     * 키가 처음 발급될 수 있는데, 그건 응시를 시작한 것이 아니다.
     */
    ensureTestId(params.get('sessionId') ?? '')
    return (
      <ResultRoute sessionId={params.get('sessionId') ?? ''} standalone={standalone} navigate={navigate} />
    )
  }

  return <IntroRoute standalone={standalone} navigate={navigate} voiceCheckCapture={voiceCheckCapture} />
}

/**
 * 인트로 결선 지점 (KAN-31 3단계). 유입 계측을 이 자리가 소유한다.
 *
 * [ResultRoute]와 같은 이유로 컴포넌트를 하나 세웠다 — 유입 계측은 "렌더될 때 한 번"이라
 * 훅인데, [App]은 스큐 판정에서 조기 반환하므로 그 뒤에 훅을 놓을 수 없다.
 */
function IntroRoute({
  standalone,
  navigate,
  voiceCheckCapture,
}: {
  standalone: boolean
  navigate: Navigate
  voiceCheckCapture?: CaptureFactory
}) {
  /*
   * 유입 계측 (퍼널 1번째 지점). **문서당 한 번**이면 충분하다 — 화면 전환이 전부 같은
   * 문서를 다시 로드하는 방식이라(`goToResult`·`goToIntro`) 이 컴포넌트의 마운트 한 번이
   * 곧 페이지 조회 한 번이다. 렌더마다 부르면 리렌더가 그대로 중복 노출로 세어진다
   * (KAN-33 AC "중복 화면 노출로 이벤트가 과다 발생하지 않는다").
   *
   * 개발 빌드의 StrictMode는 이펙트를 일부러 두 번 돌린다 — 배포 빌드에서는 그 재실행이
   * 사라지므로 실사용 집계에는 영향이 없다.
   */
  useEffect(() => {
    /*
     * 직전 응시의 상관 키를 버린다 (KAN-33). 인트로는 어느 응시에도 속하지 않는 화면이고,
     * 여기 왔다는 것은 앞의 응시가 끝났다는 뜻이다 — 남겨 두면 이 유입이 직전 응시의 키를
     * 달고 나가 순서 분석에서 A의 목록 끝에 B의 시작이 붙는다 (`analytics/testId.ts`).
     */
    clearTestId()

    /*
     * 실행을 가리지 않는다 (KAN-33). 인트로를 그리는 것이 앱에서도 이 WebView라 사건이 같고,
     * 앱 안에서는 [track]이 브리지로 넘겨 네이티브 Firebase가 앱 스트림에 싣는다 — 보내는
     * 경로만 갈릴 뿐 세는 사건은 하나다.
     *
     * 앱의 정상 실행은 `campaign`이 null이고, 앱 링크(KAN-32)로 들어온 실행만 값이 붙는다.
     */
    track({ name: 'referral_opened', campaign: trackedCampaign() })
  }, [])

  /*
   * 마이크 권한을 받았는가 (KAN-31 4단계). 인트로가 세션 생성으로 곧장 넘어가지 않고 이 값만
   * 올리는 것이 시작 게이트의 새 계약이다 — 그 다음 화면을 고르는 것은 부모의 일이다.
   *
   * URL 화면이 아니라 이 문서의 상태인 이유는 권한이다. 다른 전환처럼 문서를 다시 로드하면
   * 방금 받은 마이크 권한을 브라우저에 따라 다시 물어야 하는데, 그 프롬프트는 사용자 제스처
   * 없이는 뜨지 않는다. 결과 화면에서 [다시 테스트하기]로 돌아오면 이 문서가 새로 로드되므로
   * 이 값도 false로 돌아간다 — 재응시는 마이크를 새로 열게 되므로 점검도 다시 한다(앱과 같다).
   */
  const [micGranted, setMicGranted] = useState(false)
  /** 점검은 통과했는데 세션 생성이 막혔다. 값이 곧 사용자에게 보일 문구다 */
  const [startFailure, setStartFailure] = useState<string | null>(null)
  /*
   * 세션 생성 진행 중 표시. setState는 비동기라 [다음] 연타의 두 번째 클릭이 화면 상태가
   * 바뀌기 전에 닿을 수 있는데, 그 순간 판정에 쓸 값은 ref다 (`WebVoiceRecorder`의 업로드
   * 잠금과 같은 이유). 세션이 둘 생기면 하나는 아무도 응시하지 않는 고아로 남는다.
   */
  const startingRef = useRef(false)

  /**
   * 목소리 점검이 끝났다 — 잰 중심을 들고 세션을 만든다 (퍼널 2번째 지점은 그 안에 있다).
   *
   * 실패는 화면을 갈아치우지 않고 점검 화면에 문구로 붙인다. 인트로로 되돌리면 방금 통과한
   * 점검을 한 번 더 하게 되고, [다음]은 그대로 재시도 버튼으로 쓸 수 있다.
   */
  const finishVoiceCheck = useCallback(
    (centerHz: number) => {
      if (startingRef.current) return
      startingRef.current = true
      setStartFailure(null)
      startStandaloneTest(navigate, centerHz)
        .catch((error: unknown) => {
          setStartFailure(error instanceof Error ? error.message : START_FAILED_MESSAGE)
        })
        .finally(() => {
          startingRef.current = false
        })
    },
    [navigate],
  )

  /*
   * 권한을 받은 뒤의 시작 게이트 두 번째 칸 (KAN-31 4단계). 앱의 순서를 그대로 옮겼다:
   * 권한 → 목소리 점검 → 세션 생성. 세션을 점검 뒤로 미루는 이유는 점검이 네트워크를 쓰지
   * 않아 실패할 구석이 없기 때문이다 — 앞에 두면 이미 발급된 세션을 든 채 점검에 붙들리는
   * 구간이 생긴다 (`VoiceCheckScreen` 헤더).
   */
  if (standalone && micGranted) {
    return (
      <VoiceCheckScreen
        onDone={finishVoiceCheck}
        startFailure={startFailure}
        capture={voiceCheckCapture}
      />
    )
  }

  /*
   * 웹 단독 실행에서만 [시작하기]에 갈 곳이 있다. 앱 안에서는 이 자리가 비어 있어야 한다 —
   * 네이티브가 권한 게이트부터 세션 생성까지 자기 흐름으로 진행하므로, 웹이 세션을 하나 더
   * 만들면 같은 사용자에게 세션이 둘 생긴다.
   *
   * 인트로가 알리는 것은 "마이크 권한을 받았다"까지다. 예전에는 이 콜백이 곧 세션 생성이라
   * 그 실패가 인트로의 오류 문구로 떴는데, 이제 그 실패는 점검 화면이 받는다 —
   * 인트로의 오류 문구는 권한 요청 자체가 던진 경우에 그대로 남아 있다 (`IntroScreen`).
   */
  return <IntroScreen onWebStart={standalone ? () => setMicGranted(true) : undefined} />
}

/**
 * 계측에 실을 유입 코드. 세션 생성과 **같은 규칙으로 거른 값만** 싣는다 (`campaign.ts`).
 *
 * 공유 링크는 메신저를 여러 번 거치며 잘리거나 트래킹 파라미터가 덧붙는 경로라, 걸러 두지
 * 않으면 집계 축에 한 번 쓰이고 마는 쓰레기 값이 쌓인다 — 서버에 보내지 않기로 한 값을
 * 계측에는 보낸다면 두 집계가 다른 모수를 세게 된다.
 */
function trackedCampaign(): string | null {
  return sanitizeCampaignToken(readCampaignToken(window.location.search))
}

/**
 * 문항 화면 진입을 응시의 시작으로 센다 (퍼널 2번째 지점).
 *
 * 상관 키가 **처음 발급되는 순간**이 곧 이 세션의 첫 진입이라, 그 신호 하나로 두 실행을 함께
 * 덮는다 (`analytics/testId.ts`). 같은 세션으로 화면을 다시 열면(백그라운드 복귀, 리로드)
 * 키가 이미 있으므로 다시 세지 않는다.
 *
 * 렌더 중에 부르는 것이 걸리지만 멱등이고, 이펙트로 미루면 첫 문항 노출(`item_shown`)이 키
 * 없이 나간다 — 자식의 이펙트가 부모의 이펙트보다 먼저 돌기 때문이다.
 */
function startedTest(sessionId: string, campaign: string | null): void {
  if (ensureTestId(sessionId)?.issued === true) {
    track({ name: 'referral_test_started', campaign })
  }
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
 * 중심 음높이는 세션과 함께 저장한다 — 목소리 점검이 방금 잰 값이고(KAN-31 4단계), 문항
 * 화면은 화면 전환(문서 리로드)을 건너온 뒤에 그 값을 읽는다.
 *
 * @param userCurveCenterHz 목소리 점검이 잰 이 화자의 중심 음높이 (Hz)
 * @throws Error 사용자에게 보일 문구를 담은 오류 ([startFailureMessage] 참고)
 */
async function startStandaloneTest(navigate: Navigate, userCurveCenterHz: number): Promise<void> {
  const search = window.location.search

  /*
   * 저장소부터 잰다 — **네트워크보다 먼저**다 (KAN-31). 순서에 이유가 둘 있다.
   *
   * 하나는 부작용이다. 세션을 만든 뒤에 막히면 서버에는 아무도 응시하지 않을 세션이 남고,
   * IP 분당 제한(§2.5) 한 칸도 함께 쓰인다 — 어차피 진행할 수 없는 사람에게 그 둘을 물릴
   * 이유가 없다.
   *
   * 다른 하나는 이 판정이 곧 흐름의 전제라는 점이다. 이 화면 전환은 문서를 다시 로드하므로
   * 토큰이 저장소를 거쳐야만 다음 문서에 닿는다. 저장소가 없으면 문항 화면의 요청이 전부
   * 토큰 없이 나가고, 사용자는 이유도 모른 채 진행 화면에 갇힌다.
   */
  if (!isWebSessionStorageAvailable()) throw new Error(STORAGE_UNAVAILABLE_MESSAGE)

  let session
  try {
    session = await createWebSession(API_BASE, {
      campaignToken: readCampaignToken(search),
      previousToken: loadWebSession()?.sessionToken,
    })
  } catch (error: unknown) {
    throw new Error(startFailureMessage(error))
  }

  // 서버가 준 세션에 웹이 잰 중심을 얹어 한 덩어리로 저장한다 (`webSession.ts`).
  saveWebSession({ ...session, userCurveCenterHz })

  /*
   * 저장한 값을 되읽어 확인한다. 앞의 판정이 이미 걸렀어야 하는 경우지만, 40바이트짜리
   * 시험값은 통과시키면서 실제 세션에서 할당량에 걸리는 저장소가 있을 수 있다 — 그때
   * [saveWebSession]은 조용히 삼키므로, 확인하지 않으면 토큰 없이 문항 화면으로 넘어간다.
   */
  if (loadWebSession()?.sessionToken !== session.sessionToken) {
    throw new Error(STORAGE_UNAVAILABLE_MESSAGE)
  }

  /*
   * 시작 계측은 여기서 하지 않는다 — 다음 문서의 문항 화면 진입이 [startedTest]로 센다.
   *
   * 세션을 만드는 주체가 실행마다 다르기 때문이다: 웹 단독은 이 함수가, 앱은 네이티브가
   * 만든다(KAN-9). 이 자리에서 세면 **앱 응시는 시작이 영영 세어지지 않는다** — 에뮬레이터
   * 확인에서 실제로 그 구멍이 드러났다(2026-09-05). 두 실행이 공유하는 자리는 "문항 화면에
   * 처음 도달했다"이고, 거기서 세면 규칙이 하나로 남는다.
   *
   * 429나 네트워크 실패로 시작하지 못한 사람이 세어지지 않는다는 성질도 그대로다 — 그런
   * 실패는 이 함수가 던져서 화면을 옮기지 않으므로 문항 화면에 도달하지 못한다.
   */

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
  navigate: Navigate
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

  /*
   * [앱 다운로드]는 웹 단독 실행에만 있다 (KAN-31). UA 판별을 여기서 하는 이유는 화면이
   * `navigator`를 읽지 않게 하기 위해서다 — 어느 스토어인지는 환경 조회이고, 결과 화면이
   * 할 일은 받은 값을 그리는 것이다 (마이크 게이트가 [MicBlockedScreen]에 같은 방식으로
   * 판별 결과만 내려보낸다).
   *
   * 값 하나로 CTA와 그 계측이 같이 갈린다 — 각자 `standalone`을 따로 보면 "버튼은 그렸는데
   * 계측만 빠진" 조합이 생긴다.
   */
  const storePlatform = standalone
    ? detectStorePlatform(navigator.userAgent, navigator.maxTouchPoints)
    : undefined

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
      /*
       * [친구에게 공유하기] (KAN-30 1·3단계). 통로 선택은 `share/shareResult`가 소유한다 —
       * 앱 안이면 브리지, 브라우저면 공유 시트, 둘 다 없으면 링크 복사다.
       *
       * 공유 클릭 계측 (FR-SH-06). 공유를 **먼저** 보내고 그 반환값으로 센다 — 어느 통로로
       * 갔는지는 통로를 고른 뒤에야 알 수 있는 값이고, 계측 때문에 공유가 한 틱이라도 늦으면
       * 안 된다 ([shareResult]도 [track]도 던지지 않으므로 순서만 남는 이야기다).
       *
       * **실행을 가리지 않는다.** 예전에는 앱 안의 같은 탭을 네이티브가 `share_tapped`로 따로
       * 셌는데, 그러면 한 사람의 같은 행동이 플랫폼에 따라 다른 이름으로 쌓여 공유 퍼널이 둘로
       * 갈렸다 (Codex 검증 지적, AC 8). 지금은 **클릭은 여기 하나가 세고** 네이티브는 통로가
       * 실제로 열린 것(`share_launched`)만 센다 — 둘의 차이로 "눌렀는데 아무 데도 못 간 비율"은
       * 그대로 나오면서 이름이 하나 줄었다.
       *
       * 앱 안에서는 `channel`이 `bridge`다. 그 값이 곧 "네이티브가 통로를 고르러 갔다"는 뜻이고,
       * 어느 통로였는지는 네이티브의 `share_launched`가 말한다.
       */
      onShare={(result) => {
        const channel = shareResult(result.share)
        track({ name: 'share_clicked', campaign: trackedCampaign(), channel })
      }}
      retest={retest}
      /*
       * 결과 도착 계측 (KAN-33). 두 이벤트가 같은 순간에 나가지만 세는 것이 다르다 —
       * `result_viewed`는 퍼널의 마지막 지점(공유 링크를 탄 사람이 여기까지 왔다)이고,
       * `tier_assigned`는 등급 분포 편향(KAN-21)을 보는 계기판이다. 하나로 합치면 유입
       * 코드가 없는 앱 응시가 등급 집계에서 빠지거나, 등급이 유입 퍼널의 축이 된다.
       *
       * 점수 원값은 싣지 않는다 — `overallBucket`이 10점 단위로 뭉갠 값만 나간다 (FR-AN-09).
       */
      onResultLoaded={(result) => {
        track({ name: 'result_viewed', campaign: trackedCampaign() })
        track({
          name: 'tier_assigned',
          tier_code: result.tier.code,
          score_version: result.scoreVersion,
          overall_bucket: overallBucket(result.scores.overall),
        })
      }}
      storePlatform={storePlatform}
      /*
       * 다운로드 탭 계측 (퍼널 4번째 지점, KAN-31 3단계). 링크의 이동은 이 콜백과 무관하게
       * 일어나므로 계측이 무엇을 하든 설치 전환이 끊기지 않는다 ([track]은 던지지 않는다).
       *
       * 마지막 지점을 클릭으로 세는 것이 한계다 — 스토어에 실제로 도달했는지는 웹이 알 수
       * 없고, 설치까지는 스토어 콘솔의 유입 통계가 답한다.
       */
      onDownloadClick={
        storePlatform === undefined
          ? undefined
          : () =>
              track({
                name: 'app_download_clicked',
                campaign: trackedCampaign(),
                platform: storePlatform,
              })
      }
    />
  )
}

/**
 * 분석이 끝났다 — 결과 화면으로 넘긴다 (KAN-14 → KAN-29).
 *
 * 남기고 지우는 규칙은 [buildResultUrl]이 소유한다. 같은 문서를 다시 로드하는 이유는
 * [goToIntro]와 같다 — 진행 화면이 들고 있던 상태(폴링 타이머·스냅샷 참조)를 확실히
 * 버리기 위해서다.
 *
 * ## 이 전환만 히스토리를 덮어쓴다
 *
 * 다른 전환은 그대로 쌓는다(인트로→문항, 결과→인트로). 문항 화면에서 뒤로 가 인트로로
 * 돌아가는 것은 정상 행동이고, 인트로에서 한 번 더 뒤로 가면 사이트를 떠난다.
 *
 * 여기만 다른 이유는 **끝난 문서로는 돌아갈 데가 없기 때문**이다. 결과 화면에서 뒤로 가면
 * `?screen=test` 문서가 되살아나 대기 화면이 다시 폴링하고, READY를 보고 결과로 또 넘어온다 —
 * 뒤로 가기가 제자리를 맴돌아 아무 데도 못 가는 버튼이 된다. 되살아난 화면에서 할 수 있는
 * 일도 없다: 완료된 세션은 이후 제출을 409로 거절한다.
 *
 * 앱 안(WebView)에서는 이 변화가 눈에 띄지 않는다 — 뒤로 가기는 네이티브가 자기 규칙으로
 * 다루므로 히스토리에 한 칸이 더 있고 없고가 화면 흐름을 바꾸지 않는다.
 */
function goToResult(sessionId: string, navigate: Navigate): void {
  navigate(window.location.pathname + buildResultUrl(window.location.search, sessionId), {
    replace: true,
  })
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
function goToIntro(navigate: Navigate): void {
  // 같은 문서를 다시 로드한다 — 결과 화면이 들고 있던 상태를 확실히 버리기 위해서다.
  navigate(window.location.pathname + buildIntroUrl(window.location.search))
}

/**
 * [AppProps.navigate]의 기본값. 대입 한 줄을 함수로 감싸 두면 화면 전환 지점이 전부
 * `navigate` 하나를 거치게 되어, 테스트가 갈아끼울 자리가 한 곳으로 모인다.
 *
 * 두 갈래가 하는 일은 같다 — 문서를 옮긴다. 히스토리에 지금 항목을 남기느냐 덮어쓰느냐만
 * 다르다.
 */
function assignHref(url: string, options?: { replace?: boolean }): void {
  if (options?.replace === true) {
    window.location.replace(url)
    return
  }
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
