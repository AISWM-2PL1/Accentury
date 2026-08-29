import AccenturyCore
import XCTest
@testable import Accentury

/// WKWebView 호스트가 내리는 두 판정만 본다 (§6·§7). 시뮬레이터 없이 도는 이유가 이 둘을
/// 순수 함수로 떼어 둔 이유다 — allowlist는 보안 경계이고, 메인 프레임 판정은 "실패의 질"을
/// 좌우한다.
///
/// 안드로이드에서 이 자리는 `shouldOverrideUrlLoading`·`onReceivedHttpError` 안에 있어
/// JVM 테스트로 짚을 수 없었다(webview-layer.md §8 "미검증 구간"). 이식하면서 밖으로 뺐다.
final class WebViewHostPolicyTests: XCTestCase {

    private let allowed: Set<String> = ["https://accentury.app", "http://localhost:5173"]

    // MARK: navigationDecision

    func testUrlsOnTheAllowlistAreAllowed() {
        XCTAssertEqual(.allow, navigationDecision(url: "https://accentury.app", allowedOrigins: allowed))
        XCTAssertEqual(
            .allow,
            navigationDecision(url: "https://accentury.app/?bridge=1&app=1.0", allowedOrigins: allowed)
        )
        // 기본 포트는 표기 유무가 같은 origin이다 (Core webOrigin).
        XCTAssertEqual(.allow, navigationDecision(url: "https://accentury.app:443/t", allowedOrigins: allowed))
        XCTAssertEqual(.allow, navigationDecision(url: "http://localhost:5173/", allowedOrigins: allowed))
    }

    func testOtherOriginsAreCancelled() {
        XCTAssertEqual(.cancel, navigationDecision(url: "https://example.com", allowedOrigins: allowed))
        // 서브도메인은 다른 origin이다 — 와일드카드를 쓰지 않는다.
        XCTAssertEqual(.cancel, navigationDecision(url: "https://evil.accentury.app", allowedOrigins: allowed))
        // 스킴이 다르면 다른 origin이다 (평문 강등 차단).
        XCTAssertEqual(.cancel, navigationDecision(url: "http://accentury.app", allowedOrigins: allowed))
        // 포트가 다르면 다른 origin이다.
        XCTAssertEqual(.cancel, navigationDecision(url: "http://localhost:8080/", allowedOrigins: allowed))
    }

    /// http(s)가 아닌 스킴은 origin을 만들 수 없어 자동으로 걸린다. 브리지가 마이크 권한
    /// 게이트를 호출하므로 이 문이 곧 보안 경계다 (§7).
    func testNonHttpSchemesAreCancelled() {
        for url in [
            "javascript:alert(1)",
            "file:///etc/passwd",
            "data:text/html,<script>1</script>",
            "about:blank",
            "tel:01012345678",
            "mailto:a@b.c",
            "accentury://open",
        ] {
            XCTAssertEqual(.cancel, navigationDecision(url: url, allowedOrigins: allowed), url)
        }
    }

    func testMissingOrUnparsableUrlsAreCancelled() {
        XCTAssertEqual(.cancel, navigationDecision(url: nil, allowedOrigins: allowed))
        XCTAssertEqual(.cancel, navigationDecision(url: "", allowedOrigins: allowed))
        XCTAssertEqual(.cancel, navigationDecision(url: "https://", allowedOrigins: allowed))
    }

    /// allowlist가 비면 아무것도 열리지 않는다 — `WEB_URL`이 origin으로 파싱되지 않는
    /// 빌드 설정 실수가 "전부 허용"이 아니라 "전부 차단"으로 나타나야 한다 (fail-closed).
    func testAnEmptyAllowlistBlocksEverything() {
        XCTAssertEqual(.cancel, navigationDecision(url: "https://accentury.app", allowedOrigins: []))
    }

    // MARK: mainFrameHttpErrorDecision

    func testMainFrameErrorStatusesAreFailures() {
        XCTAssertTrue(mainFrameHttpErrorDecision(status: 404, isMainFrame: true))
        XCTAssertTrue(mainFrameHttpErrorDecision(status: 500, isMainFrame: true))
        XCTAssertTrue(mainFrameHttpErrorDecision(status: 400, isMainFrame: true))
    }

    func testMainFrameSuccessAndRedirectStatusesAreNotFailures() {
        XCTAssertFalse(mainFrameHttpErrorDecision(status: 200, isMainFrame: true))
        XCTAssertFalse(mainFrameHttpErrorDecision(status: 204, isMainFrame: true))
        // 리다이렉트는 WebKit이 알아서 따라간다 — 여기서 끊으면 정상 전환이 오류가 된다.
        XCTAssertFalse(mainFrameHttpErrorDecision(status: 302, isMainFrame: true))
    }

    /// 서브리소스 하나가 404라고 화면 전체를 접지 않는다 (안드로이드 `request.isForMainFrame`).
    func testSubframeErrorsAreIgnored() {
        XCTAssertFalse(mainFrameHttpErrorDecision(status: 404, isMainFrame: false))
        XCTAssertFalse(mainFrameHttpErrorDecision(status: 500, isMainFrame: false))
    }

    // MARK: shouldReportMainFrameFailure

    /// 신원으로 쓸 객체들. 강한 참조로 붙들어야 한다 — 해제된 객체의 주소는 재사용될 수 있어
    /// `ObjectIdentifier`가 우연히 같아질 수 있다.
    private let mainFrameNavigation = NSObject()
    private let otherNavigation = NSObject()

    private var mainFrame: ObjectIdentifier { ObjectIdentifier(mainFrameNavigation) }
    private var other: ObjectIdentifier { ObjectIdentifier(otherNavigation) }

    private let realFailure = NSError(domain: NSURLErrorDomain, code: NSURLErrorCannotConnectToHost)

    /// (1) 기다리던 메인 프레임 로드가 실패했다 — 오류 화면으로 간다.
    func testTheAwaitedMainFrameLoadFailingIsReported() {
        XCTAssertTrue(
            shouldReportMainFrameFailure(
                navigation: mainFrame,
                currentMainFrame: mainFrame,
                error: realFailure
            )
        )
    }

    /// (2) 내비게이션 객체가 없는 통지 — 메인 프레임 로드로 볼 근거가 없다.
    /// 안드로이드 `request.isForMainFrame == false`가 걸러내던 자리다.
    func testAFailureWithNoNavigationIdentityIsIgnored() {
        XCTAssertFalse(
            shouldReportMainFrameFailure(
                navigation: nil,
                currentMainFrame: mainFrame,
                error: realFailure
            )
        )
    }

    /// (3) 밀려난 앞 로드의 뒤늦은 실패 — 방금 시작한 정상 로드 위로 오류 화면을 덮으면 안 된다.
    /// 인트로 → 테스트 진입으로 갈아타는 순간이 정확히 이 경우다 (KAN-100).
    func testASupersededNavigationFailingIsIgnored() {
        XCTAssertFalse(
            shouldReportMainFrameFailure(
                navigation: other,
                currentMainFrame: mainFrame,
                error: realFailure
            )
        )
    }

    /// (4) 우리가 스스로 끊은 것은 실패가 아니다 — allowlist 밖 링크를 막을 때마다 화면이
    /// 오류로 뒤집히면 안 된다. 신원이 맞아떨어져도 오류 종류가 이기는지 함께 본다.
    func testSelfInflictedCancellationsAreIgnoredEvenOnTheAwaitedLoad() {
        for error in [
            NSError(domain: NSURLErrorDomain, code: NSURLErrorCancelled),
            NSError(domain: "WebKitErrorDomain", code: 102),
        ] {
            XCTAssertTrue(isSelfInflictedCancellation(error))
            XCTAssertFalse(
                shouldReportMainFrameFailure(
                    navigation: mainFrame,
                    currentMainFrame: mainFrame,
                    error: error
                )
            )
        }
    }

    /// 기다리는 로드가 없으면 무엇이 실패했든 화면을 뒤집을 근거가 아니다
    /// (로드가 끝난 뒤 도착한 뒤늦은 통지).
    func testFailuresWithNoLoadInFlightAreIgnored() {
        XCTAssertFalse(
            shouldReportMainFrameFailure(
                navigation: mainFrame,
                currentMainFrame: nil,
                error: realFailure
            )
        )
    }

    /// 진짜 망 오류는 취소로 오인되지 않는다.
    func testRealNetworkErrorsAreNotTreatedAsCancellations() {
        XCTAssertFalse(isSelfInflictedCancellation(realFailure))
        XCTAssertFalse(
            isSelfInflictedCancellation(NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut))
        )
        // 도메인이 다르면 코드가 같아도 취소가 아니다.
        XCTAssertFalse(isSelfInflictedCancellation(NSError(domain: NSURLErrorDomain, code: 102)))
    }
}
