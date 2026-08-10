import { IntroScreen } from './intro/IntroScreen'
import { isBridgeCompatible } from './bridge/bridge'

/**
 * 진입 분기 — 화면을 그리기 전에 브리지 버전 스큐부터 판정한다 (webview-layer.md §5).
 * 판단 주체는 웹이다: 앱이 URL로 실어 보낸 브리지 버전이 이 빌드가 요구하는 최소 버전보다
 * 낮으면(또는 없으면) 기능 화면 대신 업데이트 안내를 렌더한다. 구버전 앱은 손대지 않아도 된다.
 */
export default function App() {
  if (!isBridgeCompatible(window.location.search)) {
    return <UpdateRequiredScreen />
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
