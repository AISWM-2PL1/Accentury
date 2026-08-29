import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/web/WebLoadControllerTest.kt`의 1:1 이식본.
///
/// 안드로이드 테스트와 마찬가지로 시간을 주입하지 않는다 — 8초 대기는 화면이 걸고, 이 클래스는
/// 발화된 결과만 ``WebLoadController/onTimeout()``으로 받으므로 테스트가 그 자리를 직접 부른다.
final class WebLoadControllerTests: XCTestCase {

    func testStartsLoadingAndBecomesReadyWhenPageFinishes() {
        let controller = WebLoadController()
        XCTAssertEqual(.loading, controller.state)
        controller.onPageFinished()
        XCTAssertEqual(.ready, controller.state)
    }

    func testMainFrameErrorGoesToFailureScreen() {
        let controller = WebLoadController()
        controller.onMainFrameError()
        XCTAssertEqual(.failed, controller.state)
    }

    func testPageFinishedAfterErrorDoesNotOverwriteFailure() {
        // 오류 페이지도 완료 콜백을 쏜다.
        let controller = WebLoadController()
        controller.onMainFrameError()
        controller.onPageFinished()
        XCTAssertEqual(.failed, controller.state)
    }

    func testTimeoutOnlyJudgesFailureWhileLoading() {
        let loading = WebLoadController()
        loading.onTimeout()
        XCTAssertEqual(.failed, loading.state)
    }

    func testLateTimeoutIsIgnoredOnceLoadIsDone() {
        let controller = WebLoadController()
        controller.onPageFinished()
        controller.onTimeout()
        XCTAssertEqual(.ready, controller.state)
    }

    func testNavigationErrorAfterLoadAlsoGoesToFailureScreen() {
        let controller = WebLoadController()
        controller.onPageFinished()
        controller.onMainFrameError()
        XCTAssertEqual(.failed, controller.state)
    }

    func testLoadingNewUrlInSameWebViewGoesBackToLoading() {
        // 인트로의 ready를 이어받지 않는다.
        let controller = WebLoadController()
        controller.onPageFinished()
        controller.onNavigationStarted()
        XCTAssertEqual(.loading, controller.state)
    }

    func testNewUrlLoadIsJudgedFromScratchAgain() {
        let controller = WebLoadController()
        controller.onPageFinished()
        controller.onNavigationStarted()
        controller.onTimeout()
        XCTAssertEqual(.failed, controller.state)
        // WebView를 새로 만들지 않았으므로 재생성 키는 그대로다.
        XCTAssertEqual(0, controller.attempt)
    }

    func testRetryBumpsAttemptAndReturnsToLoading() {
        let controller = WebLoadController()
        controller.onMainFrameError()
        XCTAssertEqual(0, controller.attempt)
        controller.retry()
        XCTAssertEqual(1, controller.attempt)
        XCTAssertEqual(.loading, controller.state)
    }

    func testSameTransitionRulesApplyAfterRetry() {
        let controller = WebLoadController()
        controller.onTimeout()
        controller.retry()
        controller.onPageFinished()
        XCTAssertEqual(.ready, controller.state)
        XCTAssertEqual(1, controller.attempt)
    }
}
