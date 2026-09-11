package com.accentury.app.ads

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.accentury.app.AccenturyApplication
import com.accentury.app.BuildConfig
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration

/**
 * 광고 경로의 프로세스 단위 허브 (KAN-196). SDK 초기화·동의 저장·두 광고 게이트의 프리로드를 한
 * 곳에서 잇는다. [AccenturyApplication]이 하나 만들어 들고 있고 화면은 [from]으로 찾는다.
 *
 * ## 프로세스 단위인 이유
 *
 * 미리 받아 둔 광고는 회전·Activity 재생성을 넘겨야 한다 — 컴포지션의 remember에 두면 회전마다
 * 버려지고 다시 요청하는데, 그 요청은 노출 없는 광고 요청이라 채우기율만 깎는다. 동의 저장소도
 * 정본이 프로세스 밖(SharedPreferences)이라 소유자는 Application이 맞다.
 *
 * ## 프리로드 규칙 (webview-bridge.md §8.5 "동의 → SDK")
 *
 * **동의가 [AdConsent.Unknown]이면 광고 요청을 아예 내지 않는다.** 시트가 뜨기 전이라 사용자가
 * 아직 아무것도 고르지 않았고, 그 상태로 요청이 나가면 npa를 붙이더라도 "묻기 전에 광고
 * 서버와 통신했다"가 된다. 첫 `setAdConsent`(시트 선택)가 프리로드의 시작점이고, 그 뒤로는
 * 앱 시작마다 저장된 값으로 바로 받아 둔다. 동의가 바뀌면([setConsent]) 받아 둔 광고를 버리고
 * 새 조건으로 다시 받는다 — 허용으로 받아 둔 맞춤형 광고가 거부 뒤에 한 번 더 나가면 안 된다.
 *
 * ## 스레드
 *
 * SDK 초기화만 백그라운드 스레드다(문서 권고 — 메인을 막지 않게). 그 밖의 상태([sdkReady]·
 * 게이트의 로드 상태)는 전부 메인 스레드에서만 만진다: 브리지가 postToMain으로 넘기고, 초기화
 * 완료 콜백도 [mainHandler]로 넘긴다. 초기화 전에 온 프리로드 요청은 버려지고 완료 콜백이
 * 저장된 동의를 보고 다시 건다 — 그래서 "초기화가 끝나기 전에 시트를 골랐다"도 잃지 않는다.
 *
 * @param consentStore 동의 정본. 프로덕션은 SharedPreferences, 테스트는 가짜
 */
class AdsController(
    private val context: Context,
    val consentStore: AdConsentStore,
    interstitialAdUnitId: String,
    rewardedAdUnitId: String,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sdkReady = false

    val interstitial = InterstitialGate(context, interstitialAdUnitId, consent = consentStore::read)
    val rewarded = RewardedRetestAd(context, rewardedAdUnitId, consent = consentStore::read)

    /**
     * SDK를 세운다 (Application.onCreate). 두 번 부르지 않는다는 것은 호출자 책임이다.
     *
     * 요청 설정은 초기화 **앞**에 건다 — 초기화 시점에 나가는 첫 요청부터 적용돼야 해서다.
     * 아동 대상이 아니고(개인정보처리방침 7항) 동의 연령 미만 대상도 아님을 명시로 적는다.
     * 두 태그는 25.3.0에서 `setAgeRestrictedTreatment`로 대체 예고됐지만 그쪽 enum에는
     * "제한 없음"을 **명시하는** 값이 없다(UNSPECIFIED·CHILD·TEEN뿐, 곧 기본값과 같다). 명시
     * 선언을 남길 수 있는 것은 옛 태그뿐이라 그것을 쓰고, SDK가 태그를 지우는 판이 오면
     * 이 두 줄을 지우는 것으로 끝난다 — 기본값이 이미 "제한 없음"이다.
     * 근거: developers.google.com/admob/android/targeting, rel-notes 25.3.0 (2026-05-21)
     *
     * 테스트 기기 등록은 하지 않는다 — 디버그·미주입 빌드는 애초에 Google 테스트 광고 단위를
     * 쓰므로(build.gradle.kts) 어느 기기에서든 테스트 광고만 나온다.
     */
    fun initialize() {
        @Suppress("DEPRECATION")
        MobileAds.setRequestConfiguration(
            MobileAds.getRequestConfiguration().toBuilder()
                .setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE)
                .setTagForUnderAgeOfConsent(RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE)
                .build(),
        )
        // 문서 권고: 초기화는 백그라운드에서, 광고 로드는 메인에서.
        // developers.google.com/admob/android/quick-start#initialize_the_google_mobile_ads_sdk
        Thread({
            MobileAds.initialize(context) {
                mainHandler.post {
                    sdkReady = true
                    preloadIfConsented()
                }
            }
        }, "admob-init").start()
    }

    /**
     * 시트에서 고른 값을 적고 프리로드를 (다시) 건다. 브리지 `setAdConsent`가 메인 스레드에서 부른다.
     *
     * 같은 값이면 받아 둔 광고를 버리지 않는다 — 인트로 링크로 설정 시트를 열었다가 같은 것을 다시
     * 고르는 경로가 있고, 그때마다 버리고 다시 받으면 노출 없는 요청만 는다.
     */
    fun setConsent(consent: AdConsent) {
        val previous = consentStore.read()
        consentStore.write(consent)
        if (previous != consent) {
            interstitial.discard()
            rewarded.discard()
        }
        preloadIfConsented()
    }

    private fun preloadIfConsented() {
        if (!sdkReady) return
        // 게이트의 preload()도 같은 판정을 한다 — 여기서만 거르면 show/run의 재로드 경로가 샌다 (P1-1).
        if (!shouldRequestAds(consentStore.read())) return
        interstitial.preload()
        rewarded.preload()
    }

    companion object {
        /** 프로덕션 결선 — 저장소는 SharedPreferences, 광고 단위는 빌드가 주입한 값(없으면 테스트 단위). */
        fun create(context: Context): AdsController = AdsController(
            context = context.applicationContext,
            consentStore = SharedPreferencesAdConsentStore(context),
            interstitialAdUnitId = BuildConfig.ADMOB_INTERSTITIAL_ID,
            rewardedAdUnitId = BuildConfig.ADMOB_REWARDED_ID,
        )

        /** 화면이 Application이 든 인스턴스를 찾는 자리. */
        fun from(context: Context): AdsController =
            (context.applicationContext as AccenturyApplication).ads
    }
}
