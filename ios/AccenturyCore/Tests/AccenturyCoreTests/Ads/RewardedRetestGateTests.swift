import XCTest
@testable import AccenturyCore

/// 안드로이드 `RewardedRetestGateTest`의 이식본 (KAN-196). 케이스가 같아야 두 플랫폼의 재응시 갈래가 같다.
final class RewardedRetestGateTests: XCTestCase {

    func testNoAdToShowProceedsWithoutAnAd() {
        // 로드 실패·아직 로드 전·소진 뒤 재로드 전이 모두 여기다 — 광고 사정으로 재응시를 막지 않는다 (§8.2).
        let gate = RewardedRetestGate()
        XCTAssertEqual(.proceed, gate.request(loaded: false))
        XCTAssertFalse(gate.showing)
    }

    func testAnAvailableAdIsShownAndTheDecisionIsDeferredToCallbacks() {
        let gate = RewardedRetestGate()
        XCTAssertEqual(.showAd, gate.request(loaded: true))
        XCTAssertTrue(gate.showing)
    }

    func testWatchingToTheEndProceedsAndTheFollowingDismissIsIgnored() {
        // SDK는 보상 콜백을 닫힘 콜백보다 먼저 준다. 통과는 보상 시점에 한 번이고 닫힘은 두 번째
        // 진행이 되면 안 된다.
        let gate = RewardedRetestGate()
        _ = gate.request(loaded: true)
        XCTAssertEqual(.proceed, gate.onEarnedReward())
        XCTAssertEqual(.ignored, gate.onDismissed())
        XCTAssertFalse(gate.showing)
    }

    func testClosingWithoutTheRewardIsDismissed() {
        let gate = RewardedRetestGate()
        _ = gate.request(loaded: true)
        XCTAssertEqual(.dismissed, gate.onDismissed())
        XCTAssertFalse(gate.showing)
    }

    func testAPresentationFailureProceedsWithoutAnAd() {
        let gate = RewardedRetestGate()
        _ = gate.request(loaded: true)
        XCTAssertEqual(.proceed, gate.onShowFailed())
        XCTAssertFalse(gate.showing)
    }

    func testASecondRequestWhileShowingIsIgnored() {
        // 웹의 pending 잠금이 먼저 막지만 네이티브도 한 겹 — 광고를 두 번 띄우면 첫 광고의 보상이
        // 갈 곳을 잃는다.
        let gate = RewardedRetestGate()
        _ = gate.request(loaded: true)
        XCTAssertEqual(.ignored, gate.request(loaded: true))
        XCTAssertEqual(.ignored, gate.request(loaded: false))
        XCTAssertTrue(gate.showing)
    }

    func testTheRewardProceedsOnlyOnce() {
        let gate = RewardedRetestGate()
        _ = gate.request(loaded: true)
        XCTAssertEqual(.proceed, gate.onEarnedReward())
        XCTAssertEqual(.ignored, gate.onEarnedReward())
    }

    func testCallbacksOutsideAPresentationAreAllIgnored() {
        let gate = RewardedRetestGate()
        XCTAssertEqual(.ignored, gate.onEarnedReward())
        XCTAssertEqual(.ignored, gate.onDismissed())
        XCTAssertEqual(.ignored, gate.onShowFailed())
    }

    func testTheNextRequestIsAcceptedAfterADismiss() {
        // 중도에 닫아 dismissed가 났어도 결과 화면의 버튼은 다시 열린다 — 다음 탭은 새 광고다.
        let gate = RewardedRetestGate()
        _ = gate.request(loaded: true)
        _ = gate.onDismissed()
        XCTAssertEqual(.showAd, gate.request(loaded: true))
        // 앞선 표시의 보상 여부가 새어 오지 않는다.
        XCTAssertEqual(.dismissed, gate.onDismissed())
    }
}
