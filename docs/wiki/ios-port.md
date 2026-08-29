# iOS 이식 (KAN-108)

안드로이드 앱(`app/`)이 정본이고 `ios/`는 같은 계약을 iOS 문법으로 옮긴 포팅본이다. **웹(`web/`)은
한 글자도 바뀌지 않았다** — 같은 브리지 계약 v1을 두 런타임이 각자의 방식으로 성립시킨다.

빌드·실행 방법은 `ios/README.md`에 있다. 이 문서는 "왜 이렇게 갈렸는가"와 "어디까지 확인됐는가"다.

## 1. 배치

```
ios/
  project.yml            XcodeGen 정의 — 이것이 원본이다
  Accentury.xcodeproj    생성물(커밋은 하지만 손으로 고치지 않는다)
  AccenturyCore/         로컬 SPM 패키지: 순수 로직 (시뮬레이터 없이 swift test)
  Accentury/             앱 타깃: SwiftUI·WebKit·AVFoundation 결선
  AccenturyTests/        앱 계층 테스트 (시뮬레이터 필요)
```

경계는 **시뮬레이터 없이 테스트할 수 있는가**로 갈랐다. F0 추정·프레이밍·품질 판정·WAV·브리지
계약·업로드 상태 머신·세션 게이트·곡선 모델은 전부 Core이고, 화면·마이크·WebView·`UserDefaults`만
앱 타깃에 남는다. 안드로이드가 한 모듈인 것과 갈리는 지점인데, 이유는 검증 속도다 — Core 400건이
몇 초에 돌고 앱 121건만 시뮬레이터를 기다린다.

`project.yml`이 원본인 이유: Xcode UI에서 만진 설정은 다음 `xcodegen generate`에 조용히 사라진다.
타깃·빌드 설정은 `project.yml`, 구성별로 갈리는 값은 `Accentury/Config/*.xcconfig`다.

주소가 흐르는 경로는 안드로이드 `BuildConfig.WEB_URL` 자리를 그대로 옮겼다:
`Config/*.xcconfig` → `Info-{Debug,Release}.plist`의 `$(WEB_URL)` → `AppConfig.swift`.
값이 비면 기본값으로 때우지 않고 즉시 중단한다.

## 2. 브리지 심 — 안드로이드와 구조가 갈리는 유일한 자리

안드로이드는 `addJavascriptInterface`로 코틀린 객체를 페이지에 심어서, 값을 **동기로 돌려주는**
`getContractVersion()`·`getSessionToken()`이 공짜로 나온다. WKWebView에는 그 자리가 없다 —
`WKScriptMessageHandler`는 단방향·비동기다.

그래서 브리지 객체 자체를 JS로 적어 `WKUserScript`(`.atDocumentStart`, 메인 프레임 전용)로 심는다
(`Accentury/Web/BridgeUserScript.swift`). 값을 돌려주는 둘은 JS 안의 값을 읽고, 상태를 바꾸는 넷은
`postMessage`로 네이티브에 넘긴다.

### 보안 등가성

안드로이드의 fail-closed `AtomicBoolean`이 하던 일이 iOS에서는 **구조로** 따라온다.

| 규칙 | 안드로이드 | iOS |
|---|---|---|
| 토큰 시작값 | `originAllowed=false`면 빈 문자열 | 유저 스크립트가 문서마다 `""`로 초기화 |
| 문서 전환 시 무효화 | `onPageStarted`에서 플래그 내림 | 새 문서 = 새 JS 컨텍스트, 스크립트 재실행 |
| 밀어 넣는 조건 | origin allowlist | `didCommit` 뒤 **커밋된 문서의 origin**이 allowlist일 때만 |
| iframe 차단 | 인터페이스가 메인 프레임 한정 | `.forMainFrameOnly = true`라 스크립트 자체가 안 감 |
| 실행 시점 재검증 | `shouldOverrideUrlLoading` | `BridgeDispatcher`가 메시지 처리 시점 URL을 다시 본다 |
| allowlist 밖 로드 | `shouldOverrideUrlLoading` true | `decidePolicyFor navigationAction` → `.cancel` |

setter는 non-writable이고 객체는 freeze한다. 토큰 값은 **로그에 남기지 않는다** — `TOKEN:` 줄은
밀어 넣었다는 사실과 origin, 비었는지만 찍는다.

`decisionHandler(.cancel)`을 부르면 WebKit이 곧바로 실패 콜백을 쏘는데(`NSURLErrorCancelled`,
정책 취소면 `WebKitErrorDomain` 102) 그걸 실패로 접으면 링크 한 번 막을 때마다 화면이 오류로
뒤집힌다. 안드로이드에는 없던 구분이다.

## 3. 단계별로 안드로이드와 갈린 것

각 항목의 근거는 그 단계 커밋 본문에 있다 (`git log 9a1bfa4^..`).

| 단계 | 갈린 것 | 이유 |
|---|---|---|
| 1 | Release URL을 로컬 파일이 못 덮는다 | `Local.xcconfig` include **뒤에** Release 값을 선언 — 안드로이드 `local.properties`에는 없던 잠금 |
| 2 | Accelerate 미사용, Float 연산 순서 보존 | 벡터화하면 YIN 결과가 코틀린과 마지막 자리에서 갈린다. `AndroidParityTests`가 0.01Hz로 대조 |
| 3 | `Int16Rechunker` 추가 | AVAudioConverter 출력 길이가 하드웨어 포맷마다 달라, 512 샘플 재절단이 새로 필요했다 (AudioRecord는 요청 크기로 준다) |
| 3 | `.record`/`.measurement` 세션 | OS 음성 처리·AGC를 배제해야 F0가 안드로이드와 같은 신호를 본다 |
| 4 | `denied` → `permanentlyDenied` 붕괴 | iOS는 권한 팝업이 **설치당 한 번**이라 거부 뒤 다시 물을 수 없다. 브리지에 노출하는 4상태 계약은 그대로 두고 상태 전이만 붕괴시킨다 |
| 5b | 로드 실패 시 WebView 재생성 | `attempt`를 `.id()`에 걸어 실패한 WebView의 내부 오류 페이지를 이어받지 않는다 (안드로이드는 컴포지션에서 빼는 것으로 충분했다) |
| 5b | 밀려난 로드의 늦은 실패 무시 | 안드로이드 `request.isForMainFrame`이 없어 `WKNavigation` 신원으로 대신한다 |
| 6a | 플랫폼 문자열 `"IOS"` | 백엔드 enum이 `ANDROID`/`IOS`다 |
| 6a | `UploadManager`가 actor | 코틀린 뮤텍스 자리. 임계 구간에 `await`가 없어야 폐기 경합이 원자적이다 |
| 6b | `beginBackgroundTask` (상한 ≈30초) | 안드로이드는 프로세스가 살아 있으면 코루틴이 계속 돌지만 iOS는 백그라운드 몇 초 뒤 정지한다. `URLSessionConfiguration.background`는 **쓰지 않는다** — 본문을 파일로 요구해 녹음이 디스크에 남는다 (FR-DP-02 위반) |
| 7b | 밴드를 그리지 않는다 | 안드로이드와 같은 판단 |
| 7b | `FilePcmSource` 심볼이 Release에도 있다 | Core가 한 모듈이라 타입 자체는 링크된다. **결선이 없다**: 읽는 코드가 `#if DEBUG`이고, `FAKE_MIC_ASSET` 키가 `Info-Release.plist`에 없고, `DebugResources/`가 릴리스 번들에서 빠진다 |
| 5b | 카카오 SDK 없음 | 안드로이드의 **폴백 경로에 해당하는 것**만 세웠다 — `UIActivityViewController`(카톡 설치 시 시트에 뜬다). 카카오 링크 SDK는 후속 티켓 |

## 4. 스모크 훅

시뮬레이터에는 탭을 넣을 방법이 없다(`xcrun simctl`에 좌표 입력이 없다). 눌러야 하는 자리마다
실행 인자를 뒀고, **전부 `#if DEBUG`라 릴리스 바이너리에는 문자열조차 없다**(§7 검증 참고).

| 인자 | 하는 일 |
|---|---|
| `-DebugSmokeMenu 1` | §1~§4의 설정값·녹음·권한 버튼 메뉴 |
| `-AutoRecordSmoke 1` | 파일 소스로 3초 캡처 (`SMOKE:` 한 줄) |
| `-AutoMicSmoke 1` | 실제 마이크 캡처 — **시뮬레이터에서는 크래시한다**(§6) |
| `-AutoPermissionSmoke 1` | 권한 게이트를 바로 띄운다 (`PERM:` 줄) |
| `-AutoStartSmoke 1` | 인트로의 [시작하기]를 JS로 누른다 |
| `-AutoGateSmoke 1` | 목소리 점검의 [다음]을 대신 누른다 |
| `-AutoNavSmoke <url>` | allowlist 밖으로 이동을 시도한다 |
| `-StubSession 1` | 백엔드 없이 고정 세션 (기본은 **실제 서버**다) |
| `-AutoRecordingOverlay 1` | 고정 payload로 녹음 화면만 세운다 |
| `-AutoRecordingDrive 1` | 녹음 버튼을 시간에 맞춰 누른다 (`LATENCY:` 한 줄) |
| `-AutoFlowDrive 1` | **통합 스모크**(§8). 아래 참고 |
| `-BridgeVersionOverride <n>` | URL의 `bridge=`를 덮는다. 스큐 화면 검증용 |

`-AutoFlowDrive`만 절반씩 나뉜다. 네이티브 절반(`TestFlowView`)은 녹음 오버레이가 **뜰 때마다**
[녹음]을 누르고, 가짜 마이크가 끝나 검토로 넘어오면 [다음]을 누른다 — 시간이 아니라 페이즈 변화를
듣는 이유는 오버레이가 음성 문항 수만큼 뜨고 그 시점을 서버 정의가 정하기 때문이다. 웹 절반
(`Web/WebAutoDriver.swift`)은 300ms마다 DOM을 보고 화면을 판정한 뒤 어휘 문항의 선택지와 [다음]을
누른다. 화면 판정은 **클래스 이름으로**, 버튼은 문구로 찾는다.

정지는 누르지 않는다. WAV가 끝나면 캡처 스트림이 닫혀 엔진이 스스로 검토로 넘어가므로, 정지를
걸면 사람이 끊은 녹음을 재게 되어 안드로이드 캡처와 나란히 놓을 값이 아니게 된다.

## 5. 가짜 마이크

`Local.xcconfig`의 `FAKE_MIC_ASSET`. `DebugResources/fake_mic.wav`는 안드로이드
`app/src/debug/assets/fake_mic.wav`와 **바이트가 같다**(실제 발화 2.5초, 유성 70/75, F0 80~239Hz).
같은 소리를 두 런타임에 넣어야 캡처를 나란히 놓고 비교할 수 있어서다 — 한쪽만 갈면 곡선이 달라져도
그게 렌더 차이인지 입력 차이인지 못 가른다. **한쪽을 바꾸면 반대쪽도 같은 커밋에서 바꾼다.**

## 6. 실기기 체크리스트 (아직 안 한 것)

시뮬레이터에서 확인할 수 없는 것이 둘이다.

1. **마이크 캡처 경로.** `-AutoMicSmoke`는 이 맥의 시뮬레이터에서 앱을 죽인다 —
   `AVAudioEngine.inputNode` 안의 `AURemoteIO::Initialize()`가 호스트 오디오 서버 RPC를 10초
   기다리다 `abort()`한다. Swift에서 잡을 수 있는 오류가 아니고, 세션은 `isInputAvailable=true`로
   거짓말을 해서 코드가 미리 걸러낼 수도 없다. 그래서 캡처 검증은 **파일 소스까지**다.
2. **곡선 지연(NFR-PF-02)의 실기기 값.** 시뮬레이터 값은 p50 8.4ms · p95 15.1ms · max 16.4ms (n=68).

실기기에서 확인할 것:

- `-AutoMicSmoke 1`로 `MIC:` 줄 — 실제 마이크로 F0 프레임이 잡히는가
- `-AutoRecordingOverlay 1 -AutoRecordingDrive 1`로 `LATENCY:` 줄 (`-AutoFlowDrive`도 같은 줄을 낸다)
- 권한 팝업의 허용/거부 분기 (시뮬레이터에서는 자동으로 누를 수 없다)
- 실기기는 맥의 localhost를 모른다 — `cloudflared tunnel --url http://localhost:5173`이 준 HTTPS
  주소를 `Local.xcconfig`의 `WEB_URL`/`API_BASE_URL`에 넣는다
- 백그라운드 업로드 30초 상한 (홈으로 나갔다 돌아오기)

## 7. 8단계가 확인한 것 / 확인하지 못한 것

### 확인한 것

- **전 구간 완주** (§5 AC1). 실제 백엔드·AI·Vite에 붙여 인트로 → 권한 게이트 → 목소리 점검 →
  세션 생성 → 음성 5 + 어휘 5 → 분석 대기 → 결과까지 자동 구동으로 완주. 백엔드 로그가
  `platform=IOS traffic=REAL`, 업로드 5건·어휘 5건·분석 5건·테스트 완료를 같은 sessionId로 기록한다.
- **브리지 스큐** (§5 AC3). `-BridgeVersionOverride 0` → 웹의 «앱 업데이트가 필요해요».
- **allowlist 차단** (§5). `-AutoNavSmoke`로 이미 확인 (5b단계).
- **곡선 시각 등가성** (§7 AC3). 같은 WAV·같은 문항(v1)의 검토 화면을 두 런타임에서 캡처해 나란히
  놓았다 — 가이드 점선, 사용자 실선 + 망점 채움, 봉우리·급강하·이중 융기 위치, 품질 문구, "녹음 길이
  2.5초"까지 같다. 두 쪽 다 2500ms / 75 피치 프레임.
- **릴리스 격리.** Release 바이너리에 `AutoFlowDrive`·`WebAutoDriver`·`BridgeVersionOverride`·
  `StubSession`·`FAKE_MIC_ASSET`·`AutoRecordingDrive` 문자열이 **0건**이다.

### 확인하지 못한 것

- **실기기** — 이 맥에 연결된 iPhone이 없다(`xcrun devicectl list devices` → No devices found).
  위 체크리스트가 그대로 남는다.
- **TestFlight 서명** (§1 AC3). Release 아카이브가 서명에서 막힌다:
  `error: No Accounts: Add a new account in Accounts settings.` /
  `error: No profiles for 'com.accentury.app' were found`.
  코드는 문제가 아니다 — `CODE_SIGNING_ALLOWED=NO`로는 같은 Release 구성이 BUILD SUCCEEDED다.
  필요한 사람 작업: **Xcode › Settings › Accounts에 애플 개발자 계정 로그인**, 그리고 그 팀
  (`ACCENTURY_TEAM_ID = 559P9SYY57`)에 App ID `com.accentury.app`이 없으면
  developer.apple.com에서 등록. 그 뒤 `-allowProvisioningUpdates`가 프로파일을 스스로 받아 온다.
- **분석의 실제 모델** — 백엔드 로그의 `modelVersion=stub-0.1`이다. 이 스모크가 확인한 것은 iOS가
  올린 바이트로 분석 파이프라인이 끝까지 돌아 결과 화면이 뜬다는 사실이지, 점수의 타당성이 아니다.

## 8. 8단계에서 잡은 결함 하나

`WebViewHost`의 `onWebViewCreated`가 `makeUIView` 안에서 상위의 `@State`를 그 자리에 썼다. 거기는
SwiftUI 갱신 사이클 **안**이라 값이 반영되지 않는다 — 상위(`TestFlowView`)가 WebView 참조를 영영
nil로 들고, 그러면 문항 결과 주입(`deliverResults`)이 매번 "받을 곳이 없다"로 건너뛴다.

증상이 조용해서 §7까지 숨어 있었다. **첫 음성 문항의 업로드까지는 멀쩡히 끝나고**(세션 생성 201,
업로드 202, 분석 작업 생성) 그 다음부터 웹이 오지 않을 결과를 기다리며 «잠시만요…»에서 멈춘다.
스텁 세션으로는 업로드가 401로 떨어져 이 경로가 한 번도 실행되지 않았고, 그래서 실제 백엔드에
붙는 8단계가 첫 노출이었다.

고침은 등록·해제 통지를 한 틱 미루는 것이다. 같은 파일의 `updateUIView`가
`model.onNavigationStarted()`를 미루던 이유가 그대로 여기에도 있었다.

미루는 순간 **"미룬 사이에 무슨 일이 있었는가"가 새 규칙이 된다** — 미뤄 둔 생성 통지가 돌기
전에 그 WebView가 해체되면 상위가 이미 죽은 인스턴스를 받아 든다(Codex 지적). 그래서 판정을
`Web/WebViewLifecycleNotifier.swift`로 빼고 규칙 셋을 세웠다: 생성 통지는 아직 살아 있을 때만,
해제 통지는 생성을 알린 적이 있을 때만 발행하고, 상위의 신원 대조는 그대로 둔다. 해체 기록은
**동기**라 그 한 줄이 미뤄 둔 생성 통지를 무효로 만든다.

큐가 주입 가능한 이유는 그 순서를 손으로 세우기 위해서다 —
`AccenturyTests/WebViewLifecycleNotifierTests`가 순열 셋을 흘려 넣고, 상위의 신원 대조까지 함께
재현해 "부모가 결국 무엇을 들고 있는가"를 단언한다.

## 9. 이월

- **카카오 링크 SDK** — 지금은 OS 공유 시트뿐이다. 카드 그림을 실어 보내는 정식 경로가 카카오
  템플릿이고, 그건 KAN-30의 안드로이드 범위였다.
- **외부 링크** — 여는 길이 아직 없다. 안드로이드가 "생기면 Custom Tabs로"라고 적어 둔 자리이고
  iOS의 대응물은 `SFSafariViewController`다.
- **CI에 ios 잡** — `.github/workflows/test.yml`은 워크플로 레벨 `paths`가 아니라 job 레벨 `if` +
  `dorny/paths-filter`로 경로를 가른다(KAN-37). 그래서 필터에 `ios: ['ios/**']` 한 칸을 더하고
  `runs-on: macos-*` 잡을 붙이면 required check를 잠그지 않고 들어간다 — 스킵된 job이 통과로
  취급되는 구조라서다.
- **실기기·TestFlight** — §7 참고.

## 참고

- `ios/README.md` — 빌드·실행·스모크 명령
- `docs/wiki/pitch-curve.md` — 곡선 파라미터와 지연 분해
- `docs/wiki/ondevice-f0.md` — YIN·프레이머
- 스모크 캡처·로그 — `.omc/artifacts/kan108-smoke/` (로컬 전용, 커밋되지 않는다)
