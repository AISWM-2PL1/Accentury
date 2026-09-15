package com.accentury.app.analytics

import android.content.Intent

/**
 * 일부러 내는 테스트 크래시 (KAN-33 AC 9, 디버그 변형).
 *
 * Crashlytics가 **실제 크래시**를 기기 모델·OS 버전·스택과 함께 받는지는 진짜 크래시 하나를
 * 내 봐야 확인된다. [CrashReports]의 `recordException`은 비치명 보고라 그 경로를 지나지 않고,
 * 앱에는 일부러 죽을 자리가 없으므로 여기에 통로를 하나 둔다.
 *
 * 사용법 - 디버그 빌드를 설치한 뒤 Intent extra 하나로 연다:
 * ```
 * adb shell am force-stop com.accentury.app
 * adb shell am start -n com.accentury.app/.MainActivity --ez crashTest true
 * adb shell am start -n com.accentury.app/.MainActivity   # 다시 켜야 리포트가 올라간다
 * ```
 *
 * **다시 켜야 하는 이유**: Crashlytics는 죽는 순간 네트워크를 쓰지 않는다 - 리포트를 디스크에
 * 쓰고, 다음 실행의 초기화에서 그 파일을 올려보낸다. 그래서 크래시 뒤 앱을 한 번 더 켜기 전에는
 * 대시보드에 아무것도 없다.
 *
 * `BuildConfig.DEBUG`로 분기하지 않는 이유는 `audio/PcmSources.kt`와 같다 - 이 파일이
 * `src/debug`에만 있어 릴리스 APK의 `classes.dex`에는 **크래시를 내는 코드 자체가 없다**.
 * 실행 중 분기는 최적화가 꺼져 있으면 코드를 그대로 남기지만, 없는 함수 본문은 남을 수가 없다
 * (릴리스 변형은 `src/release`의 같은 이름 파일이고 아무 일도 하지 않는다).
 *
 * 설정(google-services.json)이 없는 빌드에서도 이 통로는 그대로 죽는다. Firebase를 부르지 않아서다 -
 * 리포트가 올라가지 않을 뿐 빌드도 동작도 갈리지 않는다.
 */
fun crashIfRequested(intent: Intent?) {
    if (intent?.getBooleanExtra(EXTRA_CRASH_TEST, false) != true) return
    throw TestCrash()
}

/** Intent extra 이름. `--ez crashTest true`로 넘어온다. */
private const val EXTRA_CRASH_TEST = "crashTest"

/**
 * 잡지 않는다 - 잡으면 크래시가 아니다. 전용 타입인 이유는 대시보드에서 이 한 건이 실제 사고와
 * 같은 묶음에 섞이지 않게 하기 위해서다.
 */
private class TestCrash : RuntimeException("KAN-33 테스트 크래시 (--ez crashTest true)")
