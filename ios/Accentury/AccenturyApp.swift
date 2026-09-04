import KakaoSDKCommon
import SwiftUI

/// 앱 진입점. 안드로이드의 `MainActivity` + `AccenturyApplication` 자리다.
@main
struct AccenturyApp: App {

    /// 안드로이드 `AccenturyApplication.onCreate`에 해당하는 자리 (KAN-180, KAN-33).
    ///
    /// 카카오 SDK는 앱 키를 **프로세스 단위로 한 번만** 등록받고, 공유를 호출하는 시점에는
    /// 이미 초기화돼 있어야 한다. SwiftUI에는 Application 클래스가 없어서 `App`의 이니셜라이저가
    /// 그 자리다 — 화면(`ContentView`)의 `onAppear`에 두면 화면이 다시 그려질 때마다 돌고,
    /// 반대로 화면보다 먼저 도는 경로에서는 초기화되지 않은 SDK를 만난다.
    init() {
        /*
         * 계측·크래시 SDK를 먼저 세운다 (KAN-33). 순서가 규칙이다 — Crashlytics는 초기화된
         * 뒤부터 크래시를 잡으므로, 앱 시작에서 가장 먼저 서야 그 앞의 초기화(카카오 SDK 등)에서
         * 나는 사고까지 리포트에 남는다.
         *
         * 설정 파일이 없으면 아무 일도 하지 않고 지나간다 — 그게 정상 상태다 (``FirebaseSetup``).
         * 카카오 키 분기와 같은 모양이고 같은 이유다.
         */
        FirebaseSetup.start()
        CrashReports.install()

        /*
         * 키가 없으면 초기화하지 않는다 — 이 분기가 카카오 경로 전체의 스위치다.
         *
         * 빈 키로 initSDK를 불러 두면 SDK는 "초기화됐다"고 보고 공유 호출을 받아들인 뒤
         * 네트워크 단계에서 인증 오류로 떨어진다. 실패 시점이 사용자가 [친구에게 공유하기]를
         * 누른 뒤라는 게 문제다 — 키를 모르는 상태는 빌드 시점에 이미 알 수 있으므로 그때 꺼
         * 두고, 공유는 처음부터 OS 공유 시트로 보낸다 (ResultSharer).
         *
         * 안드로이드 `AccenturyApplication`과 같은 판단이다.
         */
        if let kakaoAppKey = AppConfig.kakaoNativeAppKey {
            KakaoSDK.initSDK(appKey: kakaoAppKey)
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
