# WebView 브리지 계약 (정본)

앱 안에서 화면을 그리는 것은 웹(`web/`)이고, 마이크·녹음·공유·계측처럼 브라우저가 할 수 없는
일은 네이티브가 한다. 그 둘이 주고받는 창구가 브리지다. **이 문서가 계약의 정본이다** — 메서드를
더하거나 payload를 바꾸는 사람은 여기 표를 먼저 고친다.

설계 배경과 결정 이력(왜 원격 전용인가, 왜 CloudFront인가, 스탠드얼론 웹 판정 규칙 등)은
`~/accentury/docs/wiki/webview-layer.md`에 있다. 그쪽은 이력 장부이고, **계약의 현재 모습은
이 문서가 말한다.** 두 문서가 어긋나면 이 문서가 맞다.

## 1. 계약 버전 규칙

| 변경 | 버전 |
|---|---|
| 메서드·필드·수신 슬롯 **추가** | 그대로 (하위호환) |
| 메서드·필드 **삭제**, 의미·payload 모양 **변경** | +1 |

현재 버전은 **1**이다. 정본 상수는 세 곳에 있고 값이 같아야 한다:

| 플랫폼 | 위치 |
|---|---|
| 웹 (요구 버전) | `web/src/bridge/bridge.ts` `REQUIRED_BRIDGE_VERSION` |
| Android (보유 버전) | `app/src/main/java/com/accentury/app/web/WebConfig.kt` `BRIDGE_CONTRACT_VERSION` |
| iOS (보유 버전) | `ios/AccenturyCore/Sources/AccenturyCore/Web/WebConfig.swift` `bridgeContractVersion` |

**스큐 판정의 주체는 웹이다.** 앱은 로드 URL에 `?bridge=<보유 버전>`을 실어 보내기만 하고,
그 값이 요구 버전보다 낮으면 웹이 업데이트 안내 화면을 띄운다. 그래서 구버전 앱을 고치지 않고도
판정이 선다.

메서드 추가가 버전을 올리지 않는다는 규칙의 대가는 **웹 래퍼가 없는 메서드를 스스로 처리해야
한다**는 것이다. `bridge.ts`의 래퍼는 전부 `typeof bridge?.foo !== 'function'`이면 `false`를
돌려주고, 호출자가 폴백으로 내려간다.

## 2. web → native (`window.AccenturyBridge`)

| 메서드 | 티켓 | 하는 일 | 없을 때 웹의 폴백 |
|---|---|---|---|
| `getContractVersion(): number` | KAN-97 | 앱이 보유한 계약 버전. 동기 반환 | 스큐 협상 이전 앱으로 본다 |
| `getSessionToken(): string` | KAN-13 | 어휘 답안 제출의 `Authorization` 헤더에 실을 토큰. 동기 반환. origin이 allowlist 밖이면 빈 문자열 | 빈 값은 `null`로 정규화 → 제출 불가 |
| `requestMicPermission()` | KAN-98 | 네이티브 마이크 권한 게이트를 연다 | 웹이 직접 `getUserMedia` |
| `startVoiceItem(payloadJson)` | KAN-100 | 네이티브 녹음 화면으로 전환. payload = `{itemId, prompt, itemNumber, totalItems, maxDurationMs, guideF0}` | 웹 녹음기(`WebVoiceRecorder`) |
| `startRetest()` | KAN-34 | 이전 세션·결과를 버리고 새 세션으로 인트로 리로드. **인자 없음** — 폐기할 토큰은 네이티브가 들고 있다 | 쿼리를 걷어내고 인트로로 (`goToIntro`) |
| `shareResult(payloadJson)` | KAN-30 | 카카오 피드 템플릿으로 공유. payload = `{imageUrl, text, webTestUrl}` — 점수·세션 id·등급 코드 없음 | `navigator.share` → 링크 복사 |
| `logEvent(name, paramsJson)` | KAN-33 | 계측 이벤트를 네이티브 Firebase로. 앱 안 이벤트를 웹 gtag로 보내면 앱 사용자가 웹 트래픽으로 세어진다 | gtag 경로 |
| `openExternalUrl(url)` | KAN-177 | 앱 **밖** 브라우저로 링크를 연다 (§4) | `<a>`의 기본 동작 |

`@JavascriptInterface`·`postMessage`는 문자열만 주고받으므로 구조체는 JSON으로 직렬화해 넘긴다.

## 3. native → web (`window.AccenturyWeb`)

| 슬롯 | 티켓 | 하는 일 |
|---|---|---|
| `onItemResult(payloadJson)` | KAN-100 | 네이티브 녹음이 끝난 문항 결과 |
| `onRetestFailed(payloadJson)` | KAN-34 | 재응시 실패. **성공은 오지 않는다** — 성공하면 페이지가 리로드된다 |

슬롯 단위로 갈아끼운다. 객체를 통째로 교체하면 나중에 설치한 수신자가 먼저 설치된 것을 지운다.

## 4. `openExternalUrl` — 외부 링크 (KAN-177)

인트로 하단의 개인정보처리방침 링크가 이 창구를 쓴다. 앱에 설정 화면이 없어 **방침으로 가는
길이 이것뿐이고**, Google Play는 스토어 등록 정보뿐 아니라 앱 안에서도 방침을 볼 수 있기를
요구한다.

### 왜 웹이 직접 못 여는가

두 길이 다 막혀 있다.

- **WebView 안에서 이동** — allowlist(§7)에 걸린다. 디버그·시뮬레이터 빌드의 origin은 로컬
  Vite(`http://10.0.2.2:5173`)라 우리 도메인의 방침 문서가 로드 차단 대상이다. 통과하는
  prod에서도 문제가 남는다: 인트로가 사라지는데 **돌아올 길이 없다.** Android는 시스템
  뒤로가기를 WebView back으로 넘기는 코드가 없어 액티비티가 닫히고, iOS에는 시스템 뒤로가기
  자체가 없다.
- **`target="_blank"`** — 앱 안에서 아무 일도 하지 않는다. Android는
  `setSupportMultipleWindows`가 꺼져 있고, iOS `WKWebView`에는 `uiDelegate`가 없어 새 웹뷰를
  만들 주체가 없다. 오류도 나지 않아서 "눌렀는데 아무 일 없음"으로만 보인다.

그래서 **여는 주체를 네이티브로 옮긴다.** 앱 화면 위에 시트를 덮으므로 닫으면 인트로가 그대로
남는다.

| 플랫폼 | 여는 방법 | 파일 |
|---|---|---|
| Android | Chrome Custom Tabs (`androidx.browser`) | `app/src/main/java/com/accentury/app/web/ExternalBrowser.kt` |
| iOS | `SFSafariViewController` | `ios/Accentury/Web/ExternalBrowser.swift` |
| 스탠드얼론 웹 | `<a target="_blank">` (브리지가 없으므로 기본 동작) | `web/src/legal/PrivacyNotice.tsx` |

시스템 브라우저를 `ACTION_VIEW`·`UIApplication.open`으로 띄우지 않는 이유는 앱을 떠나기
때문이다 — 사용자가 앱 전환기를 거쳐 돌아와야 한다. 툴바 색은 두 플랫폼 모두 팔레트 정본
(Compose `LightBackground` / `Papercut.cream`)에서 가져온다. 시트 색이 갈리면 앱을 벗어난 것처럼
읽히는데, 실제로는 앱 위에 덮인 창이라 그 인상이 사실과 다르다.

### 네이티브가 URL을 다시 보는 이유

**여는 주소를 정하는 쪽이 웹이다.** WebView에 실린 스크립트가 부르는 메서드라, 넘어온 값을
그대로 열면 앱이 아무 주소나 여는 창구가 된다. 그래서 호스트 목록으로 다시 좁힌다.

| 함수 | 판정 단위 | 대상 |
|---|---|---|
| `isAllowedWebUrl` (§7) | origin (스킴+호스트+포트) | WebView가 **로드할** URL |
| `externalUrlToOpen` (KAN-177) | 호스트 | 앱 밖으로 **내보낼** URL |

호스트 단위인 이유: 포트·경로는 문서마다 다를 수 있어서 origin 일치를 요구하면 방침 문서를
옮기는 날 링크가 조용히 죽는다. 허용 호스트는 `accentury.app`·`staging.accentury.app`이고,
접미사 비교가 아니라 완전 일치다(`accentury.app.evil.example.com`은 거절).

거절 규칙:

- **https만.** 방침 문서는 어느 환경에서도 HTTPS로 선다. `javascript:`·`intent:`·앱 스킴은
  host가 없어 자동으로 걸린다
- **`user@host`·역슬래시·공백·제어문자 거절.** 이유는 **파서가 둘**이기 때문이다 — 검사에 쓰는
  파서(`java.net.URI` / `URLComponents`)와 실제로 여는 파서(`android.net.Uri` / `URL`)가 그
  문자들에서 host를 다르게 읽을 수 있어, 검사한 호스트와 열리는 호스트가 갈릴 여지가 남는다.
  방침 URL에는 애초에 없는 문자다

검증에 실패하거나 origin이 allowlist 밖이면 **조용히 아무 일도 하지 않고** Crashlytics에 흔적만
남긴다(`bridge_parse_failed: openExternalUrl`). 웹은 오류를 되돌려 줄 상대가 아니고, 인트로에서
대신 할 수 있는 일도 없다.

### 정책 URL은 한 곳에만 있다

`web/src/legal/privacyPolicy.ts`의 `DEFAULT_PRIVACY_POLICY_URL`이 정본이고, 스테이징은
`VITE_PRIVACY_POLICY_URL`로 덮어쓴다.

```
prod     https://accentury.app/privacy.html
staging  https://staging.accentury.app/privacy.html
```

**`.html`은 취향이 아니라 계약이다.** CloudFront SPA 재작성 함수가 마지막 경로 조각에 점이 없으면
`/index.html`로 돌리므로, `/privacy`는 **200을 주면서** 정책 문서가 아니라 앱 화면을 띄운다
(KAN-133, 2026-09-04 staging 실측). 오류가 남지 않는 실패라 눌러 보기 전에는 아무도 모른다 —
`privacyPolicy.test.ts`가 이 한 줄을 붙든다.

문서 본문과 게시 경로는 `privacy-policy.md`를 본다.

## 5. 보안 규칙 (양 플랫폼 공통)

- **allowlist가 곧 보안 경계다.** 브리지가 마이크 권한 게이트를 열고 세션 토큰을 건네므로,
  allowlist 밖 URL은 WebView 로드도 브리지 실행도 막는다
- **검사 순서가 신뢰 경계다** — origin 먼저, 파싱은 그 뒤. allowlist 밖 페이지가 보낸 payload는
  내용과 무관하게 처리할 값이 아니다
- **호출 시점이 아니라 처리 시점의 URL로 판정한다.** 메시지를 보낸 뒤 페이지가 allowlist 밖으로
  리다이렉트될 수 있다
- **불량 payload는 조용히 버린다.** 웹은 오류를 되돌려 줄 상대가 아니고(신뢰 경계 밖), 잘못된
  컨텍스트로 화면을 띄우는 것보다 아무 일도 안 하는 편이 안전하다. 대신 Crashlytics 비치명
  이벤트로 남긴다 — allowlist를 통과한 페이지만 거기 닿으므로, 그 보고는 곧 **우리 웹과 우리
  앱이 계약을 다르게 알고 있다**는 뜻이다

## 6. 플랫폼별 구현 차이

| | Android | iOS |
|---|---|---|
| 주입 | `addJavascriptInterface`로 Kotlin 객체를 그대로 심는다 | 브리지 객체를 **JS 소스로 적어** `WKUserScript(.atDocumentStart)`로 심는다 |
| 동기 반환 | 공짜 — JS가 곧바로 Kotlin을 부른다 | JS 안의 값을 읽는다. `WKScriptMessageHandler`는 단방향·비동기라 값을 되돌릴 수 없다 |
| 스레드 | `@JavascriptInterface`는 **JS 전용 스레드** → `View.post`로 메인에 넘긴 뒤 검증 | WebKit이 메인 스레드에서 부른다 → 넘기는 단계가 없다 |
| 파일 | `web/AccenturyBridge.kt` | `Web/AccenturyBridge.swift` + `Web/BridgeUserScript.swift` |

**계약 메서드를 더할 때 iOS는 파일이 둘이다** — 디스패처의 `case`와 주입 JS의 객체 리터럴을 함께
고쳐야 한다. 웹 쪽 `bridge.ts`는 이 차이를 모른다.

## 7. 계약을 바꿀 때 고칠 곳

메서드를 하나 더할 때 손대는 자리 전부다 (KAN-177이 실제로 지나간 경로).

| 순서 | 파일 |
|---|---|
| 1 | `web/src/bridge/bridge.ts` — 인터페이스 + 래퍼 |
| 2 | `app/src/main/java/com/accentury/app/web/AccenturyBridge.kt` — `@JavascriptInterface` + 생성자 파라미터 |
| 3 | `app/src/main/java/com/accentury/app/web/WebViewHost.kt` · `MainActivity.kt` — 콜백 배선 |
| 4 | `ios/Accentury/Web/BridgeUserScript.swift` — 주입 JS 객체 |
| 5 | `ios/Accentury/Web/AccenturyBridge.swift` — 디스패처 `case` + 필드 |
| 6 | `ios/Accentury/Web/WebViewHost.swift`(3계층) · `TestFlow/TestFlowView.swift` — 콜백 배선 |
| 7 | 이 문서 §2 표 |

테스트도 같은 수만큼 늘어난다: `bridge.test.ts`, `AccenturyBridgeTest.kt`,
`AccenturyBridgeTests.swift`, `BridgeUserScriptTests.swift`(메서드 목록).
