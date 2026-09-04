# accentury web

앱 WebView가 원격 로드하는 웹 레이어다 (webview-layer.md 설계). 현재 범위는 테스트 인트로(KAN-97).

## 로컬 개발

```bash
cd web
npm install
npm run dev        # --host 포함 — 에뮬레이터가 10.0.2.2:5173으로 접근한다
```

- **에뮬레이터 + debug 빌드**: 앱 debug `WEB_URL`이 `http://10.0.2.2:5173`을 가리키므로, dev 서버만 켜면 앱 첫 화면에 이 웹이 뜬다.
- **브라우저 단독 확인**: `http://localhost:5173/`을 그냥 열면 된다. 브리지 객체도 `?bridge=` 파라미터도 없으면
  웹 단독 실행으로 판정해(KAN-31, `bridge.ts`의 `isStandaloneWeb`) 스큐 게이트를 건너뛰고 인트로가 뜬다.
  앱 안(WebView)을 흉내 내려면 `?bridge=1`을 붙인다 — 그때는 스큐 판정이 도로 켜진다.
- **문항 진행 화면(KAN-99) 확인**: `http://localhost:5173/?bridge=1&screen=test&testVersion=gn-2026.08.1`.
  `?screen=test`는 문항 진행 화면의 정식 진입 쿼리다(KAN-100, `App.tsx`) — 앱도 웹 단독 실행도
  같은 쿼리로 들어오고, 이 URL을 직접 여는 것은 그 화면만 따로 보는 개발 통로다. 정의는
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

**토큰을 손으로 심던 절차는 없어졌다.** KAN-31이 웹 단독 세션을 붙이면서 인트로 [시작하기]가
마이크 권한 → 목소리 점검 → `POST /v0/sessions` → 문항 화면까지 스스로 진행한다. 그냥
`http://localhost:5173/`을 열고 [시작하기]를 누르면 된다. 세션 토큰은 그 흐름이
`sessionStorage`의 `accentury.webSession` 키에 넣고(`session/webSession.ts`) 화면들이 거기서 읽는다 —
탭과 함께 사라져야 하는 값이라 `localStorage`가 아니고, URL 쿼리로도 넘기지 않는다
(히스토리·액세스 로그·Referer에 남는다).

마이크 권한은 브라우저가 직접 묻고, `localhost`는 보안 컨텍스트라 `getUserMedia`가 동작한다
(에뮬레이터의 `http://10.0.2.2:5173`은 보안 컨텍스트가 아니라 마이크를 열 수 없다 — 앱에서는
네이티브 녹음 화면이 그 자리를 맡으므로 문제가 되지 않는다).

녹음 패널의 억양 곡선 두 레인(가이드·내 억양)은 앱과 **같은 YIN·EMA 규칙**으로 그린다.
상수와 그 값을 고른 근거는 앱 쪽 [docs/wiki/pitch-curve.md](../docs/wiki/pitch-curve.md)가 정본이고,
한쪽을 고치면 다른 쪽도 같이 고쳐야 한다.

### 브라우저 E2E (KAN-181)

vitest가 못 보는 것을 실제 Chromium에서 본다 — `getUserMedia`·`AudioContext`·`AudioWorklet`,
그리고 문서를 통째로 다시 읽는 화면 전환. 가짜 마이크로 앱 디버그 빌드와 **같은 WAV**를
흘려 넣어(`app/src/debug/assets/fake_mic.wav`) 인트로부터 결과 화면까지 걸어간다.
왜 이렇게 만들었는지는 [docs/wiki/browser-e2e.md](../docs/wiki/browser-e2e.md)가 정본이고,
여기는 돌리는 법만 적는다.

| 스펙 | 확인하는 것 | 전제 |
|---|---|---|
| `e2e/smoke.spec.ts` | 인트로 숫자 카드 → 시작 게이트 통과 → `?screen=test` | 스택 |
| `e2e/full-run.spec.ts` | 10문항 완주 → 분석 대기 → 결과 등급·점수 | 스택, `E2E_FAIL_ITEM` **없음** |
| `e2e/retake.spec.ts` | 음성 문항 분석 실패 → 막다른 길 안내 | 스택, `E2E_FAIL_ITEM` **있음** |
| `e2e/mic-blocked.spec.ts` | 마이크 거부·점유 안내, 세션 미생성 | 없음 (BE를 부르지 않는다) |

#### 스택 띄우기

`docker compose up -d --build --wait` 한 줄이면 끝나는 것이 정석이다 — Docker Desktop과
CI 러너에는 BuildKit이 있어 그대로 된다. **`docker buildx`가 없는 Docker 환경**(Colima 기본
설치처럼 buildx 플러그인이 빠진 엔진)에서는 backend 이미지가 `RUN --mount=type=cache`에서
멈추므로, 그때만 아래처럼 backend를 gradlew로 우회한다. `docker buildx version`이 에러면
이 경우다.

```bash
# 1) DB — application.yml 기본값이 localhost:5432라 루트 compose(5433)가 아니라 이쪽이다
cd backend && docker compose up -d && cd ..

# 2) AI — 루트 compose의 ai는 포트를 공개하지 않는다(BE만 부르는 사설망 서비스).
#    backend를 호스트에서 띄우므로 오버레이로 8000만 연다. 이 파일을 레포에 두지 않는 이유는
#    docker-compose.override.yml이 있으면 compose가 언제나 자동으로 읽어, 사설망 전제를
#    쓰는 사람 모두에게서 조용히 뒤집기 때문이다.
cat > /tmp/ai-ports.yml <<'YAML'
services:
  ai:
    ports: ["127.0.0.1:8000:8000"]
YAML
docker compose -f docker-compose.yml -f /tmp/ai-ports.yml up -d --wait ai

# 3) Backend — 시스템 java가 없으면 Android Studio의 JBR을 쓴다.
#    툴체인 JDK 25는 foojay resolver가 알아서 받는다.
cd backend && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew bootRun --args="--accentury.cors.allowed-origins=http://localhost:5173,http://127.0.0.1:5173 --accentury.analysis.ai-base-url=http://127.0.0.1:8000"

# 4) 확인 — 둘 다 200이어야 한다
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/health
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8000/internal/v0/health
```

개발 서버는 따로 띄우지 않아도 된다 — Playwright가 **E2E 전용 포트 5174**에 `npm run dev`를
직접 켜고 끝나면 내린다 (`playwright.config.ts`의 `webServer`). 평소 개발용 5173은 건드리지
않으므로 켜 둔 채 돌려도 된다. 5173을 재사용하지 않는 이유는 그 서버가 `VITE_API_BASE=`
없이 떴을 수 있어 브라우저가 에뮬레이터용 주소(10.0.2.2:8080)를 찾다 죽기 때문이다.

#### 돌리기

```bash
cd web
npm ci
npx playwright install chromium   # 처음 한 번

npm run test:e2e                  # 전부 (retake는 skip)
npm run test:e2e -- --headed      # 브라우저를 눈으로 보면서
npm run test:e2e:ui               # 스펙을 골라 되감아 보는 UI
npm run test:e2e -- smoke         # 파일 이름으로 좁히기

# 실패하면 trace가 남는다 — 요청·콘솔·DOM 스냅샷이 다 들어 있다
npx playwright show-trace test-results/<실패한-스펙>/trace.zip
```

#### 실패 갈래 돌리기

`retake.spec.ts`는 AI 스텁이 특정 문항을 반드시 실패시키는 스택에서만 의미가 있고,
`full-run.spec.ts`는 반대로 그 설정이 없어야 통과한다. 한 스택이 둘을 동시에 만족할 수 없어
**대칭 스킵**으로 갈랐다 — 스택을 갈아 끼우고 두 번 돌린다.

```bash
# 레포 루트에서. ai만 갈아 끼운다 — --no-deps라 DB·BE는 그대로 살아 있다.
E2E_FAIL_ITEM=v3 docker compose -f docker-compose.yml -f /tmp/ai-ports.yml up -d --no-deps --wait ai
(cd web && E2E_FAIL_ITEM=v3 npm run test:e2e)   # full-run이 skip되고 retake가 깨어난다

# 복구 — 값을 비워서 다시 띄운다. 설정을 기동 시 1회만 읽으므로 재기동이 필요하다.
# (위를 서브셸로 감싼 이유: cd가 남으면 이 줄이 web/에서 돌아 compose 파일을 못 찾는다.)
E2E_FAIL_ITEM= docker compose -f docker-compose.yml -f /tmp/ai-ports.yml up -d --no-deps --wait ai
```

`E2E_FAIL_ITEM`은 반드시 **인라인**으로 준다. `export`하면 위 복구 명령까지 같은 값을
물려받아 복구가 성립하지 않는다 (`scripts/e2e-smoke-local.sh`가 같은 이유로 인라인을 쓴다).

#### 배포 환경 겨누기

`E2E_BASE_URL`이 있으면 개발 서버를 띄우지 않고 그 주소를 그대로 연다.

```bash
E2E_BASE_URL=https://staging.accentury.app npm run test:e2e
```

- `E2E_FAIL_ITEM`을 줄 수 없으므로 `retake`는 언제나 skip된다.
- staging AI가 실모델이면 완주 시간이 로컬(21초)보다 길어진다. 결과 등급·점수 검증은 값을
  범위로만 보므로 그대로 유효하다. **미검증** — 아직 staging으로 돌려 보지 않았다.

#### 알아 둘 것

- **레이트 리밋**: 세션 생성·업로드가 각각 IP당 분당 30이다(`application.yml`). `--repeat-each=3`
  한 판이 세션 6 + 업로드 15라 안전하지만, 그 위로 올리면 업로드 쪽이 먼저 걸려 429가 난다.
- **브라우저 단독에는 재녹음 복구가 없다.** 브리지가 없으면 `onRetake`를 넘기지 않으므로
  (`TestFlowScreen.tsx`) 분석이 실패한 문항을 다시 녹음할 통로가 없다. `retake.spec.ts`가
  완주가 아니라 막다른 길 안내를 확인하는 이유다. 복구 흐름은 앱(WebView + 네이티브) 몫이라
  브라우저 E2E의 범위 밖이다.
- **CI**: `.github/workflows/test.yml`의 `web-e2e` job이 두 스택 상태를 차례로 돈다. 실패하면
  trace·리포트가 `playwright-evidence` 아티팩트로 7일 남는다. 아직 required check는 아니다.

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
