import SafariServices
import SwiftUI
import UIKit

/// 앱 밖 링크를 여는 자리 (KAN-177). ``WebViewHost``의 `navigationDecision(url:allowedOrigins:)`
/// 주석이 "인트로·결과 화면에 외부 링크가 생기는 티켓에서 붙인다"고 적어 둔 그 처리다.
/// 안드로이드 `ExternalBrowser.kt`(Custom Tabs)의 짝이다.
///
/// ## WebView 안에서 열지 않는 이유
///
/// 그러면 인트로가 사라지고 **돌아올 길이 없다.** WKWebView에 뒤로가기를 붙인 곳이 없고 iOS에는
/// 시스템 뒤로가기도 없어서, 방침을 읽은 사람은 앱을 죽였다 다시 켜야 한다. allowlist(§7)도
/// 걸린다: 시뮬레이터의 origin은 로컬 Vite라 우리 도메인의 방침 문서가 로드 차단 대상이 된다.
///
/// `SFSafariViewController`는 인트로 **위에** 시트를 덮는다. [완료]로 닫으면 인트로가 그대로
/// 남아 있고, 주소가 보여서 사용자가 무엇을 읽는지 확인할 수 있다.
///
/// `UIApplication.shared.open(_:)`(사파리 앱으로 나가기)을 쓰지 않는 이유도 같다 — 앱이
/// 백그라운드로 내려가면 WKWebView가 살아 있어도 사용자는 앱 전환기를 거쳐 돌아와야 한다.
@MainActor
enum ExternalBrowser {

    /// [url]을 Safari 시트로 연다. 호출 전에 ``AccenturyCore/externalUrlToOpen(_:allowedHosts:)``을
    /// 통과한 값이어야 한다 — 이 함수는 검증하지 않고 열기만 한다.
    ///
    /// 실패해도 던지지 않고 흔적만 남긴다. 방침 링크 하나 때문에 응시하던 앱이 죽는 것이
    /// 최악이고, 사용자에게는 "눌렀는데 아무 일도 없다"로 보이는 실패라 조용히 두면 아무도
    /// 모른다 — 앱에 설정 화면이 없어 방침으로 가는 길이 이것뿐이다.
    static func open(_ url: String) {
        guard let target = URL(string: url) else {
            CrashReports.recordExternalLinkFailure("bad_url")
            return
        }
        guard let presenter = topmostViewController() else {
            CrashReports.recordExternalLinkFailure("no_presenter")
            return
        }

        let safari = SFSafariViewController(url: target)
        /*
         * 시트를 크림·잉크로 맞춘다 (KAN-161). 색이 갈리면 앱을 벗어난 것처럼 읽히는데,
         * 실제로는 앱 위에 덮인 창이라 그 인상이 사실과 다르다. 팔레트의 정본은
         * ``Papercut`` 하나이므로 상수를 다시 적지 않고 거기서 가져온다.
         */
        safari.preferredBarTintColor = UIColor(Papercut.cream)
        safari.preferredControlTintColor = UIColor(Papercut.ink)
        // [완료]가 아니라 [닫기]다 — 무언가를 끝낸 것이 아니라 읽던 것을 덮는 동작이다
        safari.dismissButtonStyle = .close
        presenter.present(safari, animated: true)
    }

    /// 지금 화면에 서 있는 뷰 컨트롤러. 시트를 띄울 상대다.
    ///
    /// SwiftUI 앱이라 우리가 직접 든 뷰 컨트롤러가 없어서 창에서 거슬러 올라간다. 이미 떠 있는
    /// 시트(권한 안내·공유)가 있으면 그 위에 얹어야 한다 — 가려진 컨트롤러에서 present하면
    /// iOS가 조용히 무시한다.
    private static func topmostViewController() -> UIViewController? {
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
