package com.accentury.app

import android.app.Application
import com.accentury.app.ads.AdsController
import com.accentury.app.analytics.CrashReports
import com.kakao.sdk.common.KakaoSdk

/**
 * 앱 전역 초기화 지점 — 카카오 SDK(KAN-30)·크래시 리포트(KAN-33)·광고(KAN-196).
 *
 * Application이 필요한 이유: 카카오 SDK는 앱 키를 프로세스 단위로 한 번만 등록받고
 * (`ShareClient.instance`가 그 값을 전제로 만들어진다) 공유 호출 시점에는 이미 초기화돼 있어야 한다.
 * Activity의 onCreate에 두면 프로세스가 살아 있는 채 Activity만 재생성되는 경로에서 중복 init이 돌고,
 * 반대로 Activity 없이 도는 경로(추후 워커·서비스)에서는 아예 안 돈다.
 */
class AccenturyApplication : Application() {

    /**
     * 광고 허브 (KAN-196). 프로세스 단위인 이유는 AdsController KDoc에 있다 — 미리 받아 둔 광고와
     * 동의 저장소가 Activity 수명보다 길다. 화면은 [AdsController.from]으로 찾는다.
     */
    lateinit var ads: AdsController
        private set

    override fun onCreate() {
        super.onCreate()

        /*
         * 키가 없으면 초기화하지 않는다 - 이 분기가 카카오 경로 전체의 스위치다.
         *
         * 빈 문자열로 init해 두면 SDK는 "초기화됐다"고 보고 공유 호출을 받아들인 뒤 네트워크
         * 단계에서 인증 오류로 떨어진다. 실패 시점이 사용자가 [공유하기]를 누른 뒤라는 게 문제다 -
         * 키를 모르는 상태는 빌드 시점에 이미 알 수 있으므로 그때 꺼 두고, 공유는 처음부터
         * OS 공유 시트로 보낸다 (ResultSharer.forApp).
         */
        val kakaoAppKey = BuildConfig.KAKAO_NATIVE_APP_KEY
        if (kakaoAppKey.isNotBlank()) {
            KakaoSdk.init(this, kakaoAppKey)
        }

        /*
         * 크래시 리포트 (KAN-33). 카카오와 달리 여기에 init 호출이 없는 이유: Firebase는
         * google-services 플러그인이 심은 ContentProvider가 Application보다 먼저 스스로 초기화한다.
         * 설정 파일이 없는 빌드에서는 그 초기화가 아예 일어나지 않고, [CrashReports]가 그 사실을
         * 확인해 아무 데도 보내지 않는다 - "설정이 없는 것이 정상 상태"라는 카카오 키와 같은 규칙이다.
         *
         * 그래서 여기서 하는 일은 커스텀 키 하나를 걸어 두는 것뿐이다. 사용자 식별자는 붙이지
         * 않는다 (setUserId 금지, 익명 규칙).
         */
        CrashReports.install()

        /*
         * 광고 SDK (KAN-196). 카카오와 달리 "키가 없으면 끈다"는 분기가 없다 — 앱 ID·광고 단위가
         * 주입되지 않은 빌드는 Google 테스트 ID로 떨어지므로(build.gradle.kts) 광고 경로는 늘
         * 살아 있고, 테스트 광고가 나올 뿐이다. 요청 자체가 나가는 조건(동의가 정해졌는가)은
         * AdsController가 본다.
         */
        ads = AdsController.create(this)
        ads.initialize()
    }
}
