# 광고 — Google AdMob (KAN-196)

1차 배포의 광고는 Google AdMob이다. 형식은 둘 — 분석 대기 화면의 **전면(interstitial) 1회**와
결과 화면 [다시 테스트하기]의 **보상형(rewarded)**. 맞춤형 여부는 사용자가 인트로 시트에서 고른
동의가 정한다. 브리지 계약과 웹 쪽 화면은 `webview-bridge.md` §8이 정본이고, 이 문서는 **네이티브
구현의 결정과 근거**다. Android(3단계)는 끝났고 iOS(4단계)는 §7에 맞춰야 할 것만 적어 두었다.

## 1. 결정 근거

- 사업자 결정: 지라 KAN-196 코멘트 (2026-09-11, 팀장) —
  https://accentury.atlassian.net/browse/KAN-196. AdFit 대비 스토어 게시 전 매체 등록·테스트
  광고가 가능하고 비맞춤 전환 플래그(npa)가 문서화돼 있다는 것이 근거였다
- 방침 반영: 1단계 커밋 `af35b1f` (2·4·10항, `web/public/privacy.html`)
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

## 4. 동의 → 요청

동의는 웹 시트가 묻고 네이티브가 저장한다 (§8.1). UMP SDK(Google의 동의 폼)는 쓰지 않는다 — 동의를
묻는 자리가 이미 웹 시트라 SDK 폼과 이중으로 물을 수 없고, 방침이 약속한 철회 경로(인트로 링크)도 웹이다.

| 저장값 (`ad_consent` / `state`) | 브리지 | 요청 | 프리로드 |
|---|---|---|---|
| (없음) | `unknown` | **요청 없음** | 하지 않는다 |
| `denied` | `denied` | `npa=1` | 한다 |
| `granted` | `granted` | 맞춤형 | 한다 |

- 판정은 순수 함수 `personalizationAllowed(consent)` 하나다 — `Granted`만 true (`AdRequests.kt`)
- **`unknown`이면 요청을 아예 내지 않는다.** 시트가 뜨기 전이라 npa를 붙이더라도 "묻기 전에 광고
  서버와 통신했다"가 된다. 첫 `setAdConsent`가 프리로드의 시작점이고, 그 뒤로는 앱 시작마다 저장값으로
  바로 받아 둔다 (`AdsController.preloadIfConsented`)
- 동의가 **바뀌면** 받아 둔 광고를 버리고 새 조건으로 다시 받는다 — 허용으로 받은 맞춤형 광고가 거부 뒤에
  한 번 더 나가면 안 된다. 같은 값을 다시 고르면 버리지 않는다(노출 없는 요청만 는다)
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

## 7. iOS (4단계) — 맞춰야 할 것

| 항목 | Android 값 | iOS |
|---|---|---|
| 동의 저장 | SharedPreferences 파일 `ad_consent`, 키 `state`, 값 `granted`/`denied` 문자열, 없으면 `unknown` | UserDefaults 키 `ad_consent.state` 권장 — 값·의미 같게. `getAdConsent`가 두 플랫폼에서 같은 저장 형식을 말해야 한다 |
| origin 거부 시 `getAdConsent` | 빈 문자열 (`getSessionToken`과 같은 규칙) | 같게 |
| `setAdConsent` 거름 | `granted`/`denied`만. `unknown`·계약 밖은 버리고 Crashlytics `bridge_parse_failed: setAdConsent` | 같게 |
| `AD_DISMISSED` payload | `{"code":"AD_DISMISSED","message":"광고를 끝까지 보시면 다시 테스트할 수 있어요","retryable":true,"retryAfterMs":null}` — `bridge/RetestFailure.kt` `adDismissedRetestFailure()` | 문구·필드 그대로. 문구 정본은 네이티브라 두 플랫폼이 같은 문장이어야 한다 |
| 프리로드 규칙 | `unknown`이면 요청 없음. 첫 `setAdConsent`가 시작. 동의 변경 시 받아 둔 것 폐기·재로드 | 같게 |
| npa | `GADExtras.additionalParameters = ["npa": "1"]` (iOS 대응) | `denied`·`unknown`에 npa |
| 상태기계 | `RewardedRetestGate` 네 갈래 + 표시 중 중복 무시 | 같은 표로 Swift 테스트 |
| `retestInFlight` | 광고 완주 뒤에 건다 | 같게 |
| ATT | 해당 없음 | 시트 동의와 ATT 프롬프트의 순서를 4단계에서 정한다. ATT 결과를 `setAdConsent`로 접지 말 것 (§8.5) |
| 스모크 구동기 | Android 스모크는 아직 광고 사전 세팅 없음 (§8.5 하단) | `-AutoFlowDrive` 진입 시 `denied`를 미리 쓰고 테스트 단위 사용 |

## 8. 파일 지도

| 파일 | 역할 |
|---|---|
| `app/src/main/java/com/accentury/app/ads/AdConsent.kt` | 동의 enum·브리지 문자열 매핑 |
| `…/ads/AdConsentStore.kt` | 저장소 인터페이스 + SharedPreferences 구현 |
| `…/ads/AdRequests.kt` | `personalizationAllowed`·`buildAdRequest`(npa) |
| `…/ads/InterstitialGate.kt` | 전면 광고 로드·표시 |
| `…/ads/RewardedRetestGate.kt` | 보상형 → 재응시 순수 상태기계 |
| `…/ads/RewardedRetestAd.kt` | 보상형 SDK 결선 |
| `…/ads/AdsController.kt` | 프로세스 단위 허브 — 초기화·동의 저장·프리로드 |
| `AccenturyApplication.kt` | 허브 생성·초기화 |
| `MainActivity.kt` `startRetest`/`proceedRetest` | 광고 게이트 → 기존 재응시 |
| `web/AccenturyBridge.kt` | `getAdConsent`·`setAdConsent`·`showInterstitialAd` |
| `bridge/RetestFailure.kt` | `adDismissedRetestFailure()` |
| `app/build.gradle.kts` | ID 주입·릴리스 빗장, `AndroidManifest.xml` APPLICATION_ID |
| 테스트 | `app/src/test/…/ads/{AdConsentTest,AdRequestsTest,RewardedRetestGateTest}.kt`, `web/AccenturyBridgeTest.kt` |
