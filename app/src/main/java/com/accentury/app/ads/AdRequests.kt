package com.accentury.app.ads

import android.os.Bundle
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdRequest

/**
 * 동의 → 맞춤형 허용 판정 (KAN-196, webview-bridge.md §8.5 "동의 → SDK").
 *
 * **[AdConsent.Granted]만 true다.** [AdConsent.Denied]는 당연하고, [AdConsent.Unknown]도 false —
 * 시트가 뜨기 전이라 광고 요청 자체가 없어야 하지만([AdsController]가 그렇게 막는다), 어떤
 * 경로로든 요청이 나간다면 비맞춤이 안전한 쪽이다. "모르면 맞춤형"은 동의 없이 개인화하는 것이라
 * 방침(6항)이 약속한 것과 어긋난다.
 *
 * 순수 함수로 뺀 이유는 이 한 줄이 곧 동의의 법적 의미이기 때문이다 — JVM 테스트가 못박는다.
 */
fun personalizationAllowed(consent: AdConsent): Boolean = consent == AdConsent.Granted

/**
 * 동의 상태에 맞는 광고 요청을 만든다.
 *
 * 비맞춤은 AdMob 어댑터 extras `npa=1`이다. Google Mobile Ads SDK 문서 「Forward consent to the
 * Google Mobile Ads SDK」의 방식 그대로이고("any version of the Google Mobile Ads SDK"), 현재
 * 문서는 UMP SDK의 TCF 문자열로 같은 것을 표현하지만 우리는 UMP를 쓰지 않는다 — 동의를 묻는
 * 자리가 웹 시트(KAN-196 2단계)라 SDK의 동의 폼과 이중으로 물을 수 없다.
 * 근거: web.archive.org/web/2021/https://developers.google.com/admob/android/eu-consent
 *
 * 요청 객체를 미리 만들어 두지 않고 로드 때마다 만드는 이유: 동의는 인트로 링크로 언제든 바뀌고
 * (§8 「맞춤형 광고 설정」), 요청은 그 시점의 값으로 나가야 한다.
 */
fun buildAdRequest(consent: AdConsent): AdRequest {
    val builder = AdRequest.Builder()
    if (!personalizationAllowed(consent)) {
        val extras = Bundle().apply { putString("npa", "1") }
        builder.addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
    }
    return builder.build()
}
