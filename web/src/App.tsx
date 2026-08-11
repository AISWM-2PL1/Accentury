import { IntroScreen } from './intro/IntroScreen'
import { isBridgeCompatible } from './bridge/bridge'
import { TestFlowScreen } from './progress/TestFlowScreen'

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
      />
    )
  }

  return <IntroScreen />
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
        gap: '12px',
        padding: '16px',
        textAlign: 'center',
      }}
    >
      <h1 style={{ fontSize: '20px', fontWeight: 600, margin: 0 }}>앱 업데이트가 필요해요</h1>
      <p style={{ fontSize: '14px', margin: 0 }}>
        새로운 테스트를 이용하려면 스토어에서 최신 버전으로 업데이트해 주세요.
      </p>
    </main>
  )
}
