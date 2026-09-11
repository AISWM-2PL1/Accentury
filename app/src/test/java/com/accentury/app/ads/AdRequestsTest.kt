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
}
