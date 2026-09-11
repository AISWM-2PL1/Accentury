import UIKit

/// 지금 화면에 서 있는 뷰 컨트롤러 — 시트·전면 광고를 띄울 상대다 (KAN-177에서 `ExternalBrowser`가
/// 세운 것을 KAN-196에서 광고 게이트도 쓰게 되어 한 곳으로 뺐다).
///
/// SwiftUI 앱이라 우리가 직접 든 뷰 컨트롤러가 없어서 창에서 거슬러 올라간다. 이미 떠 있는
/// 시트(권한 안내·공유·Safari)가 있으면 그 위에 얹어야 한다 — 가려진 컨트롤러에서 present하면
/// iOS가 조용히 무시한다. AdMob의 `present(from: nil)`도 같은 탐색을 하지만, 우리가 고른
/// 컨트롤러를 넘겨야 Safari 시트를 띄운 자리와 광고를 띄운 자리가 같은 규칙으로 정해진다.
@MainActor
enum TopViewController {

    static func current() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first

        guard var top = scene?.windows.first(where: \.isKeyWindow)?.rootViewController else { return nil }
        while let presented = top.presentedViewController {
            top = presented
        }
        return top
    }
}
