import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { installGa4Tag } from './analytics/ga4'
import { isStandaloneWeb } from './bridge/bridge'
import './tokens.css'
import './ui/components.css'

/*
 * GA4 태그는 **웹 단독 실행에만** 설치한다 (KAN-33). 앱 안(WebView)에서는 브리지를 건너
 * 네이티브 Firebase가 보내므로, 여기서도 태그를 깔면 같은 사건이 두 번 세어진다
 * (`analytics/track.ts`의 분기표).
 *
 * 렌더보다 먼저 부르는 이유: 인트로의 유입 계측(`referral_opened`)이 첫 렌더의 이펙트에서
 * 나간다. 큐가 그 전에 서 있어야 첫 이벤트가 버려지지 않는다 — 태그 스크립트 자체는 늦게
 * 도착해도 되지만(큐에 쌓였다가 처리된다) 큐가 없으면 그 호출은 갈 곳이 없다.
 *
 * 측정 ID가 없는 빌드에서는 아무 일도 하지 않는다 (`installGa4Tag`).
 */
if (isStandaloneWeb(window.location.search)) installGa4Tag()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
