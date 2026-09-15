package com.accentury.app.analytics

import android.content.Intent

/**
 * 일부러 내는 테스트 크래시 (KAN-33 AC 9, 릴리스 변형) - **릴리스에는 통로가 없다.**
 *
 * 크래시를 내는 코드는 `src/debug`에만 있어 릴리스 APK에는 실리지 않는다. 여기 남는 것은 빈
 * 본문뿐이라, 어떤 Intent extra를 넣어도 사용자 빌드에서 앱이 죽는 경로는 만들어지지 않는다.
 * 그 보장을 `BuildConfig.DEBUG` 분기가 아니라 소스셋이 한다 (`audio/PcmSources.kt`와 같은 이유).
 *
 * [intent]는 쓰지 않는다. 두 변형의 시그니처가 같아야 호출부([com.accentury.app.MainActivity])가
 * 어느 변형인지 몰라도 된다.
 */
@Suppress("UNUSED_PARAMETER")
fun crashIfRequested(intent: Intent?) = Unit
