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
   * `?screen=test&testVersion=...` — 문항 진행 화면(KAN-99)으로 들어가는 개발·검증용 통로다.
   * 정식 진입은 인트로 [시작하기] → 네이티브 권한 게이트 → 문항 진행으로 이어져야 하는데,
   * 그 화면 전환 결선이 KAN-100 몫이라 아직 없다. 그때까지 이 화면을 사람이 직접 열어 볼
   * 방법이 필요해서 둔 분기이고, KAN-100이 정식 결선을 붙이면 지운다.
   * testVersion을 쿼리로 받는 것도 임시다 — 정본은 세션 생성(KAN-9) 응답이고, 그 값이
   * 웹까지 오는 경로가 아직 미확정이다(fetchTestDefinition 헤더 주석의 열린 질문).
   */
  const params = new URLSearchParams(window.location.search)
  if (params.get('screen') === 'test') {
    return <TestFlowScreen apiBase={API_BASE} testVersion={params.get('testVersion') ?? ''} />
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
