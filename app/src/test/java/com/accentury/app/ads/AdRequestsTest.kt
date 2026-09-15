package com.accentury.app.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdRequestsTest {

    @Test
    fun `허용만 맞춤형이다`() {
        assertTrue(personalizationAllowed(AdConsent.Granted))
    }

    @Test
    fun `거부는 비맞춤이다`() {
        assertFalse(personalizationAllowed(AdConsent.Denied))
    }

    @Test
    fun `아직 고르지 않았으면 비맞춤이다`() {
        // 시트 전에는 요청 자체가 없어야 하지만(AdsController), 어떤 경로로든 나간다면 npa다 —
        // "모르면 맞춤형"은 동의 없이 개인화하는 것이라 방침과 어긋난다 (§8.5).
        assertFalse(personalizationAllowed(AdConsent.Unknown))
    }

    // shouldRequestAds — "요청이 나가도 되는가". 게이트의 preload()와 허브의 프리로드가 같은 줄을 쓴다 (P1-1).

    @Test
    fun `허용이면 요청한다`() {
        assertTrue(shouldRequestAds(AdConsent.Granted))
    }

    @Test
    fun `거부여도 요청한다 - 비맞춤으로`() {
        // 거부는 "맞춤형을 하지 말라"이지 "광고를 내지 말라"가 아니다. 요청은 npa로 나간다.
        assertTrue(shouldRequestAds(AdConsent.Denied))
    }

    @Test
    fun `아직 고르지 않았으면 요청하지 않는다`() {
        // 시트가 뜨기 전이다. npa를 붙여도 "묻기 전에 광고 서버와 통신했다"가 된다 (§8.5).
        assertFalse(shouldRequestAds(AdConsent.Unknown))
    }
}
