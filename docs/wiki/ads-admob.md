# 광고 — Google AdMob (KAN-196)

1차 배포의 광고는 Google AdMob이다. 형식은 둘 — 분석 대기 화면의 **전면(interstitial) 1회**와
결과 화면 [다시 테스트하기]의 **보상형(rewarded)**. 맞춤형 여부는 사용자가 인트로 시트에서 고른
동의가 정한다. 브리지 계약과 웹 쪽 화면은 `webview-bridge.md` §8이 정본이고, 이 문서는 **네이티브
구현의 결정과 근거**다. Android(3단계)·iOS(4단계) 둘 다 끝났고, iOS가 Android와 갈리는 지점은 §7이다.

## 1. 결정 근거

- 사업자 결정: 지라 KAN-196 코멘트 (2026-09-11, 팀장) —
  https://accentury.atlassian.net/browse/KAN-196. AdFit 대비 스토어 게시 전 매체 등록·테스트
  광고가 가능하고 비맞춤 전환 플래그(npa)가 문서화돼 있다는 것이 근거였다
- 방침 반영: 1단계 커밋 `af35b1f` (2·4·10항, `infra/privacy/privacy.html`)
- 계약·웹 시트: 2단계 커밋 `eacdbda`

## 2. SDK

| 항목 | 값 | 근거 |
|---|---|---|
| 아티팩트 | `com.google.android.gms:play-services-ads:25.4.0` | Google Maven `maven-metadata` latest = 25.4.0 (2026-09-11 확인); 릴리스 노트 2026-06-17 |
| 요구 사항 | minSdk 23+, compileSdk 35+ — 우리는 29 / 37 | developers.google.com/admob/android/quick-start |
| Firebase BoM과의 관계 | **별개**. BoM은 `firebase-*` 좌표만 잡는다. 버전은 `gradle/libs.versions.toml`의 `playServicesAds` | — |
| 초기화 | `MobileAds.initialize`를 백그라운드 스레드에서, 완료 콜백은 메인으로 넘겨 프리로드 | quick-start "Initialize the Google Mobile Ads SDK" |
| 전면 | `InterstitialAd.load` → `fullScreenContentCallback` → `show(activity)`. 일회용이라 표시 전에 참조를 비운다 | developers.google.com/admob/android/interstitial, 공식 Kotlin 샘플 `InterstitialExample` |
| 보상형 | `RewardedAd.load` → `show(activity, OnUserEarnedRewardListener)`. 보상 콜백이 닫힘 콜백보다 먼저 온다 | developers.google.com/admob/android/rewarded |
| 비맞춤 | `AdRequest.Builder().addNetworkExtrasBundle(AdMobAdapter::class.java, Bundle{ npa = "1" })` | 「Forward consent to the Google Mobile Ads SDK」 — web.archive.org/web/2021/https://developers.google.com/admob/android/eu-consent ("any version of the Google Mobile Ads SDK"). 현재 문서는 UMP/TCF 경로로 같은 것을 표현하지만 우리는 UMP를 쓰지 않는다(§4) |
| 연령 태그 | `setTagForChildDirectedTreatment(FALSE)`·`setTagForUnderAgeOfConsent(FALSE)` | developers.google.com/admob/android/targeting. 25.3.0에서 `setAgeRestrictedTreatment`로 대체 예고 — §6 |

### 2.1 API 확인 방법

SDK 사용법은 기억이 아니라 문서로 확정한다. Context7(`googleads/googleads-mobile-android-examples`)이
API 모양(load/show/콜백)을, Google Maven 메타데이터와 developers.google.com 페이지가 버전·요구
사항·태그 상수를 준다. npa extras는 현재 문서에서 사라져(UMP 권장) 아카이브 페이지가 근거다.

## 3. ID 주입

앱 ID 하나와 광고 단위 둘. 우선순위는 카카오 키(`kakaoNativeAppKey`)와 같다 —
`-P<키>=` → 환경변수 → `local.properties` → **없으면 Google 테스트 ID**.

| gradle `-P` · `local.properties` | 환경변수 | 없을 때 (Google 테스트 ID) | 어디로 가나 |
|---|---|---|---|
| `admobAppId` | `ADMOB_APP_ID` | `ca-app-pub-3940256099942544~3347511713` | 매니페스트 `com.google.android.gms.ads.APPLICATION_ID` (placeholder) |
| `admobInterstitialId` | `ADMOB_INTERSTITIAL_ID` | `ca-app-pub-3940256099942544/1033173712` | `BuildConfig.ADMOB_INTERSTITIAL_ID` |
| `admobRewardedId` | `ADMOB_REWARDED_ID` | `ca-app-pub-3940256099942544/5224354917` | `BuildConfig.ADMOB_REWARDED_ID` |

로컬 예시 (`local.properties`, gitignore 대상):

```
admobAppId=ca-app-pub-XXXX~YYYY
admobInterstitialId=ca-app-pub-XXXX/1111
admobRewardedId=ca-app-pub-XXXX/2222
```

**카카오와 갈리는 지점은 "없을 때"다.** 카카오는 빈 키로 기능을 끄지만 광고는 테스트 ID로 **켜
둔다** — 광고 없는 빌드는 대기 화면·재응시의 광고 결선을 한 번도 밟지 않아 검증이 안 되고, Google이
테스트 ID를 주는 이유가 정확히 그것이다. 디버그 빌드는 테스트 기기 등록이 필요 없다(테스트 단위 자체가
테스트 광고를 준다).

### 3.1 릴리스 빗장

테스트 ID가 스토어로 나가면 AdMob 정책 위반이다. `-PrequireAdMobIds=true`를 주면 세 값 중 하나라도
테스트 ID로 떨어졌을 때 **설정 단계에서 실패**한다 (`app/build.gradle.kts` `requireAdMobIds`,
`requireKakaoNativeAppKey`와 같은 꼴의 스위치).

**아직 릴리스 워크플로(`.github/workflows/app-release.yml`)에는 걸려 있지 않다.** 걸려면 (1) 저장소
시크릿 `ADMOB_APP_ID`·`ADMOB_INTERSTITIAL_ID`·`ADMOB_REWARDED_ID` 등록(AdMob 콘솔에서 앱·광고 단위
생성 뒤), (2) 시크릿 존재 검사 목록과 빌드 step `env:`에 세 이름 추가, (3) gradle 호출에
`-PrequireAdMobIds=true` 추가. 시크릿이 없는 채 (3)만 하면 릴리스가 실패하므로 순서는 (1) → (2)·(3)이다.
그때까지 릴리스 산출물은 테스트 ID로 나간다 — 스토어 제출 전 반드시 잠글 것.

iOS의 같은 빗장은 `REQUIRE_ADMOB_IDS=YES`(명령줄 빌드 설정)이고 `ios/project.yml`의 Release 전용 preBuild
스크립트가 판정한다 — §7.2. 아카이브 명령에 `ADMOB_APP_ID=… ADMOB_INTERSTITIAL_ID=… ADMOB_REWARDED_ID=…
REQUIRE_ADMOB_IDS=YES`를 함께 준다.

## 4. 동의 → 요청

동의는 웹 시트가 묻고 네이티브가 저장한다 (§8.1). UMP SDK(Google의 동의 폼)는 쓰지 않는다 — 동의를
묻는 자리가 이미 웹 시트라 SDK 폼과 이중으로 물을 수 없고, 방침이 약속한 철회 경로(인트로 링크)도 웹이다.

| 저장값 (`ad_consent` / `state`) | 브리지 | 요청 | 프리로드 |
|---|---|---|---|
| (없음) | `unknown` | **요청 없음** | 하지 않는다 |
| `denied` | `denied` | `npa=1` | 한다 |
| `granted` | `granted` | 맞춤형 | 한다 |

- 판정은 순수 함수 둘이다 (`AdRequests.kt`, iOS Core `AdConsent.swift`) — "어떤 요청인가"는
  `personalizationAllowed(consent)`(`Granted`만 true), "요청이 나가도 되는가"는 `shouldRequestAds(consent)`
  (`Unknown`만 false)
- **`unknown`이면 요청을 아예 내지 않는다.** 시트가 뜨기 전이라 npa를 붙이더라도 "묻기 전에 광고
  서버와 통신했다"가 된다. 첫 `setAdConsent`가 프리로드의 시작점이고, 그 뒤로는 앱 시작마다 저장값으로
  바로 받아 둔다 (`AdsController.preloadIfConsented`). **게이트의 `preload()` 첫 줄도 같은 판정을 한다**
  (리뷰 P1-1, 2026-09-11) — 처음엔 허브만 걸렀는데 `show`/`run`이 "받아 둔 것이 없으면 한 번 더 받는"
  경로가 시트를 우회할 수 있다(iOS 구동기의 JS click, 심 경합으로 `getAdConsent()`가 `""`인 찰나). 요청이
  나가는 자리가 셋이면 판정도 셋이 같은 함수를 써야 한다
- 동의가 **바뀌면** 받아 둔 광고를 버리고 새 조건으로 다시 받는다 — 허용으로 받은 맞춤형 광고가 거부 뒤에
  한 번 더 나가면 안 된다. 같은 값을 다시 고르면 버리지 않는다(노출 없는 요청만 는다)
- **로드 중에 바뀌어도 버린다** (리뷰 P1-2). SDK 로드에는 취소가 없어 `discard()`가 참조만 비우면 옛
  조건으로 나간 요청이 잠시 뒤 완료돼 `loaded`로 들어오고, `loading`이 선 채라 새 조건의 `preload()`는
  물러난다. 그래서 게이트마다 세대 카운터 `AdLoadGeneration`(`begin`/`invalidate`/`isCurrent`)을 두고 —
  `discard()`가 세대를 올리고 `loading`을 내리며, 로드 콜백은 요청 시점 토큰이 현재 세대가 아니면 성공·실패
  둘 다 버린다. 순수 클래스라 JVM·`swift test`가 세 갈래를 못박는다 (`AdLoadGenerationTest[s]`)
- 초기화 전에 온 `setAdConsent`는 잃지 않는다 — 초기화 완료 콜백이 저장값을 다시 본다

## 5. 흐름

### 5.1 전면 (분석 대기 화면)

```
웹: 대기 화면 마운트 → showInterstitialAdOnce(sessionId)   ← 세션당 1회는 웹이 센다
  → AccenturyBridge.showInterstitialAd() → postToMain, origin 검증
  → InterstitialGate.show(activity)
       받아 둔 광고 없음 → preload() 만 걸고 끝 (회신 없음)
       있음 → 참조 비우고 show → 닫히면/표시 실패면 preload()
```

**전면 광고 중 폴링은 멈추지 않는다.** 광고는 SDK의 별도 Activity가 MainActivity 위에 서는 것이라
MainActivity는 onPause로 내려가지만, WebView의 JS 타이머는 `WebView.pauseTimers()`를 명시로 부를 때만
멈춘다 — `WebViewHost`는 부르지 않는다(onPause 훅 자체가 없다). 그래서 광고 아래에서 분석 폴링이 그대로
돌고, 닫으면 결과 화면이 와 있다. Chromium이 가려진 페이지의 타이머를 초당 1회로 늦출 수는 있는데
백오프 사다리(800·1200·2000·3000ms)에서 1초 밑은 첫 두 회차뿐이라 늦어도 수백 ms다. 그래서 별도
조정 없이 둔다.

### 5.2 보상형 (재응시)

```
웹: [광고 보고 다시 테스트하기] → startRetest() (pending 잠금)
  → AccenturyBridge.startRetest() → postToMain, origin 검증
  → MainActivity.startRetest() → RewardedRetestAd.run(activity, onProceed, onDismissed)
       gate.request(loaded)
         Ignored  (표시 중)          → 아무 일 없음
         Proceed  (받아 둔 광고 없음) → preload(); onProceed()
         ShowAd                       → show(activity)
              보상 콜백  → gate.onEarnedReward() = Proceed → onProceed()
              닫힘       → gate.onDismissed()   = Dismissed → onDismissed() (보상 뒤 닫힘은 Ignored)
              표시 실패  → gate.onShowFailed()  = Proceed → onProceed()
              (셋 다) preload()
  onProceed  = proceedRetest(): sessionGate.beginRetest() → POST /v0/sessions(previousToken) → 인트로 리로드 / onRetestFailed
  onDismissed = onRetestFailed({code:"AD_DISMISSED", message:"광고를 끝까지 보시면 다시 테스트할 수 있어요", retryable:true, retryAfterMs:null})
```

- 갈래는 순수 상태기계 `RewardedRetestGate`가 정하고 JVM 테스트가 못박는다 (`RewardedRetestGateTest`).
  SDK 결선(`RewardedRetestAd`)은 콜백을 그 메서드로 옮길 뿐이다
- **`beginRetest()`(retestInFlight)는 광고 완주 뒤에 건다.** 그 플래그는 "세션 요청이 나가 있다"는 뜻이고
  광고 시청은 그 앞 단계다. 광고 앞에서 걸면 광고 도중 회전으로 코루틴 스코프가 취소됐을 때 요청은 없는데
  플래그만 선 상태가 생긴다. 광고 중 두 번째 탭은 웹의 pending 잠금과 게이트의 `showing`이 막는다
- 보상 콜백이 닫힘보다 먼저 오므로 세션 생성·인트로 리로드는 광고가 닫히기 전에 시작된다. 닫으면 인트로가
  기다리고 있다
- 로드 실패·표시 실패는 광고 없이 통과 (§8.2 "응시 흐름을 막지 않는다")
- 보상 콜백 ~ 닫힘 사이에 회전하면 `proceedRetest`의 코루틴 스코프(Activity)가 취소돼 세션 요청이 끊기는
  창이 있다 (리뷰 P2-4, 2026-09-11). 광고가 닫힌 뒤 인트로가 안 오면 웹의 pending 잠금이 풀리지 않으므로
  사용자는 다시 탭한다 — 보상형 광고 중 회전은 드물고 SDK가 광고 Activity의 방향을 고정하므로 수정 없이 기록만 둔다

### 5.3 로드 실패 뒤

재시도 루프를 두지 않는다 — 실패 자리에서 곧바로 다시 요청하는 것은 SDK 문서가 말리는 패턴이다(무효
트래픽). 다음 기회는 **사용자 행동**이 만든다: 보여줄 것이 없는 `show`/`run`이 한 번 더 받아 두므로
세션 하나는 광고 없이 지나가더라도 그다음에는 있다.

## 6. 연령 태그와 GA4 광고 ID

**아동 대상이 아니다** (개인정보처리방침 7항). `AdsController.initialize`가 초기화 앞에
`TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE`·`TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE`를 명시로 건다.
두 태그는 25.3.0(2026-05-21)에서 `setAgeRestrictedTreatment(AgeRestrictedTreatment)`로 대체 예고됐는데
그 enum은 `UNSPECIFIED`·`CHILD`·`TEEN`뿐이라 "제한 없음"을 **명시**하는 값이 없다(UNSPECIFIED = 기본값).
명시 선언을 남길 수 있는 것은 옛 태그뿐이라 `@Suppress("DEPRECATION")`으로 쓰고, SDK가 태그를 지우는
판(26.x 예상)이 오면 두 줄을 지우면 된다 — 기본값이 이미 "제한 없음"이다.

**매니페스트의 `google_analytics_adid_collection_enabled=false`·
`google_analytics_default_allow_ad_personalization_signals=false`는 그대로다.** 이 두 키는 Firebase
Analytics(GA4) 계측에 광고 ID를 붙일지를 정할 뿐이고 AdMob SDK는 광고 요청에 쓸 GAID를 자기 경로로
읽는다. 계측은 계속 익명(FR-AN-09)이고 광고만 동의를 따른다 — `analytics.md` 「사용자 식별자와 광고
식별자를 붙이지 않는다」.

## 7. iOS 구현 (4단계)

Android 3단계를 거울처럼 옮겼다 — 동의 저장 형식·npa·프리로드 규칙·보상형 상태기계·`AD_DISMISSED` payload가
전부 같다. 갈리는 것은 플랫폼이 강제하는 셋(ATT, SwiftPM, Info.plist)뿐이다.

### 7.1 SDK

| 항목 | 값 | 근거 |
|---|---|---|
| 패키지 | `googleads/swift-package-manager-google-mobile-ads` **13.9.0** (exactVersion) | GitHub 태그 목록 2026-09-11 확인, 릴리스 노트 2026-08-26. 카카오·Firebase와 같은 "태그를 못 박는다" 규칙 (`ios/project.yml`) |
| 요구 사항 | iOS 13+ (13.0.0), Xcode 26.2+ (13.4.0) — 우리는 iOS 16 / Xcode 26.6 | rel-notes 13.0.0·13.4.0 |
| 초기화 | `MobileAds.shared.start { }` — SDK가 내부에서 비동기로 돌고 완료를 메인으로 준다. Android가 별도 스레드를 만든 자리가 없다 | developers.google.com/admob/ios/quick-start |
| 전면 | `InterstitialAd.load(with:request:completionHandler:)` → `fullScreenContentDelegate` → `present(from:)` | developers.google.com/admob/ios/interstitial (v12 API 이름) |
| 보상형 | `RewardedAd.load(with:request:completionHandler:)` → `present(from:userDidEarnRewardHandler:)`. 보상 핸들러가 닫힘 delegate보다 먼저 온다 | developers.google.com/admob/ios/rewarded |
| 비맞춤 | `Extras().additionalParameters = ["npa": "1"]` + `Request.register(_:)` | developers.google.com/admob/ios/targeting (network extras), Android와 같은 아카이브 근거 |
| 연령 태그 | `requestConfiguration.tagForChildDirectedTreatment = false`·`tagForUnderAgeOfConsent = false` — **13.3.0(2026-04-27)에서 deprecated**, 대체 `ageRestrictedTreatment`는 `.unspecified/.child/.teen`뿐이라 Android §6과 같은 판단으로 옛 태그를 쓴다. Swift에는 `@Suppress`가 없어 **deprecated 경고 2줄이 빌드에 남는다(의도)** | rel-notes 13.3.0, developers.google.com/admob/ios/targeting |
| 전이 의존 | `GoogleUserMessagingPlatform`(UMP)이 따라 들어온다. 링크만 되고 호출 코드 없음 (§4) | 패키지 `Package.swift` |
| 최상단 VC | 광고 `present(from:)`에 `TopViewController.current()`를 넘긴다 — `ExternalBrowser`(Safari 시트)가 쓰던 탐색을 `ios/Accentury/UI/TopViewController.swift`로 빼서 둘이 같은 규칙 | 이미 떠 있는 시트 위에 얹어야 iOS가 무시하지 않는다 |

Android와 달리 **광고 SDK가 AdSupport·AppTrackingTransparency를 링크한다.** KAN-33 때 `FirebaseAnalyticsCore`를
골라 "IDFA 코드가 바이너리에 없다"고 적어 둔 서술(`FirebaseEventSink.swift`, `project.yml`, `analytics.md`)은
그래서 더는 사실이 아니다. Core product는 **유지한다** — 그 선택이 지키는 것은 "계측 SDK가 IDFA를 안 읽는다"이고,
광고 SDK가 자기 경로로 IDFA를 읽는 것과는 별개다. 계측은 익명(FR-AN-09), 광고만 동의를 따른다 (§6과 같은 구도).

### 7.2 ID 주입 · 릴리스 빗장

| xcconfig / 명령줄 | 기본값 (Google 테스트 ID) | 어디로 가나 |
|---|---|---|
| `ADMOB_APP_ID` | `ca-app-pub-3940256099942544~1458002511` | `Info-*.plist` `GADApplicationIdentifier` (SDK가 읽는다) |
| `ADMOB_INTERSTITIAL_ID` | `ca-app-pub-3940256099942544/4411468910` | `Info-*.plist` → `AppConfig.admobInterstitialId` |
| `ADMOB_REWARDED_ID` | `ca-app-pub-3940256099942544/1712485313` | `Info-*.plist` → `AppConfig.admobRewardedId` |

기본값은 `ios/Accentury/Config/Base.xcconfig`에 있고 `Local.xcconfig`(gitignore)나 명령줄
`xcodebuild ... ADMOB_APP_ID=...`가 덮는다 — 카카오 `KAKAO_NATIVE_APP_KEY`와 같은 사슬. Android처럼 **없으면
테스트 ID로 켜 둔다**(§3). AdMob 콘솔의 iOS 앱은 Android 앱과 별개라 ID도 별개다.

**빗장**: `project.yml`의 preBuild 스크립트 «AdMob 테스트 ID 빗장 (Release)». `CONFIGURATION=Release`이고
`REQUIRE_ADMOB_IDS=YES`일 때 세 값 중 하나라도 `ca-app-pub-3940256099942544`이면 컴파일 전에 실패한다
(2026-09-11 확인: 세 값 모두 걸려 `BUILD FAILED`). 기본은 꺼짐 — 시크릿 없는 기계·CI가 아카이브부터 못 하면 안 된다.
릴리스 워크플로에 걸 순서는 §3.1과 같다: 시크릿 등록 → 명령줄 인자 → `REQUIRE_ADMOB_IDS=YES`.

### 7.3 Info.plist

- `GADApplicationIdentifier = $(ADMOB_APP_ID)`
- `NSUserTrackingUsageDescription` — "허용하시면 관심사에 맞는 광고를 보여 드려요. 허용하지 않으셔도 광고는 나오지만
  맞춤형이 아닌 일반 광고만 나와요." (웹 시트 `AD_CONSENT_EFFECT`와 같은 취지)
- `SKAdNetworkItems` — quick-start 「Update your Info.plist」의 전체 목록 **50개**(2026-09-11) 그대로. Debug·Release 두
  plist에 같은 배열. **갱신 방법**: 문서 목록을 다시 받아 배열을 통째로 바꾼다. 한 항목이 빠져도 빌드·실행은 멀쩡하고 그
  네트워크의 전환 집계만 조용히 빠진다

### 7.4 동의 저장 · 브리지

| 항목 | Android | iOS |
|---|---|---|
| 동의 저장 | SharedPreferences `ad_consent`/`state` | `UserDefaults.standard` 키 **`ad_consent.state`**, 값 문자열 같음. 깨진 값은 `unknown` (`UserDefaultsAdConsentStore`, Core) |
| `getAdConsent` | JS 스레드가 저장소를 동기로 읽음 | 토큰과 같은 심 — 문서 변수 `adConsent`, setter `__accenturySetAdConsent`, 대기 자리 `__accenturyPendingAdConsent`. 저장값이 바뀌면(`AdsController.consent`) `WebViewHost`가 `didCommit`·`didFinish`·갱신마다 다시 민다. origin 거부 문서는 `""` |
| `setAdConsent` 거름 | `granted`/`denied`만, 그 외 Crashlytics | 같음 (`BridgeDispatcher`) |
| `AD_DISMISSED` | `adDismissedRetestFailure()` | Core `adDismissedRetestFailure()` — JSON까지 테스트로 대조 |
| 재응시 | `MainActivity.startRetest` → `RewardedRetestAd.run` → `proceedRetest` | `TestFlowView.handleRetest` → `AdsController.runRewardedRetest` → `proceedRetest` → `TestFlowModel.startRetest`(여기서 `beginRetest`) |

### 7.5 ATT — 시트 동의와 프롬프트의 순서 (4단계 결정)

**`setAdConsent("granted")`가 들어온 직후 `ATTrackingManager.requestTrackingAuthorization`을 부른다.**
`AdsController.setConsent` → `preloadAfterTrackingSettled` → `TrackingAuthorization.requestIfUndetermined`.
"지금 물어야 하나"는 Core의 순수 함수 `shouldRequestTracking(consent:status:)`(`granted && notDetermined`)이고
`swift test`가 네 갈래를 못박는다 (리뷰 P2-6). ATT 상태는 앱 타깃 `TrackingAuthorization.status`가 Core의
`TrackingStatus`로 옮겨 넘긴다 — Core는 AppTrackingTransparency를 링크하지 않는다.

- 시트가 먼저여야 ATT 프롬프트가 맥락을 가진다 — 사용자가 방금 「맞춤형 광고 허용」을 골랐고, iOS는 그 허용을 실행하려면
  기기 식별자 접근을 한 번 더 확인한다. `NSUserTrackingUsageDescription` 문구가 그 맥락으로 적혀 있다
- **`denied`면 ATT를 부르지 않는다.** 추적 자체가 없는데 추적 허용을 묻는 것은 사용자에게도 심사에도 설명이 안 된다.
  npa 요청은 IDFA가 필요 없다
- **ATT 결과는 저장하지 않고 `setAdConsent`로 접지도 않는다** (`webview-bridge.md` §8.5). 시트 값은 사용자가 고른 것이어야
  「맞춤형 광고 설정」이 보여 주는 상태와 맞는다. ATT 거부로 SDK가 IDFA를 못 읽으면 맞춤형이 사실상 비맞춤이 되는데 그건 SDK 몫이다
- 앱 시작에 이미 `granted`면 ATT가 `notDetermined`일 때만 한 번 더 묻는다(설정에서 추적을 초기화한 경우). 이미 답이 있으면
  iOS가 다시 띄우지 않으므로 부르지 않는다
- 맞춤형 프리로드는 ATT 답이 난 **뒤**에 건다 — 프롬프트 중에 나간 요청은 IDFA 없이 나가 첫 광고가 비맞춤이 된다
- 프롬프트는 앱이 active일 때만 뜬다. 앱 시작 경로는 `didBecomeActive`를 한 번 기다린다 (`TrackingAuthorization.whenActive`).
  프롬프트가 떠 있는 동안 두 번째 요청(SDK 초기화 완료와 시트 선택이 겹침)은 시스템에 다시 묻지 않고 같은 답을 기다린다

App Store 개인정보 라벨·«추적» 항목(`analytics.md` KAN-175 표)은 이 티켓으로 바뀐다 — 맞춤형 광고를 허용한 사용자에
한해 IDFA가 광고 목적으로 쓰이므로 «추적: 광고 식별자» 신고가 필요하다. KAN-175에서 갱신.

### 7.6 스모크

`-AutoFlowDrive 1`·`-AutoStartSmoke 1`이면 `AdsController.start`가 저장소에 `denied`를 미리 쓰고
`adsSuppressed = true`로 전면·보상형 호출을 광고 없이 통과시킨다 (`ADS: smoke consent=denied suppressed=true`).
Debug 빌드 한정, 릴리스에는 그 블록이 없다. `webview-bridge.md` §8.5 하단 참고.

**실기기 미확인 항목** (시뮬레이터에서 테스트 광고는 뜨지만 ATT 프롬프트·IDFA는 시뮬레이터 값이 다르다):
ATT 시트가 시트 허용 직후 뜨는지, 허용/거부 뒤 첫 맞춤형 광고 요청, 보상형 완주 → 인트로 리로드, 중도 닫힘 → `AD_DISMISSED`,
**iOS 첫 실행 동의 시트 노출** — 심(`adConsent` 문서 변수)의 push 타이밍이 인트로의 첫 `getAdConsent()`보다 늦으면
`""`가 읽혀 시트가 안 뜰 수 있다 (§7.4, 리뷰 P2-5). 실기기에서 첫 설치 → 인트로 → 시트가 서는지 본다.

## 8. 파일 지도

| 파일 | 역할 |
|---|---|
| `app/src/main/java/com/accentury/app/ads/AdConsent.kt` | 동의 enum·브리지 문자열 매핑 |
| `…/ads/AdConsentStore.kt` | 저장소 인터페이스 + SharedPreferences 구현 |
| `…/ads/AdRequests.kt` | `personalizationAllowed`·`shouldRequestAds`·`buildAdRequest`(npa) |
| `…/ads/AdLoadGeneration.kt` | 로드 세대 카운터 — 로드 중 동의 변경 시 옛 결과 폐기 |
| `…/ads/InterstitialGate.kt` | 전면 광고 로드·표시 |
| `…/ads/RewardedRetestGate.kt` | 보상형 → 재응시 순수 상태기계 |
| `…/ads/RewardedRetestAd.kt` | 보상형 SDK 결선 |
| `…/ads/AdsController.kt` | 프로세스 단위 허브 — 초기화·동의 저장·프리로드 |
| `AccenturyApplication.kt` | 허브 생성·초기화 |
| `MainActivity.kt` `startRetest`/`proceedRetest` | 광고 게이트 → 기존 재응시 |
| `web/AccenturyBridge.kt` | `getAdConsent`·`setAdConsent`·`showInterstitialAd` |
| `bridge/RetestFailure.kt` | `adDismissedRetestFailure()` |
| `app/build.gradle.kts` | ID 주입·릴리스 빗장, `AndroidManifest.xml` APPLICATION_ID |
| 테스트 | `app/src/test/…/ads/{AdConsentTest,AdRequestsTest,AdLoadGenerationTest,RewardedRetestGateTest}.kt`, `web/AccenturyBridgeTest.kt` |

### 8.1 iOS

| 파일 | 역할 |
|---|---|
| `ios/AccenturyCore/Sources/AccenturyCore/Ads/AdConsent.swift` | 동의 enum·브리지 문자열, `personalizationAllowed`·`shouldRequestAds`·`shouldRequestTracking`·`TrackingStatus` |
| `…/Ads/AdLoadGeneration.swift` | 로드 세대 카운터 (Android와 같다) |
| `…/Ads/AdConsentStore.swift` | 저장소 프로토콜 + `UserDefaultsAdConsentStore`(키 `ad_consent.state`) |
| `…/Ads/RewardedRetestGate.swift` | 보상형 → 재응시 순수 상태기계 (Android와 같은 표) |
| `…/Bridge/RetestFailure.swift` | `adDismissedRetestFailure()`·`codeAdDismissed` |
| `ios/Accentury/Ads/AdRequests.swift` | `AdRequests.make(consent:)` — npa extras |
| `ios/Accentury/Ads/InterstitialGate.swift` | 전면 광고 로드·표시 (`FullScreenContentDelegate`) |
| `ios/Accentury/Ads/RewardedRetestAd.swift` | 보상형 SDK 결선 |
| `ios/Accentury/Ads/TrackingAuthorization.swift` | ATT 프롬프트 — active 대기, 중복 요청 합치기, `status`(Core `TrackingStatus`로 옮김) |
| `ios/Accentury/Ads/AdsController.swift` | 프로세스 허브 `shared` — 초기화·연령 태그·동의 저장·ATT 순서·프리로드·스모크 스위치 |
| `ios/Accentury/UI/TopViewController.swift` | 광고·Safari 시트가 present할 최상단 VC |
| `ios/Accentury/AccenturyApp.swift` | `AdsController.shared.start()` (Firebase 뒤) |
| `ios/Accentury/TestFlow/TestFlowView.swift` `handleRetest`/`proceedRetest` | 광고 게이트 → 기존 재응시 |
| `ios/Accentury/Web/BridgeUserScript.swift` | `getAdConsent`·`setAdConsent`·`showInterstitialAd` 심, `adConsentPushJs` |
| `ios/Accentury/Web/AccenturyBridge.swift` | `setAdConsent` 거름·`showInterstitialAd` 라우팅 |
| `ios/Accentury/Web/WebViewHost.swift` | `adConsent` 값 push (`pushAdConsent`) |
| `ios/Accentury/AppConfig.swift`, `Config/Base.xcconfig`, `Info-*.plist`, `project.yml` | ID 주입·plist 키·SwiftPM·릴리스 빗장 |
| 테스트 | Core `Tests/AccenturyCoreTests/Ads/{AdConsentTests,UserDefaultsAdConsentStoreTests,AdRequestsTests,AdLoadGenerationTests,RewardedRetestGateTests}.swift`, `Bridge/RetestFailedDeliveryTests.swift`(`AdDismissedRetestFailureTests`); 앱 `AccenturyTests/{AccenturyBridgeTests,BridgeUserScriptTests}.swift` |
