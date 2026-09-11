package com.accentury.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

private const val TAG = "RewardedRetestAd"

/**
 * 재응시 보상형 광고의 SDK 결선 (KAN-196, webview-bridge.md §8.2). 결정은 [RewardedRetestGate]가 하고
 * 여기는 `RewardedAd`의 로드·표시·콜백을 그 상태 머신의 메서드로 옮길 뿐이다.
 *
 * 로드·소진·재로드 규칙은 [InterstitialGate]와 같다 — 미리 받아 두고, 띄우면 참조를 비우고,
 * 닫히면 다음 것을 받는다. 로드 실패에 재시도 루프가 없는 것도 같다.
 *
 * 전부 메인 스레드다 (브리지 postToMain, SDK 콜백).
 *
 * @param gate 상태 머신. 기본값은 새 인스턴스이고 테스트는 자기 것을 넣는다
 */
class RewardedRetestAd(
    private val context: Context,
    private val adUnitId: String,
    private val consent: () -> AdConsent,
    private val gate: RewardedRetestGate = RewardedRetestGate(),
) {
    private var loaded: RewardedAd? = null
    private var loading = false
    private val generation = AdLoadGeneration()

    /**
     * 다음 재응시를 위해 미리 받아 둔다. 이미 있거나 받는 중이면 아무 일도 없다.
     * 동의가 `unknown`이면 요청하지 않고([shouldRequestAds], P1-1), 로드 중 [discard]된 결과는 버린다 (P1-2) —
     * [InterstitialGate.preload]와 같은 규칙이다.
     */
    fun preload() {
        if (!shouldRequestAds(consent())) return
        if (loaded != null || loading) return
        loading = true
        val token = generation.begin()
        RewardedAd.load(
            context,
            adUnitId,
            buildAdRequest(consent()),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    if (!generation.isCurrent(token)) return
                    loading = false
                    loaded = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    if (!generation.isCurrent(token)) return
                    loading = false
                    loaded = null
                    Log.w(TAG, "보상형 광고 로드 실패: code=${error.code} domain=${error.domain}")
                }
            },
        )
    }

    /** 받아 둔 광고와 진행 중인 로드를 버린다 — 동의가 바뀌었을 때 ([AdsController.setConsent], [InterstitialGate.discard]와 같다). */
    fun discard() {
        generation.invalidate()
        loading = false
        loaded = null
    }

    /**
     * 재응시 요청 한 건을 게이트에 태운다.
     *
     * @param onProceed 재응시를 진행하라 — 기존 `beginRetest()` → 세션 생성 흐름이 여기서 시작된다.
     *   광고를 끝까지 봤을 때·광고가 없을 때·표시 실패 때 **정확히 한 번** 불린다
     * @param onDismissed 광고를 중도에 닫았다 — 웹에 `AD_DISMISSED`를 회신할 자리
     */
    fun run(activity: Activity, onProceed: () -> Unit, onDismissed: () -> Unit) {
        when (gate.request(loaded = loaded != null)) {
            RewardedGateDecision.Ignored -> return

            RewardedGateDecision.Proceed -> {
                // 광고 없이 통과한다 (§8.2 "광고 로드 실패는 막지 않는다"). 다음 재응시를 위해
                // 지금 한 번 더 받아 둔다 — 로드 실패 뒤의 유일한 재시도 기회다.
                preload()
                onProceed()
            }

            RewardedGateDecision.ShowAd -> {
                // request가 ShowAd를 돌려줬다는 것은 loaded가 있었다는 뜻이다.
                val ad = checkNotNull(loaded)
                loaded = null
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        if (gate.onDismissed() == RewardedGateDecision.Dismissed) onDismissed()
                        preload()
                    }

                    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                        Log.w(TAG, "보상형 광고 표시 실패: code=${adError.code}")
                        if (gate.onShowFailed() == RewardedGateDecision.Proceed) onProceed()
                        preload()
                    }
                }
                ad.show(activity) { _ ->
                    // 보상 종류·양은 보지 않는다 — 우리 보상은 "재응시 한 번"이고 그 사실은 콜백이
                    // 왔다는 것 자체다.
                    if (gate.onEarnedReward() == RewardedGateDecision.Proceed) onProceed()
                }
            }

            // 결정을 돌려주는 자리에서 나올 수 없는 값이다 — 콜백에서만 나온다.
            RewardedGateDecision.Dismissed -> return
        }
    }
}
