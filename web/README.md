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

## 배포 (KAN-127)

`.github/workflows/web-deploy.yml`이 한다. `web/**` 변경이 Dev에 병합되면 staging
(`staging.accentury.app`), Release에 병합되면 prod(`accentury.app`)로 올라간다. 빌드는
`npm ci && npm run build` 그대로이고 환경별 값을 주입하지 않는다. 화면과 API가 같은 출처라
`API_BASE`가 배포 빌드에서 빈 문자열(상대 경로)이기 때문이다 (`src/App.tsx`).

- 캐시: `index.html`만 `no-cache`, 나머지 전부 `public, max-age=31536000, immutable`. 그래서
  `public/`에 해시 없는 파일을 두지 않는다. 폰트는 `src/assets/fonts/`에서 상대 경로로 import해
  Vite가 해시를 붙인다. 이름이 고정인 파일이 꼭 필요하면 워크플로의 캐시 규칙부터 고친다.
- 업로드 순서: 해시 자산을 먼저, `index.html`을 마지막에. 중간에 실패하면 이전 `index.html`이
  이전 자산을 그대로 가리킨다. 이전 자산은 지우지 않는다 (`sync --delete` 없음).
- 환경을 새로 지은 뒤 첫 배포나 웹 변경 없는 재배포는 Actions의 "Web Deploy"를
  workflow_dispatch로 환경을 골라 돌린다.
- 대상 버킷, 배포 ID, IAM 역할은 GitHub environment 변수다 (infra/README.md "GitHub 설정").
- `VITE_PLAY_STORE_URL` 같은 빌드 시점 값은 아직 주입하지 않는다 (코드 기본값). 필요해지면
  environment 변수로 넘긴다 - 두 환경이 같은 값이면 저장소 변수로 둔다.
