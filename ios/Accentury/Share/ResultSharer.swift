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

    /// - Parameters:
    ///   - kakaoEnabled: 앱 키가 주입돼 SDK가 초기화됐는가. false면 [isTalkAvailable]·[shareViaKakao]는 불리지 않는다
    ///   - isTalkAvailable: 카톡 설치 여부 조회 (프로덕션: `ShareApi.isKakaoTalkSharingAvailable()`)
    ///   - shareViaKakao: 템플릿을 카카오에 넘기고 **카톡을 열 URL**을 돌려받는다. 결과는 (url, error) 쌍이다
    ///   - openUrl: 그 URL 열기. 완료 핸들러의 Bool이 실제로 열렸는지다
    ///   - presentSheet: OS 공유 시트 띄우기. 폴백이 도착하는 유일한 자리다
    init(
        kakaoEnabled: Bool,
        isTalkAvailable: @escaping () -> Bool,
        shareViaKakao: @escaping (FeedTemplate, @escaping (URL?, Error?) -> Void) -> Void,
        openUrl: @escaping (URL, @escaping (Bool) -> Void) -> Void,
        presentSheet: @escaping (SharePayload) -> Void
    ) {
        self.kakaoEnabled = kakaoEnabled
        self.isTalkAvailable = isTalkAvailable
        self.shareViaKakao = shareViaKakao
        self.openUrl = openUrl
        self.presentSheet = presentSheet
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
            presentSheet(payload)
        }
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
            presentSheet(payload)
            return
        }

        shareViaKakao(kakaoFeedTemplate(from: card)) { [weak self] url, error in
            guard let self else { return }
            guard let url else {
                NSLog("[ResultSharer] 카카오 공유 실패 - 시스템 공유 시트로 폴백: \(String(describing: error))")
                self.presentSheet(payload)
                return
            }
            self.openUrl(url) { opened in
                guard !opened else { return }
                NSLog("[ResultSharer] 카톡 전환 실패 - 시스템 공유 시트로 폴백")
                self.presentSheet(payload)
            }
        }
    }

    /// 프로덕션 결선.
    ///
    /// - Parameter presentSheet: 시트를 띄울 자리. 화면(`TestFlowView`)이 넘긴다 — 시트는
    ///   SwiftUI 상태로 뜨는 것이라 이 클래스가 직접 할 수 없고, 할 이유도 없다.
    static func forApp(presentSheet: @escaping (SharePayload) -> Void) -> ResultSharer {
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
            presentSheet: presentSheet
        )
    }
}
