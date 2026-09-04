import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/testflow/ContinuesFromTest.kt`의 1:1 이식본.
///
/// 오버레이가 화면에서 빠질 때 녹음 상태를 되감을지 정하는 판정 (KAN-146).
///
/// 이 판정이 틀리면 두 방향으로 깨진다: 너무 넓게 이어짐으로 보면 이미 제출해 PCM이 빠져나간 확인
/// 화면이 새 녹음 자리에 그대로 뜨고, 너무 좁게 보면 화면 재생성 한 번에 진행 중인 녹음이 죽는다.
/// 화면 없이 검증할 수 있게 순수 함수로 떼어 둔 이유가 이것이다.
final class ContinuesFromTests: XCTestCase {

    func testRecreatedWhileRecordingSameItemContinues() {
        let shown = TestFlowPhase.recording(voiceItem())

        XCTAssertTrue(continuesFrom(shown: shown, current: .recording(voiceItem())))
    }

    func testRecreatedWhileSubmittingSameItemContinues() {
        let shown = TestFlowPhase.submitting(voiceItem(), attemptId: "at_1")

        XCTAssertTrue(continuesFrom(shown: shown, current: .submitting(voiceItem(), attemptId: "at_1")))
    }

    /*
     * [다음]으로 제출에 들어가는 전이. 여기서 되감으면 방금 그린 '내 억양' 곡선이 제출 화면에서 사라진다.
     */
    func testRecordingToSubmittingOfSameItemContinues() {
        let shown = TestFlowPhase.recording(voiceItem())

        XCTAssertTrue(continuesFrom(shown: shown, current: .submitting(voiceItem(), attemptId: "at_1")))
    }

    /*
     * 웹이 결과를 못 받고 같은 문항을 다시 열었을 때 생기는 전이. 그 문항을 처음부터 다시 하는 것이라
     * 되감아야 한다 — 안 그러면 이미 제출한 확인 화면이 그대로 뜨고 거기서 [다음]은 아무 일도 못 한다.
     */
    func testSubmittingBackToRecordingOfSameItemDoesNotContinue() {
        let shown = TestFlowPhase.submitting(voiceItem(), attemptId: "at_1")

        XCTAssertFalse(continuesFrom(shown: shown, current: .recording(voiceItem())))
    }

    func testNextItemTakingOverTheSlotDoesNotContinue() {
        let shown = TestFlowPhase.submitting(voiceItem(), attemptId: "at_1")
        let next = TestFlowPhase.recording(voiceItem(itemId: "item_3", number: 3))

        XCTAssertFalse(continuesFrom(shown: shown, current: next))
        XCTAssertFalse(continuesFrom(shown: .recording(voiceItem()), current: next))
    }

    func testGoingBackToWebDoesNotContinue() {
        XCTAssertFalse(continuesFrom(shown: .recording(voiceItem()), current: .web))
        XCTAssertFalse(continuesFrom(shown: .submitting(voiceItem(), attemptId: "at_1"), current: .web))
    }

    func testPermissionGateStandingAgainDoesNotContinue() {
        // 통과 후 대기 상태에서 시작해야 한다.
        let shown = TestFlowPhase.submitting(voiceItem(), attemptId: "at_1")

        XCTAssertFalse(continuesFrom(shown: shown, current: .needsPermission(voiceItem())))
    }

    func testPhasesWithoutAnOverlayHaveNothingToContinue() {
        XCTAssertFalse(continuesFrom(shown: .web, current: .web))
        XCTAssertFalse(
            continuesFrom(shown: .needsPermission(voiceItem()), current: .recording(voiceItem()))
        )
    }

    private func voiceItem(itemId: String = "item_1", number: Int = 1) -> VoiceItemStart {
        VoiceItemStart(
            itemId: itemId,
            prompt: "밥 뭇나?",
            itemNumber: number,
            totalItems: 10,
            maxDurationMs: 10_000,
            guideF0: GuideF0(unit: "semitone", frameIntervalMs: 10, values: [0.0, 1.0])
        )
    }
}
