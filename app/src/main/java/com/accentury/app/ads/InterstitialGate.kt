package com.accentury.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

private const val TAG = "InterstitialGate"

/**
 * 분석 대기 화면의 전면 광고 (KAN-196, webview-bridge.md §8.3). 브리지 `showInterstitialAd`가 여기로 온다.
 *
 * **fire-and-forget이다.** 로드된 광고가 없으면 [show]는 아무 일도 하지 않고 웹에 회신도 없다 —
 * 대기 화면은 광고가 떴는지에 따라 달라질 것이 없다. 횟수(세션당 한 번)는 웹이 센다
 * (`ads/interstitial.ts`). 여기서는 받은 만큼 띄운다.
 *
 * ## 전면 광고 중 폴링
 *
 * 전면 광고는 SDK의 별도 Activity(AdActivity)가 우리 MainActivity 위에 서는 것이라 MainActivity는
 * onPause로 내려간다. WebView의 JS 타이머는 그래도 돈다 — 멈추는 것은 `WebView.pauseTimers()`를
 * 명시로 부를 때뿐이고 `WebViewHost`는 부르지 않는다 (onPause 훅 자체가 없다). 그래서 광고 아래에서
 * 분석 폴링이 그대로 돌고, 광고를 닫으면 결과 화면이 이미 와 있다 — §8.3이 전제한 그대로다.
 * Chromium이 가려진 페이지의 타이머를 초당 1회로 늦출 수는 있는데, 폴링 백오프 사다리
 * (`pollSchedule.ts`, 800·1200·2000·3000ms)에서 1초 밑은 첫 두 회차뿐이라 늦어도 수백 ms다.
 *
 * ## 스레드
 *
 * 전부 메인 스레드다. 브리지가 postToMain으로 넘기고, SDK 콜백도 메인으로 온다. 그래서 [loaded]·
 * [loading]에 동기화가 없다.
 *
 * @param context 로드용. Application 컨텍스트여도 된다 — 표시만 Activity가 필요하다
 * @param adUnitId 전면 광고 단위. 주입되지 않은 빌드는 Google 테스트 단위다 (build.gradle.kts)
 * @param consent 로드 시점의 동의 상태. 요청마다 다시 읽는다 ([buildAdRequest])
 */
class InterstitialGate(
    private val context: Context,
    private val adUnitId: String,
    private val consent: () -> AdConsent,
) {
    private var loaded: InterstitialAd? = null
    private var loading = false
    private val generation = AdLoadGeneration()

    /**
     * 다음 표시를 위해 미리 받아 둔다. 이미 있거나 받는 중이면 아무 일도 없다.
     *
     * **동의가 `unknown`이면 요청하지 않는다** ([shouldRequestAds]). [AdsController]의 프리로드만 거르면
     * [show]가 만드는 재로드 경로가 시트 전에 요청을 낸다 — 판정은 허브와 게이트가 같은 함수를 쓴다 (P1-1).
     *
     * 로드 실패에 재시도 루프를 두지 않는다 — 실패한 자리에서 곧바로 다시 요청하면 무효 트래픽으로
     * 잡힐 수 있어 SDK 문서가 말리는 패턴이다. 다음 기회는 [show]가 만든다: 보여줄 것이 없을 때
     * 한 번 더 받아 두므로 세션 하나가 광고 없이 지나가더라도 그다음 세션에는 있다.
     */
    fun preload() {
        if (!shouldRequestAds(consent())) return
        if (loaded != null || loading) return
        loading = true
        // 요청 시점의 세대를 들고 간다 — 로드 중에 동의가 바뀌어 discard()가 세대를 올리면 이 결과는 버린다.
        val token = generation.begin()
        InterstitialAd.load(
            context,
            adUnitId,
            buildAdRequest(consent()),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    if (!generation.isCurrent(token)) return
                    loading = false
                    loaded = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    if (!generation.isCurrent(token)) return
                    loading = false
                    loaded = null
                    // 값은 코드·도메인뿐이라 남겨도 된다 (KAN-38). 로드 실패는 정상 경로(무재고)라
                    // Crashlytics로는 보내지 않는다.
                    Log.w(TAG, "전면 광고 로드 실패: code=${error.code} domain=${error.domain}")
                }
            },
        )
    }

    /**
     * 받아 둔 광고를 버린다 — 동의가 바뀌어 요청 조건이 달라졌을 때 ([AdsController.setConsent]).
     *
     * 진행 중인 로드도 버린다 ([AdLoadGeneration]): 세대를 올려 그 콜백이 결과를 들이지 못하게 하고,
     * `loading`을 내려 뒤따르는 [preload]가 새 조건으로 곧바로 나가게 한다. 참조만 비우면 옛 조건으로
     * 나간 로드가 완료돼 `loaded`로 들어온다 (P1-2).
     */
    fun discard() {
        generation.invalidate()
        loading = false
        loaded = null
    }

    /** 로드된 광고가 있으면 띄운다. 없으면 아무 일도 없다 (회신 없음, §8.3). */
    fun show(activity: Activity) {
        val ad = loaded ?: run {
            preload()
            return
        }
        // 전면 광고는 일회용이다 — 참조를 먼저 비워 두 번 띄우는 경로를 막는다 (SDK 샘플 그대로).
        loaded = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preload()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.w(TAG, "전면 광고 표시 실패: code=${adError.code}")
                preload()
            }
        }
        ad.show(activity)
    }
}
