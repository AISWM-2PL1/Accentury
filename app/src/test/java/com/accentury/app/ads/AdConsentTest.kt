package com.accentury.app.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdConsentTest {

    /** 맵 하나짜리 저장소. 프로덕션(SharedPreferences)과 같은 계약 — 없으면 Unknown. */
    private class FakeStore : AdConsentStore {
        private var saved: AdConsent? = null
        override fun read(): AdConsent = saved ?: AdConsent.Unknown
        override fun write(consent: AdConsent) {
            saved = consent
        }
    }

    @Test
    fun `브리지 문자열 세 개가 각각 상태로 읽힌다`() {
        assertEquals(AdConsent.Granted, AdConsent.fromBridgeValue("granted"))
        assertEquals(AdConsent.Denied, AdConsent.fromBridgeValue("denied"))
        assertEquals(AdConsent.Unknown, AdConsent.fromBridgeValue("unknown"))
    }

    @Test
    fun `상태의 브리지 문자열은 계약 그대로다`() {
        // 웹 래퍼(bridge.ts의 AD_CONSENT_VALUES)가 이 세 문자열만 계약 안으로 본다.
        assertEquals("granted", AdConsent.Granted.bridgeValue)
        assertEquals("denied", AdConsent.Denied.bridgeValue)
        assertEquals("unknown", AdConsent.Unknown.bridgeValue)
    }

    @Test
    fun `계약 밖 문자열은 null이다`() {
        // 보정하지 않는다 — 대소문자·공백까지 계약이다. 보정을 시작하면 웹과 앱이 다른 계약을
        // 들고도 동작하는 상태가 생긴다.
        assertNull(AdConsent.fromBridgeValue(""))
        assertNull(AdConsent.fromBridgeValue("Granted"))
        assertNull(AdConsent.fromBridgeValue(" granted"))
        assertNull(AdConsent.fromBridgeValue("true"))
        assertNull(AdConsent.fromBridgeValue("null"))
    }

    @Test
    fun `저장된 적 없으면 unknown이다`() {
        assertEquals(AdConsent.Unknown, FakeStore().read())
    }

    @Test
    fun `적은 값이 그대로 읽힌다`() {
        val store = FakeStore()
        store.write(AdConsent.Denied)
        assertEquals(AdConsent.Denied, store.read())
        store.write(AdConsent.Granted)
        assertEquals(AdConsent.Granted, store.read())
    }
}
