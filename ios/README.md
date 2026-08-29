# accentury iOS (KAN-108)

안드로이드 앱(`app/`)이 정본이고, 이쪽은 같은 설정 의미를 iOS 문법으로 옮긴 포팅본이다.

## 필요한 것

Xcode 26.x, XcodeGen (`brew install xcodegen`). 시뮬레이터만 쓰면 애플 개발자 계정은 없어도 된다.

## 프로젝트 생성 · 열기

```bash
cd ios
xcodegen generate          # project.yml -> Accentury.xcodeproj
open Accentury.xcodeproj
```

`project.yml`이 원본이다. `.xcodeproj`도 커밋하지만 손으로 고치지 않는다 — Xcode UI에서 만진
설정은 다음 `xcodegen generate` 때 조용히 사라진다. 타깃·빌드 설정을 바꿀 자리는 `project.yml`,
값이 구성별로 갈리면 `Accentury/Config/*.xcconfig`다.

## 빌드 · 테스트

```bash
# 순수 Swift 계층만 (시뮬레이터 불필요, 몇 초)
cd ios/AccenturyCore && swift test

# 앱까지 (시뮬레이터)
cd ios
xcodebuild -project Accentury.xcodeproj -scheme Accentury -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' CODE_SIGNING_ALLOWED=NO build
xcodebuild -project Accentury.xcodeproj -scheme Accentury \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' CODE_SIGNING_ALLOWED=NO test
```

## Local.xcconfig

`Accentury/Config/Local.xcconfig.example`를 같은 폴더에 `Local.xcconfig`로 복사해서 쓴다
(gitignore 대상, 안드로이드 `local.properties`와 같은 자리). 없어도 시뮬레이터 빌드는 통과한다.

- `ACCENTURY_TEAM_ID` — 실기기 빌드용 애플 팀 ID
- `WEB_URL` / `API_BASE_URL` — Debug 빌드가 열 주소 오버라이드. 실기기는 맥의 localhost를
  모르므로 `cloudflared tunnel --url http://localhost:5173`이 준 HTTPS 주소를 넣는다.
  Release는 이 파일이 덮지 못한다 (`Config/Release.xcconfig` 주석 참고).

## 가짜 마이크 (디버그 전용)

시뮬레이터에는 쓸 만한 마이크 입력이 없고 실기기라도 매번 같은 발화를 낼 수는 없다. 피치
곡선을 눈으로 다듬으려면 같은 음성이 같은 속도로 들어와야 해서, 디버그 빌드에 한해 WAV를
마이크 자리에 끼울 수 있다 (안드로이드 `./gradlew :app:installDebug -PfakeMic=fake_mic.wav`와
같은 자리).

`Local.xcconfig`에 한 줄 적고 다시 빌드한다:

```
FAKE_MIC_ASSET = fake_mic.wav            # 앱 번들에 넣은 리소스 이름
FAKE_MIC_ASSET = /Users/me/fake_mic.wav  # 또는 맥의 절대 경로 (시뮬레이터는 맥 파일을 읽는다)
```

WAV는 16kHz 모노 16bit여야 한다 — 리샘플하지 않는다 (`AccenturyCore/Audio/FilePcmSource.swift`).

번들 리소스는 `Accentury/DebugResources/`에 둔다. 그 폴더는 **Debug 빌드에만** 실린다
(`project.yml`의 `EXCLUDED_SOURCE_FILE_NAMES`).

거기 있는 `fake_mic.wav`는 **안드로이드 `app/src/debug/assets/fake_mic.wav`와 바이트가 같다**
(실제 발화 2.5초, 유성 70/75, F0 80~239Hz). 두 런타임에 같은 소리를 넣어야 캡처를 나란히
놓고 비교할 수 있어서다 — 한쪽만 갈면 곡선이 달라져도 그게 렌더 차이인지 입력 차이인지
못 가른다. **한쪽을 바꾸면 반대쪽도 같은 커밋에서 바꾼다.**

> 한때 이 자리에 220Hz 고정 톤을 만들어 넣는 스니펫이 있었는데 걷어냈다 (KAN-108 §7b).
> 값이 안 변해 억양 곡선이 레인 한가운데 평선으로만 그려져, 곡선 렌더를 검증할 수 없었다.
> 목소리 점검(§6)은 이 발화 자산으로도 그대로 통과한다.

릴리스 빌드에는 이 경로가 없다. 잠금이 셋이다 — 읽기 코드가 통째로 `#if DEBUG`이고,
`FAKE_MIC_ASSET` 키가 `Info-Release.plist`에 아예 없고, `DebugResources/`가 릴리스 번들에서
빠진다. 릴리스에서 파일 소스를 켜려면 셋을 다 고쳐야 하고, 그건 리뷰에 걸린다.

## 스모크 메뉴 (KAN-108 §3·§4)

§5부터 앱의 첫 화면은 `TestFlowView`(WKWebView + 브리지)다. §1~§4에서 세워 둔 설정값 표시와
녹음·권한 버튼은 지우지 않고 실행 인자 뒤로 옮겼다 — 캡처·권한 경로를 손으로 다시 확인할 일이
§6까지 남아 있어서다.

```bash
xcrun simctl launch --console-pty booted com.accentury.app -DebugSmokeMenu 1
```

아래 자동 스모크 인자들은 이 메뉴 없이도 그대로 동작한다.

```bash
# 파일 소스로 3초 - 220Hz WAV를 앱이 임시 폴더에 만들어 흘린다. 마이크 권한이 필요 없다.
xcrun simctl launch --console-pty booted com.accentury.app -AutoRecordSmoke 1
# SMOKE: pcm=48000 duration=3000 f0frames=90 voiced=90 wav=96044

# 실제 마이크(AVAudioEngine)로 최대 10초. 권한을 미리 준다.
xcrun simctl privacy booted grant microphone com.accentury.app
xcrun simctl launch --console-pty booted com.accentury.app -AutoMicSmoke 1
```

`simctl`에는 화면을 탭할 방법이 없어서 버튼 말고 실행 인자 경로를 따로 둔 것이다.

### 시뮬레이터에서는 마이크 경로를 확인할 수 없다 (2026-08-30 확인)

`-AutoMicSmoke`는 이 맥의 시뮬레이터에서 **앱을 죽인다**. 크래시는 우리 코드가 아니라
`AVAudioEngine.inputNode` 안이다 — `AURemoteIO::Initialize()`가 호스트 오디오 서버 RPC를
10초 기다리다 `abort()`한다(SIGABRT). Swift에서 잡을 수 있는 오류가 아니다.

변수를 갈라 확인한 결과(모두 메인 스레드, 콜드 부팅 후에도 재현):

| 세션 설정 | `inputNode` 결과 |
|---|---|
| `.record`/`.measurement` + `setActive(true)` | 10초 뒤 abort |
| `.record`/`.default` + `setActive(true)` | 10초 뒤 abort |
| `.playAndRecord`/`.default` + `setActive(true)` | 10초 뒤 abort |
| 세션 설정 없음 | 통과하지만 포맷이 `0.0Hz 2ch` (입력 없음) |

세션은 `isInputAvailable=true`, `availableInputs=1`, 경로 `MicrophoneBuiltIn`으로 **거짓말을
한다** — 그래서 코드가 미리 걸러낼 방법이 없다. 맥 자체의 입력 장치는 정상이다(내장 마이크,
48kHz, 기본 입력). 가장 유력한 원인은 macOS 쪽 Simulator.app에 마이크 권한이 없는 것이다:
시스템 설정 › 개인정보 보호 및 보안 › 마이크에서 Simulator를 켜고 다시 시도해 볼 것.

그래서 캡처 계층 검증은 **파일 소스(`-AutoRecordSmoke`)까지**이고, `AudioRecorder`(AVAudioEngine)
경로는 실기기에서 한 번 확인해야 한다.

## 권한 게이트 스모크 (KAN-108 §4)

스모크 메뉴의 «권한 게이트 테스트» 버튼과 실행 인자 두 개. §5부터 게이트를 여는 정본은 웹의
`requestMicPermission`이고(아래 WebView 스모크), 이 인자들은 게이트 화면 자체를 따로 볼 때 쓴다.

```bash
# 게이트를 바로 띄운다. 상태가 바뀔 때마다 PERM: 한 줄이 로그로 나온다.
xcrun simctl launch --console-pty booted com.accentury.app -AutoPermissionSmoke 1
# PERM: state=rationale real=undetermined

# 권한을 밖에서 바꿔 가며 복원 규칙을 확인한다.
xcrun simctl privacy booted reset  microphone com.accentury.app   # 안 물어본 상태
xcrun simctl privacy booted grant  microphone com.accentury.app   # 허용
xcrun simctl privacy booted revoke microphone com.accentury.app   # 거부
```

`state`는 저장 키(안드로이드 `rememberSaveable` 값과 같은 문자열), `real`은 OS가 말하는 실제
권한이라 둘이 어긋나는 순간이 로그에 그대로 남는다 — **실제 권한이 이긴다**는 규칙이 여기서 보인다.

`-AutoPermissionRequest 1`을 같이 주면 화면이 뜨자마자 권한을 요청한다. 시뮬레이터에는 탭을
넣을 방법이 없어서(`xcrun simctl`에 좌표 입력이 없다) 버튼을 누를 수 없는데, 이미 거부된
상태에서는 요청이 팝업 없이 곧바로 거절돼 영구 거부 화면까지 자동으로 간다.

```bash
xcrun simctl privacy booted revoke microphone com.accentury.app
xcrun simctl launch --console-pty booted com.accentury.app -AutoPermissionSmoke 1 -AutoPermissionRequest 1
# PERM: state=rationale real=denied
# PERM: state=permanently_denied real=denied
```

**아직 안 물어본 상태(`reset`)에서는 팝업이 실제로 뜬다.** 그 팝업은 자동화로 누를 수 없어
허용/거부 분기는 손으로 눌러 확인해야 한다.

## 주소가 흐르는 경로

`Config/*.xcconfig` → `Info-{Debug,Release}.plist`의 `$(WEB_URL)` → `AppConfig.swift`.
안드로이드의 `BuildConfig.WEB_URL` 자리다. 값이 비면 기본값으로 때우지 않고 즉시 중단한다.

## WebView·브리지 스모크 (KAN-108 §5)

웹 dev 서버가 먼저 떠 있어야 한다.

```bash
cd web && npm run dev -- --host 127.0.0.1 --port 5173
```

시뮬레이터에는 탭을 넣을 방법이 없어서(`xcrun simctl`에 좌표 입력이 없다) 눌러야 하는 자리마다
실행 인자를 따로 뒀다. 전부 디버그 빌드 전용이고 릴리스 바이너리에는 문자열조차 없다.

```bash
# 인트로가 뜨는 것까지. 브리지 버전이 URL에 실려 나가 «앱 업데이트 필요»가 뜨지 않는다.
xcrun simctl launch --console-pty booted com.accentury.app
# TOKEN: pushed origin=http://localhost:5173 empty=true
# NAV: committed http://localhost:5173/?bridge=1&app=1.0

# 인트로의 [시작하기]를 눌러 브리지 requestMicPermission을 흘린다 → 권한 게이트가 웹 위를 덮는다.
xcrun simctl privacy booted reset microphone com.accentury.app
xcrun simctl launch --console-pty booted com.accentury.app -AutoStartSmoke 1
# SMOKE: autostart=armed
# FLOW: startRequested=true

# 시작 게이트를 끝까지 밀어 테스트 진입 URL까지 간다 (권한 허용 + 목소리 점검 자리 표시 통과).
xcrun simctl privacy booted grant microphone com.accentury.app
xcrun simctl launch --console-pty booted com.accentury.app -AutoStartSmoke 1 -AutoGateSmoke 1
# TOKEN: pushed origin=http://localhost:5173 empty=false
# NAV: committed .../?bridge=1&app=1.0&screen=test&testVersion=gn-2026.08.1&sessionId=s_debug_stub

# allowlist 밖으로 나가 보고 막히는지 본다. 화면은 인트로 그대로 (오류 화면이 아니다).
xcrun simctl launch --console-pty booted com.accentury.app -AutoNavSmoke "https://example.com"
# NAV: cancelled https://example.com/
```

`TOKEN:` 줄에 **토큰 값은 찍히지 않는다** — 밀어 넣었다는 사실과 origin, 비었는지만 남긴다.

### 여기서 멈추는 지점 (§8 백엔드 결선)

테스트 진입 URL이 열리면 웹이 `GET /v0/tests/{testVersion}`을 부르고, 백엔드가 없으면
«문항을 불러오는 중…»에서 멈춘다. 웹을 통해 확인 가능한 범위는 거기까지다.

## 목소리 점검·세션 게이트·녹음 화면 (KAN-108 §6)

§6부터 시작 게이트의 세 칸이 전부 진짜 화면이다 — 마이크 권한(KAN-98) → 목소리 점검(KAN-105)
→ 세션 생성(KAN-34). 세션 클라이언트도 **기본이 실제 서버**다 (`URLSessionSessionClient`).

```bash
# 백엔드 없이 게이트 뒤를 보려면 세션을 스텁으로 바꾼다 (디버그 전용).
xcrun simctl privacy booted grant microphone com.accentury.app
xcrun simctl launch --console-pty booted com.accentury.app \
  -StubSession 1 -AutoStartSmoke 1 -AutoGateSmoke 1
# NAV: committed .../?bridge=1&app=1.0&screen=test&testVersion=gn-2026.08.1&sessionId=s_debug_stub
```

- `-StubSession 1` — `DebugStubSessionClient`(고정 세션, 네트워크 없음). **없으면 실제 서버로
  나간다** — 백엔드가 안 떠 있으면 세션 게이트의 «연결이 불안정해요» 실패 화면을 볼 수 있다.
  기본을 스텁으로 두지 않은 이유가 그것이다: 스텁이 기본이면 실패 화면을 한 번도 못 본다.
- `-AutoGateSmoke 1` — 목소리 점검의 [다음]을 대신 누른다. 가짜 마이크가 물려 있으면 실제로
  잰 중심 음높이를 넘기고, 없으면 자리 표시 0으로 넘긴다.

### 녹음 화면을 따로 보기

백엔드가 없으면 웹이 VOICE 문항을 못 그려 브리지의 `startVoiceItem`이 오지 않는다. 그 화면만
따로 세우는 통로가 있다.

```bash
xcrun simctl launch --console-pty booted com.accentury.app \
  -StubSession 1 -AutoStartSmoke 1 -AutoGateSmoke 1 -AutoRecordingOverlay 1 -AutoRecordingDrive 1
# FLOW: startVoiceItem item=it_debug_overlay number=3/10
```

- `-AutoRecordingOverlay 1` — 고정 `VoiceItemStart`를 **웹이 부르는 것과 같은 함수**로 흘려
  넣는다. 배선까지 함께 확인된다.
- `-AutoRecordingDrive 1` — 녹음 버튼도 대신 누른다. 대기(4초) → 녹음 중(4초) → 확인 순서로
  서므로 그 사이에 `xcrun simctl io booted screenshot`을 끼우면 세 화면이 다 잡힌다.

두 곡선 레인이 다 그려진다 (§7b, `UI/Components/CurveLane.swift`). 위는 payload의 `guideF0`를
점선으로, 아래는 녹음 중 자라는 내 억양을 굵은 실선 + 망점으로 긋는다 — 자동 오버레이가 실어
보내는 가이드는 합성 곡선이다(`TestFlowView.swift`의 `debugGuideF0`).

아래 레인에 억양이 나오려면 가짜 마이크가 물려 있어야 한다(위 «가짜 마이크» 절) — 자산은
안드로이드와 같은 파일이라 두 런타임의 캡처를 그대로 나란히 놓고 비교할 수 있다.

`-AutoRecordingDrive 1`이면 녹음이 끝날 때 `LATENCY: p50=… p95=… max=… n=…` 한 줄이 찍힌다
(NFR-PF-02). 재는 구간은 파이프라인 전체가 아니라 **진행 콜백 → 캔버스 그리기**, 곧 이
런타임이 새로 얹는 몫이다 — 앞쪽(창 채우기·EMA·캡처 버퍼)은 `docs/wiki/pitch-curve.md` §3이
분해해 두었다. 시뮬레이터 기준 p50 8.4ms · p95 15.1ms · max 16.4ms (n=68).

### 녹음은 디스크에 닿지 않는다 (§5.5, FR-DP-02)

음성 바이트가 사는 곳은 둘뿐이다: 녹음 직후의 `RecordingModel`(한 번 꺼내면 사라진다)과
업로드가 끝나거나 폐기될 때까지의 `UploadManager`(메모리). `AccenturyTests/RecordingFileLifecycleTests`가
녹음 한 벌을 통째로 태운 뒤 임시 폴더·캐시·문서에 WAV가 없는지 파일 시스템에 직접 묻는다.

### 업로드는 앱이 뒤로 가도 이어진다

진행 중인 업로드가 있는 동안 `beginBackgroundTask`로 실행 시간을 빌린다 — **상한은 약 30초**고
iOS가 주는 시간이라 보장값이 아니다. 그 안에 못 끝내면 전송 실패로 떨어지고 업로드 상태 바의
[재시도]가 받는다. `URLSessionConfiguration.background`는 쓰지 않는다: 그쪽은 본문을 파일로
요구해서 녹음이 디스크에 남는다 (`Upload/UploadModel.swift` 주석).

### 진행 저장이 남는다

`TestFlowModel`이 `UserDefaults`에 진행을 적는다(`test_flow_state`·`session_gate_state`·
`test_flow_start_requested` 등, 안드로이드 `rememberSaveable` 자리). 인트로부터 다시 보려면
앱을 지웠다 깔거나 시뮬레이터를 초기화한다.

```bash
xcrun simctl uninstall booted com.accentury.app
```

## 브리지가 iOS에서 갈리는 지점

안드로이드는 `addJavascriptInterface`로 코틀린 객체를 페이지에 그대로 심어서, 값을 **동기로
돌려주는** `getContractVersion()`·`getSessionToken()`이 공짜로 나온다. WKWebView에는 그 자리가
없다 — `WKScriptMessageHandler`는 단방향·비동기다.

그래서 브리지 객체 자체를 JS로 적어 `WKUserScript`(`.atDocumentStart`, 메인 프레임 전용)로 심는다
(`Accentury/Web/BridgeUserScript.swift`). 값을 돌려주는 둘은 JS 안의 값을 읽고, 상태를 바꾸는
넷은 `postMessage`로 네이티브에 넘긴다. **웹은 이 차이를 모른다** — `web/src/bridge/bridge.ts`는
한 글자도 바뀌지 않았다.

토큰은 문서에 매인 JS 변수다. 안드로이드의 fail-closed `AtomicBoolean`이 하던 일이 구조로 따라온다:
시작값이 빈 문자열이고, 메인 프레임 전환마다 유저 스크립트가 다시 돌아 초기화되며, 네이티브는
**커밋된 문서의 origin이 allowlist 안일 때만** 밀어 넣는다. iframe에는 스크립트 자체가 가지 않는다.

## 카카오 공유가 iOS에 없다

안드로이드는 카카오 피드 템플릿으로 카톡을 직접 열고 미설치면 OS 공유 시트로 내려간다.
iOS는 그 **폴백에 해당하는 것만** 세웠다 — `UIActivityViewController`에 문구와 캠페인 URL을 싣는다
(카톡이 깔려 있으면 시트에 그대로 뜬다). 카카오 링크 SDK는 KAN-30의 안드로이드 범위였고,
iOS 도입은 후속 티켓이다 (`TestFlowView.swift`의 `ShareSheet` 주석).
