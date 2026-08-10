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

## 배포 (미확정)

산출물 `dist/` → CloudFront 원격 전용 서빙. 배포 주체·도메인은 미확정(webview-layer.md §10) — 확정 전까지 release 빌드의 `WEB_URL`은 placeholder다.
