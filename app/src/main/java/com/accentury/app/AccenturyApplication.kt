package com.accentury.app

import android.app.Application
import com.kakao.sdk.common.KakaoSdk

/**
 * 앱 전역 초기화 지점. 지금은 카카오 SDK 하나뿐이다 (KAN-30).
 *
 * Application이 필요한 이유: 카카오 SDK는 앱 키를 프로세스 단위로 한 번만 등록받고
 * (`ShareClient.instance`가 그 값을 전제로 만들어진다) 공유 호출 시점에는 이미 초기화돼 있어야 한다.
 * Activity의 onCreate에 두면 프로세스가 살아 있는 채 Activity만 재생성되는 경로에서 중복 init이 돌고,
 * 반대로 Activity 없이 도는 경로(추후 워커·서비스)에서는 아예 안 돈다.
 */
class AccenturyApplication : Application() {
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
    }
}
