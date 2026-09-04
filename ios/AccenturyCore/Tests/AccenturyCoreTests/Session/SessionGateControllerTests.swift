import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/session/SessionGateControllerTest.kt`의 1:1 이식본.
final class SessionGateControllerTests: XCTestCase {

    private let session = Session(
        sessionId: "s_abc",
        sessionToken: "st_xyz",
        testVersion: "gn-2026.08.1",
        scoreVersion: "sv-1",
        expiresAt: "2026-08-24T10:30:00Z"
    )

    func testStartsCreatingWithNoSessionYet() {
        let controller = SessionGateController()
        XCTAssertEqual(.creating, controller.state)
        XCTAssertNil(controller.session)
        XCTAssertEqual(0, controller.attempt)
    }

    func testReceivingASessionIsTheTestEntryItself() {
        let controller = SessionGateController()
        controller.onResult(.created(session))

        XCTAssertEqual(.ready(session), controller.state)
        XCTAssertEqual(session, controller.session)
    }

    func testNoResponseFoldsIntoTheNetworkReason() {
        let controller = SessionGateController()
        controller.onResult(.transportError(reason: "timeout"))

        XCTAssertEqual(.failed(reason: .network, retryAfterSeconds: nil), controller.state)
        XCTAssertNil(controller.session)
    }

    func testRateLimitedRoundsTheWaitUpToSeconds() {
        // 서버의 Retry-After와 같은 규칙.
        let controller = SessionGateController()
        controller.onResult(rejected(code: "RATE_LIMITED", retryable: true, retryAfterMs: 2_100))

        XCTAssertEqual(.failed(reason: .rateLimited, retryAfterSeconds: 3), controller.state)
    }

    func testWaitTimeAloneAlsoMeansRateLimited() {
        // 그 값을 주는 거절은 그것뿐이다.
        let controller = SessionGateController()
        controller.onResult(rejected(code: nil, retryable: true, retryAfterMs: 5_000))

        XCTAssertEqual(.failed(reason: .rateLimited, retryAfterSeconds: 5), controller.state)
    }

    func testRetryableRejectionFoldsIntoServerWithNoWaitTime() {
        let controller = SessionGateController()
        controller.onResult(rejected(code: nil, retryable: true, retryAfterMs: nil))

        XCTAssertEqual(.failed(reason: .server, retryAfterSeconds: nil), controller.state)
    }

    func testServerPinnedNonRetryableRejectionGoesToTheUnsupportedBranch() {
        let controller = SessionGateController()
        controller.onResult(rejected(code: "VALIDATION_FAILED", retryable: false, retryAfterMs: nil))

        XCTAssertEqual(.failed(reason: .unsupported, retryAfterSeconds: nil), controller.state)
    }

    func testRestartBumpsAttemptToSendTheRequestAgain() {
        let controller = SessionGateController()
        controller.onResult(.transportError(reason: "boom"))

        controller.restart()

        XCTAssertEqual(1, controller.attempt)
        XCTAssertEqual(.creating, controller.state)
    }

    func testRestartAlsoDiscardsAnAlreadyHeldSession() {
        // 종료한 응시를 다음 시작이 이어받으면 안 된다.
        let controller = SessionGateController()
        controller.onResult(.created(session))

        controller.restart()

        XCTAssertNil(controller.session)
        XCTAssertEqual(.creating, controller.state)
    }

    func testSameJudgementRulesApplyAfterARestart() {
        let controller = SessionGateController()
        controller.onResult(.transportError(reason: "boom"))
        controller.restart()
        controller.onResult(.created(session))

        XCTAssertEqual(session, controller.session)
        XCTAssertEqual(1, controller.attempt)
    }

    func testAnAcquiredSessionSurvivesRecreation() {
        // 토큰은 응답에서 한 번만 오므로 잃으면 되찾을 수 없다.
        let controller = SessionGateController()
        controller.onResult(.created(session))

        XCTAssertEqual(session, recreate(controller).session)
    }

    func testCreatingIsNotSavedAndComesBackReadyToRequestAgain() {
        let restored = recreate(SessionGateController())

        XCTAssertEqual(.creating, restored.state)
        XCTAssertEqual(0, restored.attempt)
    }

    func testFailureIsNotSaved() {
        // 이미 풀린 요청 제한을 복원 뒤에도 보여주면 안 된다.
        let controller = SessionGateController()
        controller.onResult(rejected(code: "RATE_LIMITED", retryable: true, retryAfterMs: 60_000))

        XCTAssertEqual(.creating, recreate(controller).state)
    }

    func testBrokenSavedValueStartsOverWithANewSession() {
        let restored = SessionGateController.restored(from: "{not json")

        XCTAssertEqual(.creating, restored.state)
    }

    func testARestoredSessionStillCarriesTheEntryUrlValues() {
        let controller = SessionGateController()
        controller.onResult(.created(session))

        let restored = recreate(controller).session
        XCTAssertEqual("s_abc", restored?.sessionId)
        XCTAssertEqual("st_xyz", restored?.sessionToken)
        XCTAssertEqual("gn-2026.08.1", restored?.testVersion)
        XCTAssertFalse((restored?.expiresAt ?? "").isEmpty)
    }

    // MARK: - 재응시 (KAN-34 2단계, KAN-107)

    /// 세션을 확보한 상태 = 결과 화면에서 [다시 테스트하기]를 누를 수 있는 상태.
    private func readyController() -> SessionGateController {
        let controller = SessionGateController()
        controller.onResult(.created(session))
        return controller
    }

    private var newSession: Session {
        Session(
            sessionId: "s_def",
            sessionToken: "st_uvw",
            testVersion: session.testVersion,
            scoreVersion: session.scoreVersion,
            expiresAt: session.expiresAt
        )
    }

    func testRetestHandsOverTheCurrentTokenAsTheDiscardTarget() {
        // 폐기와 발급이 한 요청이다.
        XCTAssertEqual("st_xyz", readyController().beginRetest())
    }

    func testSecondCallDuringARetestIsIgnored() {
        // 더블탭이 만든 세션은 곧바로 고아가 된다.
        let controller = readyController()
        XCTAssertEqual("st_xyz", controller.beginRetest())

        XCTAssertNil(controller.beginRetest())
        XCTAssertTrue(controller.retestInFlight)
    }

    func testNoRetestWithoutASessionToDiscard() {
        let controller = SessionGateController()

        XCTAssertNil(controller.beginRetest())
        XCTAssertFalse(controller.retestInFlight)
    }

    func testASuccessfulRetestSwitchesToTheNewSession() {
        let controller = readyController()
        _ = controller.beginRetest()

        let outcome = controller.onRetestResult(.created(newSession))

        XCTAssertEqual(.replaced(newSession), outcome)
        XCTAssertEqual(newSession, controller.session)
        XCTAssertFalse(controller.retestInFlight)
    }

    func testAFailedRetestLeavesThePreviousSessionAlone() {
        // 서버도 안 지웠고 결과 화면이 아직 조회한다.
        let controller = readyController()
        _ = controller.beginRetest()

        let outcome = controller.onRetestResult(.transportError(reason: "boom"))

        XCTAssertEqual(.failed(reason: .network, code: nil, retryAfterMs: nil), outcome)
        XCTAssertEqual(session, controller.session)
        XCTAssertEqual(.ready(session), controller.state)
    }

    func testAFailedRetestCarriesTheServerCodeAndWaitTimeThrough() {
        let controller = readyController()
        _ = controller.beginRetest()

        let outcome = controller.onRetestResult(
            rejected(code: "RATE_LIMITED", retryable: true, retryAfterMs: 5_000)
        )

        XCTAssertEqual(
            .failed(reason: .rateLimited, code: "RATE_LIMITED", retryAfterMs: 5_000),
            outcome
        )
    }

    func testAFailedRetestUsesTheSameJudgementRulesAsInitialCreation() {
        let controller = readyController()
        _ = controller.beginRetest()

        let outcome = controller.onRetestResult(
            rejected(code: "VALIDATION_FAILED", retryable: false, retryAfterMs: nil)
        )

        XCTAssertEqual(
            .failed(reason: .unsupported, code: "VALIDATION_FAILED", retryAfterMs: nil),
            outcome
        )
    }

    func testAFailedRetestCanBeStartedAgain() {
        let controller = readyController()
        _ = controller.beginRetest()
        _ = controller.onRetestResult(.transportError(reason: "boom"))

        XCTAssertEqual("st_xyz", controller.beginRetest())
    }

    func testTheRetestAfterASuccessfulOneHandsOverTheNewSessionsToken() {
        let controller = readyController()
        _ = controller.beginRetest()
        _ = controller.onRetestResult(.created(newSession))

        XCTAssertEqual("st_uvw", controller.beginRetest())
    }

    // MARK: - 폐기 대기 토큰 (KAN-34 2단계)

    func testAFirstAttemptHasNoSessionToDiscard() {
        XCTAssertNil(SessionGateController().pendingPreviousToken)
    }

    func testRestartAfterFinishingHandsTheDiscardedTokenToTheNextCreation() {
        // 재응시와 같은 폐기 경로다.
        let controller = readyController()

        controller.restart()

        XCTAssertEqual("st_xyz", controller.pendingPreviousToken)
    }

    func testReceivingANewSessionClearsThePendingDiscard() {
        // 발급이 곧 폐기 완료 통지다.
        let controller = readyController()
        controller.restart()

        controller.onResult(.created(newSession))

        XCTAssertNil(controller.pendingPreviousToken)
    }

    func testAFailedCreationKeepsThePendingDiscard() {
        // 다음 시도가 다시 실어야 한다.
        let controller = readyController()
        controller.restart()

        controller.onResult(.transportError(reason: "boom"))

        XCTAssertEqual("st_xyz", controller.pendingPreviousToken)
    }

    func testARestartWithNothingToDiscardDoesNotEraseAnEarlierPendingDiscard() {
        let controller = readyController()
        controller.restart() // 종료: 세션을 버리며 토큰을 적어 둔다
        controller.onResult(.transportError(reason: "boom"))

        controller.restart() // 실패 화면의 [다시 시도]: 버릴 세션이 없다

        XCTAssertEqual("st_xyz", controller.pendingPreviousToken)
    }

    func testASuccessfulRetestLeavesNoPendingDiscardEither() {
        // 그 요청이 이미 폐기를 실어 보냈다.
        let controller = readyController()
        _ = controller.beginRetest()
        _ = controller.onRetestResult(.created(newSession))

        XCTAssertNil(controller.pendingPreviousToken)
    }

    func testThePendingDiscardTokenDoesNotSurviveRecreation() {
        // 이미 버려진 익명 세션의 만료를 앞당길 뿐이다.
        let controller = readyController()
        controller.restart()

        XCTAssertNil(recreate(controller).pendingPreviousToken)
    }

    func testRecreationDuringARetestClearsTheInFlightFlagSoItCanBePressedAgain() {
        let controller = readyController()
        _ = controller.beginRetest()

        let restored = recreate(controller)

        XCTAssertFalse(restored.retestInFlight)
        XCTAssertEqual(session, restored.session)
    }

    // MARK: - 보조

    private func rejected(code: String?, retryable: Bool, retryAfterMs: Int64?) -> SessionResult {
        .rejected(code: code, message: "거절", retryable: retryable, retryAfterMs: retryAfterMs)
    }

    /// 저장 → 복원. 안드로이드 `rememberSaveable`이 구성 변경에서 하는 일을 그대로 흉내 낸다.
    private func recreate(_ controller: SessionGateController) -> SessionGateController {
        SessionGateController.restored(from: controller.saved())
    }
}
