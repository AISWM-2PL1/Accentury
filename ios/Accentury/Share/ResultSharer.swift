import AccenturyCore
import KakaoSDKShare
import KakaoSDKTemplate
import UIKit

/// 카드를 카카오 템플릿으로 옮긴다 (KAN-180). 판단은 Core ``ShareCard``가 이미 끝냈고 여기는
/// 필드를 옮기기만 한다 — 이 함수가 SDK 타입을 만드는 유일한 자리다.
///
/// 링크를 하나 만들어 본문과 버튼이 나눠 쓴다: 카드 어디를 눌러도 도착지가 같아야 한다
/// (``ShareCard/linkUrl`` 주석). `webUrl`과 `mobileWebUrl`에 같은 값을 넣는 것도 안드로이드와
/// 같다 — 우리 도착지는 모바일·데스크톱을 가리지 않는 SPA 하나다.
///
/// `description`·`imageWidth`·`imageHeight`를 넘기지 않는 이유는 ``ShareCard``에 그 값이
/// 없어서이고, 없는 이유는 그쪽 주석에 있다.
func kakaoFeedTemplate(from card: ShareCard) -> FeedTemplate {
    let link = Link(webUrl: card.linkUrl, mobileWebUrl: card.linkUrl)
    return FeedTemplate(
        content: Content(title: card.title, imageUrl: card.imageUrl, link: link),
        buttons: [Button(title: card.buttonTitle, link: link)]
    )
}

/// 결과가 나가는 통로 (KAN-180). 안드로이드 `ResultSharer.kt`와 같은 자리이고 같은 순서다.
///
/// 협력자를 전부 생성자로 받는 이유는 테스트다 — 카카오 SDK도 `UIApplication.open`도 단위
/// 테스트에서 돌지 않는데, 정작 검증하고 싶은 건 "성공하면 카톡을 연다 / 실패하면 시트로
/// 넘어간다"는 순서 자체다. 프로덕션 결선은 [forApp]  한 곳에 모여 있다.
///
/// **모든 호출이 메인 스레드라고 전제한다.** 진입점이 브리지의 shareResult 하나뿐이고
/// (`AccenturyBridge`), 카카오 SDK의 콜백도 메인 큐로 돌아온다.
///
/// 안드로이드와 갈리는 점 하나: 그쪽은 SDK 호출이 동기 예외로 끝날 수 있어 try/catch가 한 겹
/// 더 있다. 스위프트 SDK에는 `throws` 경로가 없어서 실패가 콜백 하나로만 온다.
final class ResultSharer {

    private let kakaoEnabled: Bool
    private let isTalkAvailable: () -> Bool
    private let shareViaKakao: (FeedTemplate, @escaping (URL?, Error?) -> Void) -> Void
    private let openUrl: (URL, @escaping (Bool) -> Void) -> Void
    private let presentSheet: (SharePayload) -> Void
    private let onLaunched: (ShareChannel) -> Void

    /// - Parameters:
    ///   - kakaoEnabled: 앱 키가 주입돼 SDK가 초기화됐는가. false면 [isTalkAvailable]·[shareViaKakao]는 불리지 않는다
    ///   - isTalkAvailable: 카톡 설치 여부 조회 (프로덕션: `ShareApi.isKakaoTalkSharingAvailable()`)
    ///   - shareViaKakao: 템플릿을 카카오에 넘기고 **카톡을 열 URL**을 돌려받는다. 결과는 (url, error) 쌍이다
    ///   - openUrl: 그 URL 열기. 완료 핸들러의 Bool이 실제로 열렸는지다
    ///   - presentSheet: OS 공유 시트 띄우기. 폴백이 도착하는 유일한 자리다
    ///   - onLaunched: 실제로 띄운 통로 (KAN-33 계측, 안드로이드 `ResultSharer`의 같은 인자).
    ///     폴백이 얼마나 도는지를 모르면 카카오 경로의 값을 판단할 수 없다. **열지 못한 경우에는
    ///     부르지 않는다** — 열리지 않은 화면을 "띄웠다"로 세면 통로 하나가 통째로 막힌 기기가
    ///     집계에서 정상으로 보인다. 기본값이 있는 이유는 테스트다(공유 순서만 보는 기존 검증들이
    ///     계측 인자를 몰라도 된다)
    init(
        kakaoEnabled: Bool,
        isTalkAvailable: @escaping () -> Bool,
        shareViaKakao: @escaping (FeedTemplate, @escaping (URL?, Error?) -> Void) -> Void,
        openUrl: @escaping (URL, @escaping (Bool) -> Void) -> Void,
        presentSheet: @escaping (SharePayload) -> Void,
        onLaunched: @escaping (ShareChannel) -> Void = { _ in }
    ) {
        self.kakaoEnabled = kakaoEnabled
        self.isTalkAvailable = isTalkAvailable
        self.shareViaKakao = shareViaKakao
        self.openUrl = openUrl
        self.presentSheet = presentSheet
        self.onLaunched = onLaunched
    }

    func share(_ payload: SharePayload) {
        let channel = chooseShareChannel(
            kakaoEnabled: kakaoEnabled,
            // 키가 없으면 SDK가 초기화되지 않았으므로 조회 자체를 하지 않는다.
            talkAvailable: kakaoEnabled && isTalkAvailable()
        )
        switch channel {
        case .kakao:
            shareViaKakaoOrSheet(payload)
        case .systemSheet:
            launchSheet(payload)
        }
    }

    /// 시트로 내려가는 모든 경로가 여기 하나로 모인다 — 계측을 자리마다 붙이면 폴백 갈래가 하나
    /// 늘 때 그 자리만 세지 않는 일이 생긴다 (안드로이드 `launchSheet`와 같은 자리다).
    ///
    /// 안드로이드는 받을 앱이 없는 기기에서 인텐트 실행이 던져 "띄우지 못함"이 실재하지만,
    /// iOS의 시트는 SwiftUI 상태 한 줄이라 뜨지 않는 경로가 없다. 그래서 여기서는 곧바로 센다.
    private func launchSheet(_ payload: SharePayload) {
        presentSheet(payload)
        onLaunched(.systemSheet)
    }

    /// 카카오 경로. 어디서 막히든 끝은 시트다 — 사용자가 누른 건 "공유"지 "카톡 공유"가 아니므로,
    /// 아무 일도 일어나지 않은 화면을 보여주는 대신 통로를 하나 더 내준다.
    ///
    /// 막힐 수 있는 자리가 셋이다.
    /// 1. 카드 조립 실패 — ``buildShareCard(_:)``가 nil (그쪽 주석 참고)
    /// 2. 카카오 쪽 실패 — 템플릿 거부, 카카오 서버 오류, 앱 키·콘솔 설정 불일치
    /// 3. URL 열기 실패 — 카톡이 조회 시점 이후에 지워졌거나 전환이 거부됐다
    ///
    /// 3번을 굳이 보는 이유: `isKakaoTalkSharingAvailable`이 참이었다고 전환까지 보장되지 않는다.
    /// 여기서 성공 여부를 무시하면 사용자는 [친구에게 공유하기]를 눌렀는데 아무 일도 일어나지
    /// 않는 화면을 보게 된다 — 우리가 알 수 있는 실패를 그냥 삼킨 셈이다.
    private func shareViaKakaoOrSheet(_ payload: SharePayload) {
        guard let card = buildShareCard(payload) else {
            launchSheet(payload)
            return
        }

        /*
         * 콜백이 self를 **강하게** 잡는다. `[weak self]`가 여기서는 정확히 틀린다.
         *
         * 이 객체를 붙들고 있는 사람이 없다 - 화면은 `ResultSharer.forApp(...).share(payload)`
         * 한 줄로 쓰고 지나가고(TestFlowView.routeShare), 그 임시 객체는 share가 반환하는
         * 순간 해제된다. 카카오 SDK 콜백은 네트워크 왕복 뒤에 오므로, 약하게 잡으면 그때는
         * 이미 nil이라 카톡 전환도 시트 폴백도 통째로 사라진다 - 사용자는 [친구에게
         * 공유하기]를 눌렀는데 아무 일도 일어나지 않는 화면을 본다.
         *
         * 순환 참조는 생기지 않는다. self는 이 클로저를 들고 있지 않고, 클로저를 들고 있는
         * 쪽은 카카오 SDK다. SDK가 콜백을 부르고 놓으면 self도 함께 풀린다 - 강한 캡처가
         * 곧 "공유가 끝날 때까지만 살아 있는다"는 수명이다.
         */
        shareViaKakao(kakaoFeedTemplate(from: card)) { url, error in
            guard let url else {
                NSLog("[ResultSharer] 카카오 공유 실패 - 시스템 공유 시트로 폴백: \(String(describing: error))")
                self.launchSheet(payload)
                return
            }
            // 여기도 같은 이유로 강한 캡처다. 열기 완료 핸들러가 올 때까지 살아 있어야
            // 전환 실패를 시트로 받아낼 수 있다.
            self.openUrl(url) { opened in
                guard !opened else {
                    // 카톡이 실제로 열린 유일한 자리다 (KAN-33 `share_launched`).
                    self.onLaunched(.kakao)
                    return
                }
                NSLog("[ResultSharer] 카톡 전환 실패 - 시스템 공유 시트로 폴백")
                self.launchSheet(payload)
            }
        }
    }

    /// 프로덕션 결선.
    ///
    /// - Parameters:
    ///   - presentSheet: 시트를 띄울 자리. 화면(`TestFlowView`)이 넘긴다 — 시트는
    ///     SwiftUI 상태로 뜨는 것이라 이 클래스가 직접 할 수 없고, 할 이유도 없다.
    ///   - onLaunched: 계측 (KAN-33). 창구를 들고 있는 쪽도 화면이라 같이 넘어온다.
    static func forApp(
        presentSheet: @escaping (SharePayload) -> Void,
        onLaunched: @escaping (ShareChannel) -> Void = { _ in }
    ) -> ResultSharer {
        ResultSharer(
            // 1단계에서 세운 사슬의 끝. 키가 없으면 AccenturyApp이 initSDK를 건너뛰었으므로
            // 여기서도 카카오를 부르지 않는다 - 미초기화 SDK 접근은 그 자체로 사고다.
            kakaoEnabled: AppConfig.kakaoNativeAppKey != nil,
            isTalkAvailable: { ShareApi.isKakaoTalkSharingAvailable() },
            shareViaKakao: { template, onResult in
                ShareApi.shared.shareDefault(templatable: template) { result, error in
                    onResult(result?.url, error)
                }
            },
            openUrl: { url, completion in
                UIApplication.shared.open(url, options: [:], completionHandler: completion)
            },
            presentSheet: presentSheet,
            onLaunched: onLaunched
        )
    }
}
