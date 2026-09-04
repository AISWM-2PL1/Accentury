import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/testflow/TestFlowControllerTest.kt`의 1:1 이식본.
///
/// 안드로이드의 "회전"(구성 변경 → 컴포지션 재생성)에 해당하는 자리는 iOS에서 화면 재생성·
/// 프로세스 복원이다. 판정 대상은 같다: 저장했다가 되살렸을 때 진행이 그대로 이어지는가.
final class TestFlowControllerTests: XCTestCase {

    func testWebIsInFrontAtFirst() {
        let controller = TestFlowController()
        XCTAssertEqual(.web, controller.phase)
    }

    func testGrantedMicGoesStraightToRecording() {
        let controller = TestFlowController()
        let start = voiceItem()

        controller.onStartVoiceItem(start, micGranted: true)

        XCTAssertEqual(.recording(start), controller.phase)
    }

    func testMissingMicRaisesTheGateFirst() {
        // 설정에서 회수된 경우.
        let controller = TestFlowController()
        let start = voiceItem()

        controller.onStartVoiceItem(start, micGranted: false)

        XCTAssertEqual(.needsPermission(start), controller.phase)
    }

    func testPassingTheGateContinuesToTheWaitingItem() {
        // 문항을 잃지 않는다.
        let controller = TestFlowController()
        let start = voiceItem()
        controller.onStartVoiceItem(start, micGranted: false)

        controller.onPermissionGranted()

        XCTAssertEqual(.recording(start), controller.phase)
    }

    func testDuplicateRequestDuringRecordingIsIgnored() {
        // 진행 중인 녹음을 갈아치우지 않는다.
        let controller = TestFlowController()
        let first = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(first, micGranted: true)

        controller.onStartVoiceItem(voiceItem(itemId: "item_2", number: 2), micGranted: true)

        XCTAssertEqual(.recording(first), controller.phase)
    }

    func testRequestArrivingWhileTheGateStandsIsAlsoIgnored() {
        let controller = TestFlowController()
        let first = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(first, micGranted: false)

        controller.onStartVoiceItem(voiceItem(itemId: "item_2", number: 2), micGranted: true)

        XCTAssertEqual(.needsPermission(first), controller.phase)
    }

    func testGrantNoticeWithoutAGateIsIgnored() {
        // 설정 복귀 재확인이 화면을 바꾸지 않는다.
        let controller = TestFlowController()

        controller.onPermissionGranted()

        XCTAssertEqual(.web, controller.phase)
    }

    func testGrantNoticeDuringRecordingDoesNotRewindTheScreen() {
        let controller = TestFlowController()
        let start = voiceItem()
        controller.onStartVoiceItem(start, micGranted: true)

        controller.onPermissionGranted()

        XCTAssertEqual(.recording(start), controller.phase)
    }

    /*
     * KAN-146으로 뒤집힌 결정이다. 예전에는 [다음] 즉시 웹으로 돌아갔지만, 결과는 업로드가 끝나야
     * 나가므로 그 사이 웹의 대기 화면이 한 번 드러났다. 진행이 업로드를 기다리지 않는다는 원칙은
     * 그대로다 — 붙드는 것은 화면뿐이고 대기 시도는 즉시 등록된다(아래 조립 테스트가 그걸 본다).
     */
    func testFinishingARecordingHoldsTheScreenUntilTheResultGoesOut() {
        let controller = TestFlowController()
        let start = voiceItem()
        controller.onStartVoiceItem(start, micGranted: true)

        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        XCTAssertEqual(.submitting(start, attemptId: "at_1"), controller.phase)
    }

    /*
     * 결과를 조립했다는 것과 웹이 그것을 받아 다음 문항을 그렸다는 것은 다르다. 조립 자리에서 놓으면
     * 걷힌 아래에 아직 앞 문항의 대기 화면이 남아 한 프레임 드러난다(실기에서 33ms 노출로 확인).
     */
    func testAssemblingTheResultAloneDoesNotReleaseTheScreen() {
        let controller = TestFlowController()
        let start = voiceItem()
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        controller.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")])

        XCTAssertEqual(.submitting(start, attemptId: "at_1"), controller.phase)
    }

    func testTheScreenIsReleasedOnceInjectionFinishes() {
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)
        controller.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")])

        controller.onResultDelivered(attemptId: "at_1")

        XCTAssertEqual(.web, controller.phase)
    }

    /*
     * 앞 문항의 뒤늦은 주입 완료가 새로 뜬 화면을 걷어버리면, 사용자는 녹음 화면이 이유 없이
     * 사라지는 것을 본다.
     */
    func testInjectionOfAnotherAttemptDoesNotReleaseTheCurrentScreen() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_3", number: 3)
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_3", durationMs: 3_200, quality: .normal)

        controller.onResultDelivered(attemptId: "at_1")

        XCTAssertEqual(.submitting(start, attemptId: "at_3"), controller.phase)
    }

    func testScreenIsHeldWhileTheUploadIsStillInFlight() {
        let controller = TestFlowController()
        let start = voiceItem()
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        controller.onUploadsChanged(["at_1": .inFlight])

        XCTAssertEqual(.submitting(start, attemptId: "at_1"), controller.phase)
    }

    /*
     * 업로드가 실패하면 결과는 영영 조립되지 않는다. 그걸 아는 자리에서 상한까지 기다리면 오버레이는
     * "제출 중…"이라 말하는데 같은 화면의 업로드 상태 바는 이미 "업로드 실패 [재시도]"를 띄운다 —
     * 한 화면이 서로 다른 두 말을 하는 구간이라 상한을 기다리지 않고 놓는다.
     */
    func testConfirmedUploadFailureReturnsToWebWithoutWaitingForTheCap() {
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        controller.onUploadsChanged(["at_1": .failed(retryable: true, message: "연결 실패")])

        XCTAssertEqual(.web, controller.phase)
    }

    /*
     * 상한은 위 두 경로(결과 도착·실패 확정) 어느 쪽도 오지 않는 경우를 받는 최후 안전망이다 —
     * 프로세스 사망 복원처럼 업로드 키 자체가 사라져 상태를 물어볼 곳이 없을 때가 그렇다.
     */
    func testWithoutAnyUploadTraceOnlyTheCapReturnsToWeb() {
        let controller = TestFlowController()
        let start = voiceItem()
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        controller.onUploadsChanged([:])
        XCTAssertEqual(.submitting(start, attemptId: "at_1"), controller.phase)

        controller.onSubmitTimeout(attemptId: "at_1", uploads: [:])

        XCTAssertEqual(.web, controller.phase)
    }

    /*
     * 결과가 먼저 나가 다음 문항이 뜬 뒤 뒤늦게 도착한 타이머가 새 화면을 걷어버리면, 사용자는
     * 녹음 화면이 이유 없이 사라지는 것을 본다.
     */
    func testCapNoticeForAnotherAttemptDoesNotReleaseTheCurrentScreen() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_2")
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_2", durationMs: 3_200, quality: .normal)

        controller.onSubmitTimeout(attemptId: "at_1", uploads: [:])

        XCTAssertEqual(.submitting(start, attemptId: "at_2"), controller.phase)
    }

    func testSameScreenSurvivesRecreationWhileSubmitting() {
        let controller = TestFlowController()
        let start = voiceItem()
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        let restored = recreate(controller)

        XCTAssertEqual(.submitting(start, attemptId: "at_1"), restored.phase)
    }

    /*
     * 완화된 가드를 못 박는다 (KAN-146). 가드가 지키려는 것은 아직 손에 있는 녹음인데, 제출 뒤에는
     * PCM이 이미 업로드로 넘어가 잃을 것이 없다. 반대로 여기서 막으면 웹이 다음 문항으로 넘어갔는데
     * 네이티브가 따라가지 못해 진행이 멈춘다 — 진행의 정본은 웹이다.
     */
    func testNextItemRequestArrivingWhileSubmittingIsAccepted() {
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId: "item_1"), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        let next = voiceItem(itemId: "item_3", number: 3)
        controller.onStartVoiceItem(next, micGranted: true)

        XCTAssertEqual(.recording(next), controller.phase)
    }

    /*
     * 화면이 다음 문항으로 넘어가도 앞 시도는 대기 목록에 그대로 남아야 한다 — 붙드는 것은 화면뿐이지
     * 진행이 업로드를 기다리는 것이 아니다.
     */
    func testPreviousAttemptStillShipsAfterMovingToTheNextItem() {
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId: "item_1"), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)
        controller.onStartVoiceItem(voiceItem(itemId: "item_3", number: 3), micGranted: true)

        let results = controller.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")])

        XCTAssertEqual(["item_1"], results.map(\.itemId))
        // 앞 시도의 결과가 나가면서 새 문항의 녹음 화면을 실수로 걷어버리면 안 된다.
        XCTAssertEqual(.recording(voiceItem(itemId: "item_3", number: 3)), controller.phase)
    }

    /*
     * 타이머는 업로드가 목록에 오르기 전 한 프레임에도 걸릴 수 있다. 발화 시점에 다시 확인하지 않으면
     * 그새 시작된(또는 백그라운드에서 계속되던) 업로드를 시간이 끊어, 없애려던 대기 화면이 그 자리에
     * 생긴다.
     */
    func testCapDoesNotReleaseTheScreenWhileTheUploadIsInFlight() {
        let controller = TestFlowController()
        let start = voiceItem()
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        controller.onSubmitTimeout(attemptId: "at_1", uploads: ["at_1": .inFlight])

        XCTAssertEqual(.submitting(start, attemptId: "at_1"), controller.phase)
    }

    func testFinishedUploadMakesTheResultReadyForTheWeb() {
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId: "item_1"), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        let results = controller.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")])

        XCTAssertEqual(1, results.count)
        let result = results[0]
        XCTAssertEqual("item_1", result.itemId)
        XCTAssertEqual("at_1", result.attemptId)
        XCTAssertEqual("job_1", result.analysisJobId)
        XCTAssertEqual(3_200, result.durationMs)
        XCTAssertEqual(.normal, result.qualityStatus)
    }

    func testUploadStillInFlightShipsNoResult() {
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        XCTAssertTrue(controller.onUploadsChanged(["at_1": .inFlight]).isEmpty)
    }

    func testFailedUploadBecomesAResultOnlyWhenARetrySucceeds() {
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        let whileFailed = controller.onUploadsChanged(
            ["at_1": .failed(retryable: true, message: "timeout")]
        )
        XCTAssertTrue(whileFailed.isEmpty)

        let afterRetry = controller.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")])
        XCTAssertEqual(["at_1"], afterRetry.map(\.attemptId))
    }

    func testTheSameAttemptIsNeverShippedTwice() {
        // 웹은 문항당 결과 1회를 전제로 진행한다.
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)
        let uploads: [String: UploadState] = ["at_1": .done(analysisJobId: "job_1")]

        XCTAssertEqual(1, controller.onUploadsChanged(uploads).count)
        XCTAssertTrue(controller.onUploadsChanged(uploads).isEmpty)
    }

    func testFinishNoticeFromOutsideTheRecordingScreenRegistersNoAttempt() {
        let controller = TestFlowController()

        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        XCTAssertEqual(.web, controller.phase)
        XCTAssertTrue(controller.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")]).isEmpty)
    }

    func testSubmitWithoutPcmReturnsToWebWithoutRegisteringAnAttempt() {
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted: true)

        controller.onRecordingExit()

        XCTAssertEqual(.web, controller.phase)
        XCTAssertTrue(controller.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")]).isEmpty)
    }

    func testGoingBackDoesNotDiscardPendingAttemptsOfEarlierItems() {
        // 진행 전체를 초기화하지 않는다.
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId: "item_1"), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        controller.onStartVoiceItem(voiceItem(itemId: "item_2", number: 2), micGranted: true)
        controller.onRecordingExit()

        let results = controller.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")])
        XCTAssertEqual(["item_1"], results.map(\.itemId))
    }

    func testAnItemReturnedFromCanBeRequestedAgain() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingExit()

        controller.onStartVoiceItem(start, micGranted: true)

        XCTAssertEqual(.recording(start), controller.phase)
    }

    /*
     * 같은 문항의 재녹음은 여전히 막지 않는다. 다만 앞 시도는 여기서 밀려난다 (KAN-147) — 한 문항에
     * 살아 있는 시도가 둘이면 상태 바에 앞 시도의 [재시도]가 그대로 서 있고, 그걸 누르면 같은 문항에
     * 분석 작업이 둘 생겨 웹이 결과를 두 번 받는다.
     */
    func testRerecordingTheSameItemSupersedesThePreviousAttempt() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(start, micGranted: true)
        XCTAssertEqual(
            [],
            controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)
        )

        controller.onStartVoiceItem(start, micGranted: true)
        let superseded = controller.onRecordingFinished(
            attemptId: "at_2",
            durationMs: 4_100,
            quality: .normal
        )

        // 밀려난 시도의 업로드를 실제로 폐기하는 것은 호출자 몫이라 attemptId만 돌려준다.
        XCTAssertEqual(["at_1"], superseded)

        let results = controller.onUploadsChanged(
            [
                "at_1": .done(analysisJobId: "job_1"),
                "at_2": .done(analysisJobId: "job_2"),
            ]
        )
        XCTAssertEqual(["at_2"], results.map(\.attemptId))
        XCTAssertEqual(["item_1"], results.map(\.itemId))
    }

    func testPendingAttemptsOfOtherItemsAreNotSuperseded() {
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId: "item_1"), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        controller.onStartVoiceItem(voiceItem(itemId: "item_2", number: 2), micGranted: true)
        let superseded = controller.onRecordingFinished(
            attemptId: "at_2",
            durationMs: 3_200,
            quality: .normal
        )

        XCTAssertEqual([], superseded)
        let results = controller.onUploadsChanged(
            [
                "at_1": .done(analysisJobId: "job_1"),
                "at_2": .done(analysisJobId: "job_2"),
            ]
        )
        XCTAssertEqual(["at_1", "at_2"], results.map(\.attemptId))
    }

    /*
     * 재녹음 전환 — 서버가 이 녹음을 못 쓰겠다고 답한 경우다 (KAN-147, 2026-08-25 B안).
     * 전송 실패는 여기로 오지 않고 [재시도]가 계속 서 있는다. 웹은 네이티브 쪽 실패를 통지받지
     * 않아 그 문항의 대기 화면에 멈춰 있으므로, 네이티브가 같은 문항의 녹음 화면을 다시 열어도
     * 진행을 앞지르지 않는다.
     */
    func testGivingUpAnUploadReopensThatItemsRecordingScreen() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)
        // 실패가 확정되면 화면은 웹으로 돌아가 있고 대기 시도만 남는다 (KAN-146).
        controller.onUploadsChanged(["at_1": .failed(retryable: false, message: "timeout")])
        XCTAssertEqual(.web, controller.phase)

        XCTAssertTrue(controller.onUploadGivenUp(attemptId: "at_1", micGranted: true))

        XCTAssertEqual(.recording(start, afterUploadFailure: true), controller.phase)
        // 결과가 영영 조립되지 않을 시도라 대기 목록에서도 빠진다.
        XCTAssertTrue(controller.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")]).isEmpty)
    }

    /*
     * 제출을 기다리는 중에 포기가 확정되는 경로. 같은 문항의 녹음으로 되돌아가는 것은
     * continuesFrom이 이미 다루는 "제출에서 녹음으로 되돌아온 것"이라 호출자 쪽 되감기가 돈다.
     */
    func testGivingUpWhileSubmittingAlsoReturnsToTheSameItemsRecording() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        XCTAssertTrue(controller.onUploadGivenUp(attemptId: "at_1", micGranted: true))

        XCTAssertEqual(.recording(start, afterUploadFailure: true), controller.phase)
    }

    /*
     * 포기 결선과 결과 전달 결선은 같은 키(uploads)로 도는 별개의 이펙트다. 어느 쪽이 먼저 돌든
     * 결과가 같아야 한다 — 이 두 테스트가 그 순서 독립성을 못 박는다. onUploadsChanged가 web으로
     * 내리는 조건은 "submitting에서 그 시도가 실패"뿐이라, 포기가 먼저 열어 둔 recording은
     * 건드리지 않는다.
     */
    func testGiveUpFirstThenFailureNoticeDoesNotCloseTheReopenedRecording() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)
        let failed: [String: UploadState] = ["at_1": .failed(retryable: false, message: "timeout")]

        XCTAssertTrue(controller.onUploadGivenUp(attemptId: "at_1", micGranted: true))
        XCTAssertTrue(controller.onUploadsChanged(failed).isEmpty)

        XCTAssertEqual(.recording(start, afterUploadFailure: true), controller.phase)
    }

    func testFailureNoticeFirstThenGiveUpStillOpensTheSameRecording() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)
        let failed: [String: UploadState] = ["at_1": .failed(retryable: false, message: "timeout")]

        XCTAssertTrue(controller.onUploadsChanged(failed).isEmpty)
        XCTAssertEqual(.web, controller.phase)
        XCTAssertTrue(controller.onUploadGivenUp(attemptId: "at_1", micGranted: true))

        XCTAssertEqual(.recording(start, afterUploadFailure: true), controller.phase)
        // 폐기 뒤 uploads가 비어 두 이펙트가 다시 돌아도 화면은 그대로다.
        XCTAssertTrue(controller.onUploadsChanged([:]).isEmpty)
        XCTAssertFalse(controller.onUploadGivenUp(attemptId: "at_1", micGranted: true))
        XCTAssertEqual(.recording(start, afterUploadFailure: true), controller.phase)
    }

    /*
     * 권한 팝업이 한 번 끼어도 재녹음 사유는 살아남아야 한다 (KAN-147). 게이트가 그 값을 들고
     * 있지 않으면 통과 직후 열리는 녹음 화면에서 "왜 다시 녹음하는지"가 사라진다.
     */
    func testRevokedMicRaisesTheGateCarryingTheItemAndTheReason() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        XCTAssertTrue(
            controller.onUploadGivenUp(attemptId: "at_1", micGranted: false, message: "녹음이 너무 깁니다")
        )

        XCTAssertEqual(
            .needsPermission(start, afterUploadFailure: true, failureMessage: "녹음이 너무 깁니다"),
            controller.phase
        )

        controller.onPermissionGranted()

        XCTAssertEqual(
            .recording(start, afterUploadFailure: true, failureMessage: "녹음이 너무 깁니다"),
            controller.phase
        )
    }

    /*
     * 게이트에 선 채로 화면이 재생성돼도 사유를 잃지 않는다 — 저장 형식은 두 페이즈의 재녹음
     * 사유를 같은 자리에 담는다.
     */
    func testTheReasonAtTheGateSurvivesRecreation() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)
        controller.onUploadGivenUp(attemptId: "at_1", micGranted: false, message: "소리가 너무 작습니다")

        let restored = recreate(controller)

        XCTAssertEqual(
            .needsPermission(start, afterUploadFailure: true, failureMessage: "소리가 너무 작습니다"),
            restored.phase
        )
        restored.onPermissionGranted()
        XCTAssertEqual(
            .recording(start, afterUploadFailure: true, failureMessage: "소리가 너무 작습니다"),
            restored.phase
        )
    }

    /*
     * 왜 다시 녹음해야 하는지는 서버만 안다 (KAN-147, B안). 앱이 지어낸 일반 문구로 덮으면
     * 사용자는 다음 녹음에서 같은 실패를 반복한다.
     */
    func testServerRejectionTextRidesAllTheWayToTheReopenedRecording() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        XCTAssertTrue(
            controller.onUploadGivenUp(attemptId: "at_1", micGranted: true, message: "녹음이 너무 깁니다")
        )

        XCTAssertEqual(
            .recording(start, afterUploadFailure: true, failureMessage: "녹음이 너무 깁니다"),
            controller.phase
        )
    }

    func testTheReopenedRecordingKeepsTheServerTextAcrossRecreation() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)
        controller.onUploadGivenUp(attemptId: "at_1", micGranted: true, message: "녹음이 너무 깁니다")

        let restored = recreate(controller)

        XCTAssertEqual(
            .recording(start, afterUploadFailure: true, failureMessage: "녹음이 너무 깁니다"),
            restored.phase
        )
    }

    /*
     * 앞 문항의 뒤늦은 포기가 손에 든 녹음을 갈아치우면 사용자는 방금 녹음하던 것을 잃는다.
     * 시도는 거둬가되(업로드는 폐기해야 한다) 화면은 건드리지 않는다.
     */
    func testGiveUpArrivingWhileRecordingAnotherItemLeavesTheScreenAlone() {
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId: "item_1"), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)
        let next = voiceItem(itemId: "item_2", number: 2)
        controller.onStartVoiceItem(next, micGranted: true)

        XCTAssertTrue(controller.onUploadGivenUp(attemptId: "at_1", micGranted: true))

        XCTAssertEqual(.recording(next), controller.phase)
        XCTAssertTrue(controller.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")]).isEmpty)
    }

    func testGiveUpArrivingWhileRerecordingTheSameItemAlsoLeavesTheScreenAlone() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)
        controller.onUploadsChanged(["at_1": .failed(retryable: true, message: "timeout")])
        controller.onStartVoiceItem(start, micGranted: true)

        XCTAssertTrue(controller.onUploadGivenUp(attemptId: "at_1", micGranted: true))

        // 이미 서 있던 녹음 화면이라 재개 표식이 붙지 않는다.
        XCTAssertEqual(.recording(start), controller.phase)
    }

    func testGivingUpAnUnknownAttemptDoesNothing() {
        // 이미 밀려난 시도가 여기로 온다.
        let controller = TestFlowController()

        XCTAssertFalse(controller.onUploadGivenUp(attemptId: "at_unknown", micGranted: true))

        XCTAssertEqual(.web, controller.phase)
    }

    /*
     * 대기 시도가 어느 문항의 것이었는지까지 저장한다 (KAN-147) — 복원 뒤에 포기가 확정돼도
     * 녹음 화면을 다시 세울 수 있어야 한다. 문항 문구, 번호, 가이드 곡선이 전부 필요한데
     * 결과 조립용 메타에는 itemId밖에 없다.
     */
    func testGiveUpAfterRestoreAlsoReopensThatItemsRecording() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)
        controller.onUploadsChanged(["at_1": .failed(retryable: true, message: "timeout")])

        let restored = recreate(controller)
        XCTAssertEqual(.web, restored.phase)

        XCTAssertTrue(restored.onUploadGivenUp(attemptId: "at_1", micGranted: true))

        XCTAssertEqual(.recording(start, afterUploadFailure: true), restored.phase)
    }

    func testTheReopenedRecordingKeepsItsReasonAcrossRecreation() {
        let controller = TestFlowController()
        let start = voiceItem(itemId: "item_1")
        controller.onStartVoiceItem(start, micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)
        controller.onUploadGivenUp(attemptId: "at_1", micGranted: true)

        let restored = recreate(controller)

        XCTAssertEqual(.recording(start, afterUploadFailure: true), restored.phase)
    }

    func testResultsShipInRegistrationOrderEvenWhenManyFinishAtOnce() {
        let controller = TestFlowController()
        for (index, itemId) in ["item_1", "item_2", "item_3"].enumerated() {
            controller.onStartVoiceItem(voiceItem(itemId: itemId, number: index + 1), micGranted: true)
            controller.onRecordingFinished(
                attemptId: "at_\(index + 1)",
                durationMs: 3_000,
                quality: .normal
            )
        }

        let results = controller.onUploadsChanged(
            [
                "at_2": .done(analysisJobId: "job_2"),
                "at_3": .done(analysisJobId: "job_3"),
                "at_1": .done(analysisJobId: "job_1"),
            ]
        )

        XCTAssertEqual(["item_1", "item_2", "item_3"], results.map(\.itemId))
    }

    func testRecordingScreenSurvivesRecreation() {
        // 녹음을 든 객체는 살아남는데 화면만 사라지면 진행이 멈춘다.
        let controller = TestFlowController()
        let start = voiceItem()
        controller.onStartVoiceItem(start, micGranted: true)

        let restored = recreate(controller)

        XCTAssertEqual(.recording(start), restored.phase)
    }

    func testGateAndItsWaitingItemSurviveRecreation() {
        let controller = TestFlowController()
        let start = voiceItem()
        controller.onStartVoiceItem(start, micGranted: false)

        let restored = recreate(controller)

        XCTAssertEqual(.needsPermission(start), restored.phase)
        restored.onPermissionGranted()
        XCTAssertEqual(.recording(start), restored.phase)
    }

    func testPendingAttemptsSurviveRecreationSoLateUploadsStillShip() {
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId: "item_1"), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .tooQuiet)

        let restored = recreate(controller)

        let results = restored.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")])
        XCTAssertEqual(1, results.count)
        XCTAssertEqual("item_1", results[0].itemId)
        XCTAssertEqual(3_200, results[0].durationMs)
        XCTAssertEqual(.tooQuiet, results[0].qualityStatus)
    }

    func testAlreadyShippedResultsDoNotShipAgainAfterRecreation() {
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)
        let uploads: [String: UploadState] = ["at_1": .done(analysisJobId: "job_1")]
        controller.onUploadsChanged(uploads)

        let restored = recreate(controller)

        XCTAssertTrue(restored.onUploadsChanged(uploads).isEmpty)
    }

    func testAttemptsWithASurvivingUploadPassThePruneAndBecomeResults() {
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId: "item_1"), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        let restored = recreate(controller)
        restored.pruneAttemptsWithoutUpload(["at_1"])

        let results = restored.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")])
        XCTAssertEqual(["item_1"], results.map(\.itemId))
    }

    func testAttemptsWhoseUploadIsGoneArePruned() {
        // 프로세스 사망 복원의 가짜 대기.
        let controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId: "item_1"), micGranted: true)
        controller.onRecordingFinished(attemptId: "at_1", durationMs: 3_200, quality: .normal)

        let restored = recreate(controller)
        restored.pruneAttemptsWithoutUpload([])

        // 뒤늦게 같은 키의 완료가 들어와도 이 시도는 다시 살아나지 않는다.
        XCTAssertTrue(restored.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")]).isEmpty)
    }

    func testPruneKeepsOnlyKnownAttemptsAndPreservesRegistrationOrder() {
        let controller = TestFlowController()
        for (index, itemId) in ["item_1", "item_2", "item_3"].enumerated() {
            controller.onStartVoiceItem(voiceItem(itemId: itemId, number: index + 1), micGranted: true)
            controller.onRecordingFinished(
                attemptId: "at_\(index + 1)",
                durationMs: 3_000,
                quality: .normal
            )
        }

        controller.pruneAttemptsWithoutUpload(["at_1", "at_3"])

        let results = controller.onUploadsChanged(
            [
                "at_1": .done(analysisJobId: "job_1"),
                "at_2": .done(analysisJobId: "job_2"),
                "at_3": .done(analysisJobId: "job_3"),
            ]
        )
        XCTAssertEqual(["at_1", "at_3"], results.map(\.attemptId))
    }

    func testPruneDoesNotTouchTheScreenCurrentlyOnTop() {
        let controller = TestFlowController()
        let start = voiceItem()
        controller.onStartVoiceItem(start, micGranted: true)

        controller.pruneAttemptsWithoutUpload([])

        XCTAssertEqual(.recording(start), controller.phase)
    }

    /// 나중에 생긴 필드가 통째로 빠진 구버전 저장값도 그대로 복원된다 (KAN-147 기본값 규약).
    ///
    /// 손으로 쓴 픽스처인 이유: 지금 코드로는 이 형식을 만들 수 없다. 실제로 깨지는 경로는
    /// "구버전 앱이 저장 → 앱 업데이트 → 신버전이 복원"이라, 옛 형식의 문자열을 직접 넣어야 한다.
    func testLegacySavedValueWithoutTheLaterFieldsStillRestores() {
        let legacy = """
        {"phase":"RECORDING","start":{"itemId":"item_1","prompt":"마! 니 어데 가노?",\
        "itemNumber":1,"totalItems":3,"maxDurationMs":15000}}
        """

        guard let restored = TestFlowController.restored(from: legacy) else {
            XCTFail("구버전 저장값이 복원되지 않았다")
            return
        }

        // afterUploadFailure·failureMessage가 없으면 "웹 요청으로 정상 진입"이 기본값이다.
        XCTAssertEqual(.recording(voiceItem()), restored.phase)
        // attempts가 없으면 빈 목록이다 — 복원 직후의 정리가 지울 것도 없다.
        restored.pruneAttemptsWithoutUpload([])
        XCTAssertTrue(restored.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")]).isEmpty)
    }

    /// SavedAttempt의 `start`가 빠진 구버전 시도: 결과 조립은 정상이고 자동 재개만 못 한다.
    func testLegacySavedAttemptWithoutStartAssemblesButCannotReopenTheScreen() {
        let legacy = """
        {"phase":"WEB","attempts":[{"itemId":"item_1","attemptId":"at_1",\
        "durationMs":3200,"quality":"NORMAL"}]}
        """

        guard let restored = TestFlowController.restored(from: legacy) else {
            XCTFail("구버전 저장값이 복원되지 않았다")
            return
        }

        // start가 없어 다시 열 화면을 만들 수 없다 — 시도는 거둬가되 화면은 웹 그대로다.
        XCTAssertTrue(restored.onUploadGivenUp(attemptId: "at_1", micGranted: true))
        XCTAssertEqual(.web, restored.phase)
    }

    /// 위 픽스처가 실제로 조립까지 간다는 것을 따로 못 박는다 — 폐기 경로와 헷갈리지 않게.
    func testLegacySavedAttemptWithoutStartStillShipsItsResult() {
        let legacy = """
        {"phase":"WEB","attempts":[{"itemId":"item_1","attemptId":"at_1",\
        "durationMs":3200,"quality":"TOO_QUIET"}]}
        """

        let restored = TestFlowController.restored(from: legacy)!

        let results = restored.onUploadsChanged(["at_1": .done(analysisJobId: "job_1")])
        XCTAssertEqual(["item_1"], results.map(\.itemId))
        XCTAssertEqual(3_200, results[0].durationMs)
        XCTAssertEqual(.tooQuiet, results[0].qualityStatus)
    }

    func testBrokenSavedValueIsNotRestored() {
        // 새 컨트롤러로 시작한다.
        XCTAssertNil(TestFlowController.restored(from: "{not json"))
    }

    /// 저장 → 복원. 안드로이드 `rememberSaveable`이 구성 변경에서 하는 일을 그대로 흉내 낸다.
    private func recreate(_ controller: TestFlowController) -> TestFlowController {
        guard let restored = TestFlowController.restored(from: controller.saved()) else {
            XCTFail("복원에 실패했다")
            return TestFlowController()
        }
        return restored
    }

    private func voiceItem(itemId: String = "item_1", number: Int = 1) -> VoiceItemStart {
        VoiceItemStart(
            itemId: itemId,
            prompt: "마! 니 어데 가노?",
            itemNumber: number,
            totalItems: 3,
            maxDurationMs: 15_000
        )
    }
}
