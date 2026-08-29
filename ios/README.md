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

## 주소가 흐르는 경로

`Config/*.xcconfig` → `Info-{Debug,Release}.plist`의 `$(WEB_URL)` → `AppConfig.swift`.
안드로이드의 `BuildConfig.WEB_URL` 자리다. 값이 비면 기본값으로 때우지 않고 즉시 중단한다.
