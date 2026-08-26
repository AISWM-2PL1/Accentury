# accentury web

앱 WebView가 원격 로드하는 웹 레이어다 (webview-layer.md 설계). 현재 범위는 테스트 인트로(KAN-97).

## 로컬 개발

```bash
cd web
npm install
npm run dev        # --host 포함 — 에뮬레이터가 10.0.2.2:5173으로 접근한다
```

- **에뮬레이터 + debug 빌드**: 앱 debug `WEB_URL`이 `http://10.0.2.2:5173`을 가리키므로, dev 서버만 켜면 앱 첫 화면에 이 웹이 뜬다.
- **브라우저 단독 확인**: 브리지 버전 파라미터가 없으면 "앱 업데이트가 필요해요"가 뜬다(스큐 판정 정상 동작). `http://localhost:5173/?bridge=1`로 열 것.
- **문항 진행 화면(KAN-99) 확인**: `http://localhost:5173/?bridge=1&screen=test&testVersion=gn-2026.08.1`.
  `?screen=test`는 KAN-100이 정식 화면 전환을 붙이기 전까지의 개발용 통로다. 정의는
  `VITE_API_BASE`(기본 `http://10.0.2.2:8080`)에서 `GET /v0/tests/{testVersion}`으로 받아온다 —
  브라우저에서 볼 때는 `VITE_API_BASE=http://localhost:8080`으로 띄울 것.
- 실기기 테스트는 앱 `WEB_URL`을 호스트 머신 IP로 바꾸고 `network_security_config.xml`에 해당 IP 평문 허용을 추가해야 한다.

## 검증

```bash
npm test           # vitest
npm run build      # tsc --noEmit + vite build
```

### 브라우저 단독 녹음 확인 (KAN-56)

브리지가 없는 브라우저에서는 음성 문항이 웹 녹음 패널로 열린다([녹음] → [정지] → [재녹음]/[다음]).
[다음]이 실제로 서버에 올라가려면 세션 토큰이 필요한데, 웹 단독 세션(KAN-31)이 붙기 전까지는
개발자 콘솔에서 직접 심는다 — **DEV 빌드에서만 읽는 값이다.**

```js
// POST /v0/sessions 응답의 sessionToken
localStorage.setItem('accentury.devSessionToken', '<token>')
```

토큰을 URL 쿼리로 넘기지 않는 것이 규칙이다: 히스토리·액세스 로그·Referer에 남는다.

그런 다음 `http://localhost:5173/?bridge=1&screen=test&testVersion=gn-2026.08.1&sessionId=<sessionId>`로
연다. 마이크 권한은 브라우저가 직접 묻고, `localhost`는 보안 컨텍스트라 `getUserMedia`가 동작한다
(에뮬레이터의 `http://10.0.2.2:5173`은 보안 컨텍스트가 아니라 마이크를 열 수 없다 — 앱에서는
네이티브 녹음 화면이 그 자리를 맡으므로 문제가 되지 않는다).

녹음 패널의 억양 곡선 두 레인(가이드·내 억양)은 앱과 **같은 YIN·EMA 규칙**으로 그린다.
상수와 그 값을 고른 근거는 앱 쪽 [docs/wiki/pitch-curve.md](../docs/wiki/pitch-curve.md)가 정본이고,
한쪽을 고치면 다른 쪽도 같이 고쳐야 한다.

## 배포 (미확정)

산출물 `dist/` → CloudFront 원격 전용 서빙. 배포 주체·도메인은 미확정(webview-layer.md §10) — 확정 전까지 release 빌드의 `WEB_URL`은 placeholder다.
