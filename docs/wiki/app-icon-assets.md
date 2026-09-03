# 앱 아이콘·스플래시·스토어 자산 (KAN-178)

런처 아이콘·스플래시·스토어 그래픽·스토어 스크린샷이 **어느 원본에서 나오고, 어떻게 배선돼
있는지**를 적는다. 도상 자체의 확정 근거(후보 비교·시안 이력)는 노션 「FE 개발 중 진행 상황 정리」
§1-12와 `assets/app-icon/README.md`가 갖고, 이 문서는 코드 쪽 — 파일·스크립트·배선 규칙 — 만 다룬다.

- 티켓: **KAN-178** (선행 KAN-161 Papercut 팔레트, KAN-162 캐릭터 파이프라인)
- 소비처: Android 런처·스플래시(`app/src/main/res/`) · iOS `AppIcon`·런치 스크린(`ios/Accentury/Assets.xcassets/`) · Play/App Store 등록(KAN-174·KAN-175)
- 자산 폴더의 실행 문서: `assets/app-icon/README.md` · `assets/screenshots/README.md`

## 1. 도상 결정 상태

**임시 확정 C2** (2026-09-03). 가로로 놓인 "ㅅㅌㄹ" 세 글자를 잉크 종이 조각에 얹고 크림 스티커
림과 오프셋 그림자를 두른 그림이다. `assets/app-icon/candidates/c2`를 `assets/app-icon/source.png`로
승격했다.

**팀 결정 전 임시다.** 다른 도상으로 정해지면 `source.png`를 그 파일로 갈아끼우고 `build.py`를
다시 돌린다 — 스크립트가 `source.png` 하나만 읽으므로 그 밖에 손댈 것이 없다. 탈락 후보 D3(한
문장의 피치 곡선 한 획)와 두 후보를 런처 크기(400/144/96/72/48px)로 나란히 놓은 비교 시트는
`candidates/`에 남아 있다.

앱 표시 이름은 **Accentury**다(2026-09-02 확정). Android `strings.xml`의 `app_name`, iOS
`Info-Debug.plist`·`Info-Release.plist`의 `CFBundleDisplayName`, Play 피처 그래픽에 박히는 글자
(`build.py`의 `FEATURE_TITLE`), 스토어 등록 정보가 전부 같은 값이어야 한다.

## 2. 아이콘 파이프라인 — 원본 한 장에서 전부

| 층 | 위치 | 성격 |
|---|---|---|
| 원본 | `assets/app-icon/source.png` + `prompt.md` | 생성 도구가 뽑은 그대로(1024×1024, **흰 배경**). 손으로 고치지 않는다 |
| 파생본 | 아래 표 전부 | **`build.py`가 만든다.** 직접 편집 금지 — 고칠 것은 스크립트나 원본이다 |

### 파생본

| 경로 | 규격 | 소비처 |
|---|---|---|
| `res/mipmap-{m,h,xh,xxh,xxx}dpi/ic_launcher_foreground.png` | 108dp 캔버스(108~432px), 투명, 도상은 62dp 상자 안 | adaptive icon 전경 |
| `res/mipmap-<density>/ic_launcher_monochrome.png` | 같은 캔버스, 흰 실루엣 | Android 13 테마 아이콘 |
| `res/values/colors.xml`의 `ic_launcher_background` | `#F3ECD9` | adaptive 배경 + 스플래시 창 배경 |
| `res/mipmap-anydpi/ic_launcher.xml` · `ic_launcher_round.xml` | `<adaptive-icon>` | 런처 |
| `res/mipmap-<density>/ic_launcher.png` · `ic_launcher_round.png` | 48dp(48~192px), 크림 꽉 참 | adaptive를 못 읽는 레거시 런처 |
| `res/drawable-<density>/ic_splash.png` | 288dp 캔버스(288~1152px), 가운데 192dp 원 안 | SplashScreen API |
| `xcassets/AppIcon.appiconset/AppIcon-1024.png` | 1024×1024 RGB, **알파 없음** | iOS 앱 아이콘 |
| `xcassets/LaunchIcon.imageset/` | 200/400/600px 투명 + `Contents.json` | iOS 런치 스크린 아이콘 |
| `xcassets/LaunchBackground.colorset/Contents.json` | sRGB `0.953/0.925/0.851` | iOS 런치 스크린 배경 |
| `assets/app-icon/store/play-icon-512.png` | 512×512 RGB, 1MB 이하 | Play Console 아이콘 |
| `assets/app-icon/store/play-feature-1024x500.png` | 1024×500 | Play 피처 그래픽 |
| `assets/app-icon/store/app-store-icon-1024.png` | iOS 1024 사본 | App Store Connect 업로드 편의 |

`colors.xml`의 한 줄만 **손으로 관리한다**(한 파일에 다른 색도 있어서). `build.py`는 그 줄이 맞는지
문자열로 검증만 한다.

### 배경 키잉과 종이색 정규화

키잉은 `assets/characters/build.py`와 같은 코드다 — 테두리 평균색을 배경으로 잡고, 채널 차 14
이하이면서 **바깥에서 이어진** 영역만 투명으로 만든다(flood-fill). 스티커 림이 배경보다 채널 최대
29 어두워 벽이 되고, 그 벽의 틈으로 새는 것은 침식 3px로 끊는다. 경계 픽셀은 배경 거리로 알파를
펴고 섞인 색을 되돌린다.

**종이색 정규화만 캐릭터 쪽과 다르다.** 캐릭터는 배경이 곧 종이색이라 "휘도가 높을수록 델타 적용"
휘도 램프로 됐다. 여기는 원본 배경이 흰색이고 종이는 도상 안에만 있어서, 기준색을 배경이 아니라
**도상 안 밝은 면(휘도 230 초과)의 중앙값**(`#FBF3E3`)에서 잡고 그 색과의 거리로 가중치를 준다.
휘도 램프를 쓰면 오프셋 그림자(휘도 약 206)까지 같이 밀려 이미 토큰 값인 `#CFC5AA`에서 벗어난다.
원본은 생성 결과 그대로 두고, 색 보정은 파생본이 책임진다.

스크립트는 Android Studio가 깔아 둔 기본 아이콘 잔재(`mipmap-*/ic_launcher*.webp`,
`drawable/ic_launcher_{background,foreground}.xml`)도 지운다. 남기면 같은 이름의 리소스가 둘이 된다.
축소는 알파를 미리 곱해서 한다 — 그냥 줄이면 투명 픽셀의 검정이 경계로 번진다.

### 재생성

```
/Users/iseongju/accentury/.venv/bin/python assets/app-icon/build.py
```

Pillow·numpy·scipy가 필요하다(위 venv에 있다). 끝에 **39행 검증표**를 찍고 하나라도 FAIL이면
종료 코드 1이다. 보는 것: 파일 존재·정확한 픽셀 크기·알파 유무·모서리 색·adaptive 전경 bbox가
66dp 안전 영역 안인지·스플래시 bbox가 192dp 원 안인지·용량 상한·기본 아이콘 잔재 0개.

## 3. Android 런처·스플래시 배선

**adaptive icon 안전 영역.** 108dp 캔버스 가운데 66dp만 항상 보인다 — 런처가 원·스퀴클·둥근 네모
마스크를 바꿔 씌우고 흔들기 애니메이션으로 최대 ±6dp까지 민다. 도상은 안전 영역 변에 닿지 않게
2dp를 더 물려 62dp 상자 안에만 그린다.

**배경은 drawable이 아니라 색 리소스다**(`@color/ic_launcher_background`). 단색 크림 한 장을 그리는
데 벡터/비트맵을 둘 이유가 없고, 색 리소스면 값이 `colors.xml` 한 곳에만 있다. 스플래시 창 배경
(`windowSplashScreenBackground`)이 같은 리소스를 재사용한다 — 스플래시에서 앱으로 넘어가는 순간
배경색이 갈리면 한 번 깜빡인 것처럼 보인다.

**monochrome 레이어**는 잉크 피복률만 알파로 남긴 흰 실루엣이다(휘도 60 미만 완전 불투명, 140 초과
완전 투명). 스티커 림(휘도 약 240)과 오프셋 그림자(약 206)는 뺀다 — 단색으로 칠하면 그림자가 같이
칠해져 도상이 두 겹으로 보인다. 전경을 그대로 monochrome으로 쓰면 크림 종이까지 단색이 돼 글자가
사라진다.

**테마 갈아끼우기.** 매니페스트의 `<activity>` theme만 `Theme.Accentury.Starting`이고
`<application>`은 `Theme.Accentury` 그대로다. 런처가 여는 액티비티라 창이 뜨는 순간의 테마가 곧
스플래시이고, 앱 전체 기본값을 스플래시 테마로 바꾸면 나중에 액티비티가 늘 때 그쪽까지 스플래시를
문다. 첫 프레임 뒤 `postSplashScreenTheme`(`Theme.Accentury`)로 되돌리는 것은 `installSplashScreen()`이
한다.

**`installSplashScreen()`은 `super.onCreate` 앞이어야 한다** — 이 호출이 하는 일이 창의 테마를
갈아끼우는 것이고, 창이 만들어진 뒤에 바꾸면 늦는다. `setKeepOnScreenCondition`은 걸지 않는다:
첫 화면이 웹뷰라 붙들 기준이 애매하고, 붙들면 그만큼 사용자가 아무것도 못 하는 시간이 늘어난다.

**core-splashscreen 1.2.0을 쓰는 이유는 minSdk 29다.** 플랫폼 SplashScreen은 API 31부터라 29·30에는
스플래시가 아예 없다. 라이브러리가 그 두 버전에 같은 화면을 만들어 준다.

**`windowLightStatusBar`를 스플래시 테마에 다시 적는다.** `Theme.Accentury.Starting`의 부모가
`Theme.SplashScreen`이라 `Theme.Accentury`의 값이 내려오지 않는다. 빼면 시스템 다크에서 스플래시가
떠 있는 동안만 흰 시계·배터리가 크림 위에 얹힌다(트러블슈팅 #44와 같은 계열).

**`mipmap-anydpi` 폴더명은 `-v26` 없이 그대로 둔다.** adaptive-icon XML은 API 26부터 읽히는데 이
앱의 minSdk가 29라, 버전 한정자를 붙여 26 미만을 갈라 줄 이유가 없다. 레거시 PNG는 adaptive를
안 읽는 일부 런처가 `@mipmap/ic_launcher`를 직접 집을 때를 위한 보험이다.

## 4. iOS 아이콘·런치 스크린

런치 스크린은 **스토리보드가 아니라 `UILaunchScreen` 딕셔너리**다. 우리 런치 화면은 "크림 배경 +
가운데 아이콘" 한 장이라, 스토리보드가 들고 오는 xib 파일·오토레이아웃·씬 편집이 전부 값 두 개로
줄어든다.

```xml
<key>UILaunchScreen</key>
<dict>
    <key>UIColorName</key>
    <string>LaunchBackground</string>
    <key>UIImageName</key>
    <string>LaunchIcon</string>
    <key>UIImageRespectsSafeAreaInsets</key>
    <true/>
</dict>
```

`UIColorName`·`UIImageName`은 **에셋 카탈로그의 이름**이지 파일명이 아니다
(`LaunchBackground.colorset` · `LaunchIcon.imageset`). `UIImageRespectsSafeAreaInsets`는 노치·
다이내믹 아일랜드에 아이콘이 물리지 않게 한다. `Info-Debug.plist`·`Info-Release.plist` 두 장에 같은
블록이 들어 있다 — 한쪽만 고치면 그 구성에서만 흰 화면이 뜬다.

`LaunchBackground.colorset`에 다크 대응 항목은 없다. 앱이 라이트 고정이다(`design-tokens.md` §7).

`project.yml`은 건드리지 않았다. 에셋 카탈로그는 폴더 전체가 이미 리소스로 물려 있어 새 imageset·
colorset이 자동으로 들어오고, plist는 xcconfig의 `INFOPLIST_FILE`이 가리키는 손으로 든 파일이다 —
XcodeGen에 plist 소유권을 넘기면 손으로 적은 내용이 지워진다(트러블슈팅 #59).

## 5. 스토어 스크린샷

`assets/screenshots/build.py`가 기기 캡처 한 장에 같은 틀을 씌워 규격별 파생본을 만든다.

```
raw/android/<화면>.png   에뮬레이터 캡처            ← 사람이 넣는다
raw/ios/<화면>.png       시뮬레이터 캡처            ← 사람이 넣는다
out/play/                1080×1920                (생성물, raw/android에서)
out/appstore-6.7/        1290×2796                (생성물, raw/ios에서)
out/appstore-6.1/        1179×2556                (생성물, raw/ios에서)
```

화면 id는 `01-intro` · `02-recording` · `03-vocab` · `04-result` 네 개 고정이다. `raw/`에 없는 화면은
검증표에 SKIP으로 찍고 넘어가므로 캡처를 한 장씩 채워 가며 돌려도 된다.

틀은 크림 바탕 + 위쪽 문구 두 줄(Jua, 잉크 헤드라인 + `#6B6459` 보조) + 아래 캡처다. 캡처는 둥근
네모가 아니라 **실제 기기 실루엣**에 끼운다 — 잉크 베젤, 옆면 버튼, `#CFC5AA` 오프셋 그림자.
그림자는 본체와 버튼을 한 덩어리로 묶은 마스크를 그대로 옮겨 찍는다(따로 그리면 버튼 그림자가
어긋난다).

기기는 플랫폼마다 다르다. 스토어에서 그 폰으로 찍은 것처럼 읽혀야 하기 때문이다.

| 원본 폴더 | 기기 | 딕셔너리 |
|---|---|---|
| `raw/ios/` | iPhone 17 Pro — 네 변 같은 베젤 2.6%, 화면 모서리 13.5%, 왼쪽 3 + 오른쪽 2 버튼 | `DEVICE_IPHONE_17_PRO` |
| `raw/android/` | Galaxy S25 — 각진 모서리 7%, 아래턱만 두꺼움, 펀치홀, 오른쪽 2 버튼 | `DEVICE_GALAXY_S25` |

치수는 전부 **화면 폭 대비 비율**이라 세 출력 규격에서 같은 그림이 나온다. `PLATFORM_DEVICE`가
원본 폴더를 기기에 잇는다 — **기기를 갈아끼우려면 딕셔너리만 고친다.** 버튼은 `(변, 본체 높이 대비
시작, 본체 높이 대비 길이)` 세 값이다.

캡션은 `build.py` 맨 위 `CAPTIONS` 딕셔너리 하나에 있다. 여기만 고치고 다시 돌리면 세 규격에
한꺼번에 반영된다. 캐릭터 스티커는 `STICKER_SCREEN`으로 켜고 끄며 **현재 `None`**이다(§6).

### 캡처 만드는 법

**Android** — 에뮬레이터를 가짜 마이크 빌드로 띄우고 화면마다 탭으로 진행한 뒤

```
~/Library/Android/sdk/platform-tools/adb exec-out screencap -p > assets/screenshots/raw/android/01-intro.png
```

가짜 마이크(`-PfakeMic`)가 물려 있어야 녹음 화면에 억양 곡선이 자란다. 워크트리에서 돌릴 때의
함정은 트러블슈팅 #63·#64를 본다.

**iOS** — 시뮬레이터를 `-AutoFlowDrive 1`로 띄워 인트로부터 결과까지 자동 완주시키고, 원하는 화면이
지날 때마다 스크린샷을 연달아 찍는다.

```
xcrun simctl io booted screenshot assets/screenshots/raw/ios/01-intro.png
```

캡처 크기는 달라도 된다(스크립트가 비율을 지켜 맞춘다). 세로 화면이어야 하고, 네 장의 크기는
플랫폼 안에서 맞추는 게 보기 좋다.

### 재생성

```
/Users/iseongju/accentury/.venv/bin/python assets/screenshots/build.py
... --raw <원본 폴더> --out <출력 폴더>   # 임시 캡처로 배치만 확인할 때
```

검증표는 규격 12줄(파일 존재·픽셀 크기·알파 없는 RGB·모서리 크림·8MB 이하)과 플랫폼별 버튼 여백
2줄(가장 바깥 버튼 픽셀이 프레임 변에서 3% 이상)이다. 버튼 여백이 FAIL이면 `MIN_DEVICE_W`·
`SIDE_MARGIN`을 줄이라는 신호다.

## 6. 결정 기록

- **C2 임시 확정** (2026-09-03) — 팀 결정 전까지 쓰는 값이다. 바꾸는 비용은 `source.png` 교체 + `build.py` 재실행 한 번이다.
- **앱 이름 Accentury** (2026-09-02) — Android·iOS·스토어·피처 그래픽 네 곳이 같은 값이다.
- **Play 피처 그래픽의 앱 이름은 종이 조각을 뺐다** (2026-09-03) — 공유 카드처럼 테두리·그림자를 두른 조각에 넣었더니 도상의 종이 림과 겹쳐 사각형이 하나 더 보였다. 잉크 글자만 둔다. 앱 이름·태그라인 말고 다른 문구는 넣지 않는다(Play 정책: 기기 사진·평점·가격 금지).
- **결과 화면 캐릭터 스티커 제거** (2026-09-03) — 결과 화면 캡처 안에 이미 같은 캐릭터가 있어 두 겹으로 보였다. `STICKER_SCREEN = None`으로 껐고, 되살리려면 `"04-result"`로 되돌린다.
- **기기 프레임은 실루엣 채택** — 둥근 네모로는 "어느 폰인지" 안 읽혀 실제 기기 실루엣을 그린다. 베젤 안쪽 띠는 크림이 아니라 `#CFC5AA`다(크림이면 베젤이 두 겹으로 갈라져 보인다).
- **캡처는 실루엣 전체가 보이는 크기로** — 9:16 프레임에 20:9 캡처를 넣으면 좌우가 16%씩 비고 기기가 프레임 폭의 66%가 되지만, 아래가 잘려 버튼이 사라지는 것보다 낫다고 봤다. 폭이 62% 아래로 떨어질 때만 폭을 키우고 아래를 흘린다(`MIN_DEVICE_W`).
- **결과 화면 등급 통일** — Android·iOS 결과 캡처가 서로 다른 등급이면 스토어에서 같은 앱으로 안 읽혀, iOS 쪽을 다시 찍어 둘 다 명예주민으로 맞췄다.
- **Android 상태바·내비게이션 바는 자르지 않는다** — Play가 허용하고, 자르기 시작하면 기기마다 기준이 갈린다.
- **iOS 캡처의 서체를 Android와 맞췄다** (2026-09-03) — 녹음 캡처의 대사 "밥 뭇나?"와 타이머만 iOS가 시스템 서체였다. 두 캡처가 스토어에 나란히 서면 같은 앱으로 안 읽히는 자리라 Jua를 번들하고(`ios-port.md` §3) `raw/ios/02-recording.png`를 다시 찍었다. 나머지 셋은 웹 화면이라 처음부터 Jua였다.
- **인트로·분석 대기 화면의 일러스트를 텍스트 히어로로 갈았다** (2026-09-03) — 오려 낸 종이 그림(확성기 든 사람·계단)이 스토어 캡처 크기로 줄면 선이 뭉개져 무엇을 그린 것인지 읽히지 않았고, 읽히더라도 앱이 무엇을 하는 곳인지는 말하지 않았다. 같은 자리에 Jua 40 잉크 한 줄을 세운다 — 인트로는 `사투리 좀 치나?`, 대기 화면은 `분석 중입니다`. 슬롯 높이(192·176)는 그대로라 아래 요소는 움직이지 않고, `raw/{android,ios}/01-intro.png`를 다시 찍어 프레임을 재생성했다.
- **텍스트 히어로에서 종이 조각을 걷어냈다** (2026-09-03) — 히어로가 대사 카드와 같은 테두리·면·그림자를 쓰니 한 화면에 종이가 두 장 겹쳐, 히어로가 아래 카드의 예고편처럼 읽혔다. 면을 지우고 잉크 글자만 남긴다. 문구도 `니 사투리 몇 등급?`에서 `사투리 좀 치나?`로 바꿨다 — 등급을 먼저 말하는 대신 말을 건다.
- **Play `01-intro` 캡션이 화면 문구와 겹치지 않게 갈았다** (2026-09-03) — 옛 캡션 `내 사투리, 몇 등급?`이 프레임 안 화면의 히어로와 같은 말을 두 번 했다. `3분이면 끝나는 사투리 테스트` / `음성 5 / 단어 5, 계정 없이`로 바꿔 캡션은 화면이 말하지 않는 것(길이·구성·가입 없음)만 말한다.
- **Jua에 없는 글자는 `build.py`가 막는다** (2026-09-03) — 새 캡션의 가운뎃점(U+00B7)이 Jua에 없어 두부(□)로 그려졌는데 검증 14개가 전부 통과했다. Pillow는 글리프가 없어도 조용히 그린다. `assert_glyphs()`가 캡션 글자를 cmap과 대조해 죽는다(fontTools가 없으면 건너뛴다). 구분자는 슬래시로 갈았다.

## 7. 이월

- **도상 팀 결정** — C2 대 D3. 노션 §1-12에 비교 시트와 원본 두 장을 올려 뒀다. 결정되면 `source.png` 교체 + `build.py` 재실행.
- **실기기 캡처** — 현재 스크린샷은 에뮬레이터(Android)·시뮬레이터(iOS) 캡처다. AC1은 이것으로 충족하지만, 실기기 캡처로 갈아 끼우면 더 낫다. 캡처만 `raw/`에 덮고 다시 돌리면 된다.
- **스토어 등록** — Play(KAN-174)·App Store(KAN-175)에 이 파일들을 올리는 것은 별도 티켓이다.
- **파비콘·OG 이미지** — KAN-179가 같은 도상을 재사용한다. 도상이 바뀌면 그쪽도 같이 다시 뽑아야 한다.
- **캡션 문구 팀 확정** — 네 화면의 헤드라인·보조 문구는 개발자가 초안을 잡은 것이다. `CAPTIONS` 한 곳만 고치면 세 규격에 반영된다.
