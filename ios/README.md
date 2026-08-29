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
안드로이드가 쓰는 파일이 `app/src/debug/assets/fake_mic.wav`에 있으니 그 절대 경로를 그대로
넣으면 두 플랫폼이 같은 음성을 흘린다.

릴리스 빌드에는 이 경로가 없다. 읽기 코드가 통째로 `#if DEBUG`이고 `FAKE_MIC_ASSET` 키가
`Info-Release.plist`에 아예 없어서, 릴리스에서 파일 소스를 켜려면 릴리스 plist를 고쳐야 한다.

## 녹음 스모크 (KAN-108 §3, §5에서 제거)

`ContentView`의 «녹음 테스트» 버튼과 실행 인자 두 개가 임시로 붙어 있다. WKWebView 호스트가
들어오는 §5에서 통째로 걷어낸다.

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

## 권한 게이트 스모크 (KAN-108 §4, §5에서 제거)

`ContentView`의 «권한 게이트 테스트» 버튼과 실행 인자 두 개. §5에서 웹의 `requestMicPermission`이
게이트를 열게 되면 통째로 걷어낸다.

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
