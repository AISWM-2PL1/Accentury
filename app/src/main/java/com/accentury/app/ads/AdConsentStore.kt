package com.accentury.app.ads

import android.content.Context
import android.content.SharedPreferences

/**
 * 광고 동의의 정본 저장소 (KAN-196, webview-bridge.md §8.1).
 *
 * 인터페이스로 둔 이유는 [com.accentury.app.web.AccenturyBridge]와 같은 사정이다 — 브리지와
 * 광고 게이트는 JVM 단위 테스트 대상인데 SharedPreferences는 계측기 없이 만들 수 없다. 프로덕션은
 * [SharedPreferencesAdConsentStore], 테스트는 맵 하나짜리 가짜다.
 *
 * 읽기는 브리지 `getAdConsent`가 **JS 스레드에서 동기로** 부르고 쓰기는 postToMain을 타고 메인
 * 스레드에서 온다. 구현은 이 두 스레드에서 안전해야 한다 (SharedPreferences는 그렇다).
 */
interface AdConsentStore {
    /** 저장된 적 없으면 [AdConsent.Unknown]. */
    fun read(): AdConsent

    /** 사용자가 시트에서 고른 값을 적는다. [AdConsent.Unknown]을 쓰는 호출자는 없어야 한다. */
    fun write(consent: AdConsent)
}

/**
 * SharedPreferences 구현. 파일 이름 `ad_consent`, 키 `state`, 값은 [AdConsent.bridgeValue] 문자열
 * 그대로다 — 4단계 iOS의 UserDefaults 키도 같은 이름·같은 값이어야 두 플랫폼의 `getAdConsent`가
 * 같은 저장 형식을 말한다 (ads-admob.md).
 *
 * 앱 기본 prefs가 아니라 전용 파일인 이유: 기본 파일은 AdMob SDK 자신이 `gad_rdp` 같은 키를
 * 읽고 쓰는 자리다(developers.google.com/admob/android/privacy/ccpa). 우리 값이 SDK가 훑는
 * 파일에 섞여 있으면 SDK 쪽 키 이름이 바뀔 때 우리 값과 충돌하는지 매번 확인해야 한다.
 */
class SharedPreferencesAdConsentStore(context: Context) : AdConsentStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): AdConsent {
        val raw = prefs.getString(KEY_STATE, null) ?: return AdConsent.Unknown
        // 깨진 값(구버전 형식 등)은 저장이 없었던 것으로 본다 — 시트를 한 번 더 묻는 편이
        // 모르는 값을 "허용"으로 읽는 것보다 안전하다.
        return AdConsent.fromBridgeValue(raw) ?: AdConsent.Unknown
    }

    override fun write(consent: AdConsent) {
        // commit이 아니라 apply — 메인 스레드에서 오는 쓰기라 디스크 동기 대기를 걸지 않는다.
        // 되읽기는 메모리 사본에서 즉시 반영된다 (SharedPreferences 계약).
        prefs.edit().putString(KEY_STATE, consent.bridgeValue).apply()
    }

    companion object {
        const val PREFS_NAME = "ad_consent"
        const val KEY_STATE = "state"
    }
}
