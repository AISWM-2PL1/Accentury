import XCTest
@testable import AccenturyCore

/// 안드로이드 `recording/RecordingViewModelTest.kt`의 1:1 이식본 (9개) + 마이크 반납 1건.
///
/// 코틀린은 `StandardTestDispatcher`로 시간을 밀어 청크 사이를 들여다봤다. 여기서는 대신
/// **방출된 상태를 전부 기록**해 두고 그 열을 본다 — 확인하려는 것이 "특정 시각의 화면"이 아니라
/// "청크를 넘기며 값이 어떻게 자라는가"라, 오히려 열 전체를 보는 편이 질문에 가깝다.
final class RecordingControllerTests: XCTestCase {

    /// 방출된 상태를 순서대로 모으는 상자. 진행 콜백이 어느 스레드에서 올지 정해져 있지 않아 잠근다.
    private final class StateRecorder: @unchecked Sendable {
        private let lock = NSLock()
        private var states: [RecordingUiState] = []

        func record(_ state: RecordingUiState) {
            lock.lock()
            states.append(state)
            lock.unlock()
        }

        func clear() {
            lock.lock()
            states.removeAll()
            lock.unlock()
        }

        var all: [RecordingUiState] {
            lock.lock()
            defer { lock.unlock() }
            return states
        }

        /// 녹음 중 방출된 것만. 첫 항목은 청크가 오기 전의 빈 상태다.
        var recordings: [RecordingUiState.Recording] {
            all.compactMap { if case let .recording(recording) = $0 { return recording } else { return nil } }
        }
    }

    private func makeController(
        limit: Int?,
        intervalNanos: UInt64 = 0,
        releaseDelay: TimeInterval = 0,
        failure: Error? = nil
    ) -> (RecordingController, FakePcmSource, StateRecorder) {
        let source = FakePcmSource(
            limit: limit,
            intervalNanos: intervalNanos,
            releaseDelay: releaseDelay,
            failure: failure
        )
        let controller = RecordingController(engine: RecordingEngine(source: source))
        let recorder = StateRecorder()
        controller.onStateChange = { recorder.record($0) }
        return (controller, source, recorder)
    }

    private func awaitReview(_ controller: RecordingController) async -> RecordingUiState.Review? {
        await waitUntil("Review로 넘어가지 않았다") {
            if case .review = controller.state { return true }
            return false
        }
        if case let .review(review) = controller.state { return review }
        return nil
    }

    func test시작_직후_Recording_상태가_되고_완료_시_Review로_전이된다() async throws {
        let (controller, _, _) = makeController(limit: 16)

        controller.startRecording()
        guard case .recording = controller.state else { return XCTFail("Recording이 아님: \(controller.state)") }

        let reviewed = await awaitReview(controller)
        let review = try XCTUnwrap(reviewed)
        XCTAssertEqual(.normal, review.quality)
        XCTAssertEqual(Int64(16 * chunkSize) * 1000 / Int64(sampleRate), review.durationMs)
        XCTAssertTrue(review.canProceed)
    }

    func test1초_미만_발화는_TOO_SHORT_판정으로_다음_진행이_차단된다() async throws {
        let (controller, _, _) = makeController(limit: 4)

        controller.startRecording()

        let reviewed = await awaitReview(controller)
        let review = try XCTUnwrap(reviewed)
        XCTAssertEqual(.tooShort, review.quality)
        XCTAssertFalse(review.canProceed)
    }

    func test재녹음은_새_attemptId를_발급한다() async throws {
        let (controller, _, _) = makeController(limit: 16)

        controller.startRecording()
        let firstReview = await awaitReview(controller)
        let first = try XCTUnwrap(firstReview).attemptId

        controller.retryRecording()
        await waitUntil("두 번째 녹음이 끝나지 않았다") {
            if case let .review(review) = controller.state { return review.attemptId != first }
            return false
        }
        guard case let .review(second) = controller.state else { return XCTFail("Review가 아님") }

        XCTAssertNotEqual(first, second.attemptId)
        XCTAssertTrue(second.attemptId.hasPrefix("at_"))
    }

    func testRecording_상태의_pitchFrames는_청크를_넘기며_누적된다() async throws {
        let (controller, _, recorder) = makeController(limit: 4)

        controller.startRecording()
        // 시작 직후에는 아직 아무 청크도 안 왔다.
        XCTAssertEqual([], recorder.recordings.first?.pitchFrames)

        _ = await awaitReview(controller)

        let withFrames = recorder.recordings.filter { !$0.pitchFrames.isEmpty }
        XCTAssertTrue(withFrames.count >= 2, "청크마다 상태가 나와야 한다: \(recorder.recordings.count)")
        let first = try XCTUnwrap(withFrames.first).pitchFrames
        let second = try XCTUnwrap(withFrames.dropFirst().first).pitchFrames
        XCTAssertTrue(second.count > first.count, "프레임이 늘어야 한다: \(first.count) -> \(second.count)")
        // 앞 청크의 프레임이 그대로 앞에 남아 있다 - 곡선이 매번 다시 그려져도 과거가 안 잘린다
        XCTAssertEqual(first, Array(second.prefix(first.count)))
    }

    func testReview_상태에_녹음_전체의_pitchFrames가_남는다() async throws {
        let (controller, _, _) = makeController(limit: 4)

        controller.startRecording()
        let reviewed = await awaitReview(controller)
        let review = try XCTUnwrap(reviewed)

        // 4청크 x 2048샘플이면 프레이머가 (8192 - 2048) / 512 + 1 = 13개 창을 완성한다
        XCTAssertEqual(13, review.pitchFrames.count)
        // 첫 창(0..2047)의 시각은 그 중앙인 1024샘플 = 64ms다.
        XCTAssertEqual(64, review.pitchFrames.first?.timestampMs)
    }

    func test두_번째_녹음은_빈_누적으로_시작한다() async throws {
        let (controller, _, recorder) = makeController(limit: 4)

        controller.startRecording()
        let firstReview = await awaitReview(controller)
        let first = try XCTUnwrap(firstReview)
        let afterFirstChunk = try XCTUnwrap(recorder.recordings.first { !$0.pitchFrames.isEmpty }).pitchFrames.count

        recorder.clear()
        controller.retryRecording()
        await waitUntil("두 번째 녹음이 끝나지 않았다") {
            if case let .review(review) = controller.state { return review.attemptId != first.attemptId }
            return false
        }

        let frames = try XCTUnwrap(recorder.recordings.first { !$0.pitchFrames.isEmpty }).pitchFrames
        XCTAssertEqual(afterFirstChunk, frames.count)
        // 시각이 첫 창의 중앙(64ms)으로 되돌아왔다 = 이전 녹음의 프레이머 상태가 안 남았다.
        XCTAssertEqual(64, frames.first?.timestampMs)
    }

    func test녹음_중_reset하면_진행_중이던_녹음이_상태를_덮어쓰지_못한다() async {
        // 청크 사이가 벌어져 있어야 녹음 도중을 붙잡을 수 있다.
        let (controller, _, _) = makeController(limit: nil, intervalNanos: 2_000_000)

        controller.startRecording()
        await waitUntil("첫 청크가 오지 않았다") {
            if case let .recording(recording) = controller.state { return recording.elapsedMs > 0 }
            return false
        }

        controller.reset()

        XCTAssertEqual(.idle, controller.state)
        XCTAssertNil(controller.consumeRecording())
        // 취소된 녹음이 뒤늦게 Review를 밀어 넣지 못한다.
        try? await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertEqual(.idle, controller.state)
    }

    /// 화면을 떠나면 마이크를 놓는다 — ``RecordingController/reset()``이 정지 요청과 취소를
    /// 함께 보내고, 소스의 정리 구간(실기기에서는 AVAudioEngine 정지·세션 반납)이 실제로 돈다.
    func test화면을_떠나면_마이크를_놓고_PCM도_남기지_않는다() async {
        let (controller, source, _) = makeController(limit: nil, intervalNanos: 2_000_000)

        controller.startRecording()
        await waitUntil("캡처가 열리지 않았다") { source.events.contains("open1") }

        controller.reset()

        await waitUntil("캡처가 반납되지 않았다: \(source.events.all)") { source.events.contains("release1") }
        XCTAssertNil(controller.consumeRecording())
        XCTAssertEqual(.idle, controller.state)
    }

    func test엔진_실패는_Failed_상태가_된다() async {
        let (controller, _, _) = makeController(limit: nil, failure: CaptureError("녹음 중 권한 회수"))

        controller.startRecording()
        await waitUntil("Failed로 넘어가지 않았다") {
            if case .failed = controller.state { return true }
            return false
        }

        guard case let .failed(reason) = controller.state else { return XCTFail("Failed가 아님") }
        XCTAssertTrue(reason.contains("권한"), "엔진이 준 문구가 그대로 남아야 한다: \(reason)")
    }

    func test다음으로_넘어가면_reset으로_Idle에_돌아오고_PCM은_1회만_소비된다() async {
        let (controller, _, _) = makeController(limit: 16)

        controller.startRecording()
        _ = await awaitReview(controller)

        let pcm = controller.consumeRecording()
        XCTAssertEqual(16 * chunkSize, pcm?.count)
        XCTAssertNil(controller.consumeRecording())

        controller.reset()
        XCTAssertEqual(.idle, controller.state)
    }
}
