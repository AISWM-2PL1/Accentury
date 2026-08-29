import XCTest
@testable import AccenturyCore

/// 안드로이드 `recording/VoiceCheckControllerTest.kt`의 1:1 이식본 (12개).
final class VoiceCheckControllerTests: XCTestCase {

    private let frameMs: Int64 = 32
    private let centerHz: Float = 220

    /// 통과선을 넘는 청크 볼륨
    private var loud: Double { AudioQuality.quietRmsThreshold * 3 }
    /// 통과선에 못 미치는 청크 볼륨
    private var quiet: Double { AudioQuality.quietRmsThreshold / 2 }

    /// 실제 엔진과 같은 32ms 간격으로 유성 프레임을 만든다
    private func voiced(_ count: Int, hz: Float? = nil, startMs: Int64 = 0) -> [RecordingEngine.PitchFrame] {
        (0..<count).map {
            RecordingEngine.PitchFrame(timestampMs: startMs + Int64($0) * frameMs, pitchHz: hz ?? centerHz)
        }
    }

    private func unvoiced(_ count: Int, startMs: Int64 = 0) -> [RecordingEngine.PitchFrame] {
        (0..<count).map { RecordingEngine.PitchFrame(timestampMs: startMs + Int64($0) * frameMs, pitchHz: nil) }
    }

    /// Listening이 아니면 **실패**다. `XCTSkip`을 던지면 회귀가 조용히 건너뛰기로 감춰진다.
    private struct NotListening: Error { let state: VoiceCheckState }

    private func listening(
        _ state: VoiceCheckState,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws -> VoiceCheckState.Listening {
        guard case let .listening(listening) = state else {
            XCTFail("Listening이 아님: \(state)", file: file, line: line)
            throw NotListening(state: state)
        }
        return listening
    }

    func test말하기_전에는_안내가_말해_달라는_쪽이다() throws {
        let controller = VoiceCheckController()

        let initial = try listening(controller.state)
        XCTAssertEqual(.sayIt, initial.hint)
        XCTAssertNil(initial.centerHz)

        // 무성 프레임만 들어와도 마찬가지다 - 소리는 났는데 목소리가 아니었던 경우다.
        XCTAssertFalse(controller.onProgress(rms: loud, newFrames: unvoiced(5)))
        XCTAssertEqual(.sayIt, try listening(controller.state).hint)
    }

    func test유성_프레임이_모자라면_계속_듣는다() throws {
        let controller = VoiceCheckController()

        // 볼륨은 충분한데 중심을 잠글 만큼 말하지 않았다
        let stopRequested = controller.onProgress(rms: loud, newFrames: voiced(centerMinVoicedFrames - 1))

        XCTAssertFalse(stopRequested, "아직 판정이 안 났으니 엔진을 세우지 않는다")
        let state = try listening(controller.state)
        XCTAssertEqual(.keepGoing, state.hint)
        XCTAssertEqual(centerMinVoicedFrames - 1, state.voicedCount)
        XCTAssertNil(state.centerHz, "8개에 못 미치면 중심이 안 잠긴다")
        XCTAssertTrue(state.loudEnough)
    }

    func test중심은_잡혔는데_볼륨이_모자라면_더_크게_말하라고_한다() throws {
        let controller = VoiceCheckController()

        let stopRequested = controller.onProgress(rms: quiet, newFrames: voiced(centerMinVoicedFrames))

        XCTAssertFalse(stopRequested, "볼륨이 모자라면 아직 준비가 아니다")
        let state = try listening(controller.state)
        XCTAssertEqual(.tooQuiet, state.hint)
        XCTAssertEqual(centerHz, try XCTUnwrap(state.centerHz), accuracy: 1e-3)
        XCTAssertFalse(state.loudEnough)
    }

    func test조용히_잡은_중심은_뒤늦게_크게_말해도_그대로다() throws {
        let controller = VoiceCheckController()

        _ = controller.onProgress(rms: quiet, newFrames: voiced(centerMinVoicedFrames))
        // 안내를 보고 크게 다시 말했다. 이번엔 훨씬 높은 음이지만 중심은 이미 잠겼다.
        let stopRequested = controller.onProgress(
            rms: loud,
            newFrames: voiced(8, hz: centerHz * 2, startMs: Int64(centerMinVoicedFrames) * frameMs)
        )

        XCTAssertTrue(stopRequested, "준비가 끝났으니 엔진을 세운다")
        guard case let .ready(frames, ready) = controller.state else {
            return XCTFail("Ready가 아님: \(controller.state)")
        }
        XCTAssertEqual(centerHz, ready, accuracy: 1e-3, "중심은 처음 8개의 중앙값이다")
        XCTAssertEqual(centerMinVoicedFrames + 8, frames.count)
    }

    func test한_번_크게_말했으면_뒤에_조용해져도_통과한다() {
        let controller = VoiceCheckController()

        // 크게 시작했지만 아직 프레임이 모자라고
        _ = controller.onProgress(rms: loud, newFrames: voiced(4))
        // 말끝이 잦아들며 나머지 프레임이 채워졌다
        let stopRequested = controller.onProgress(rms: quiet, newFrames: voiced(4, startMs: 4 * frameMs))

        XCTAssertTrue(stopRequested, "볼륨 판정은 최댓값 기준이라 앞의 큰 소리가 근거로 남는다")
        guard case .ready = controller.state else { return XCTFail("Ready가 아님: \(controller.state)") }
    }

    func test준비된_뒤_도착한_청크는_판정을_흔들지_못한다() {
        let controller = VoiceCheckController()
        _ = controller.onProgress(rms: loud, newFrames: voiced(centerMinVoicedFrames))
        let ready = controller.state

        // 정지 요청과 실제 정지 사이에 청크가 한둘 더 온다
        let stopRequested = controller.onProgress(
            rms: 0,
            newFrames: voiced(4, startMs: Int64(centerMinVoicedFrames) * frameMs)
        )

        XCTAssertFalse(stopRequested)
        XCTAssertEqual(ready, controller.state)
    }

    func test듣기가_끝났는데_준비가_아니면_시간_초과다() {
        let controller = VoiceCheckController()
        _ = controller.onProgress(rms: quiet, newFrames: voiced(centerMinVoicedFrames))

        controller.onStopped()

        guard case let .timedOut(frames, hint) = controller.state else {
            return XCTFail("TimedOut이 아님: \(controller.state)")
        }
        XCTAssertEqual(.tooQuiet, hint, "무엇이 모자랐는지가 남는다")
        XCTAssertEqual(centerMinVoicedFrames, frames.count)
    }

    func test준비된_뒤의_종료는_준비를_그대로_둔다() {
        let controller = VoiceCheckController()
        _ = controller.onProgress(rms: loud, newFrames: voiced(centerMinVoicedFrames))
        let ready = controller.state

        // 준비가 되면 엔진 정지를 요청하므로 종료 통지는 늘 이 뒤에 온다
        controller.onStopped()

        XCTAssertEqual(ready, controller.state)
    }

    func test엔진_실패는_실패_상태가_되고_종료_통지가_덮지_않는다() {
        let controller = VoiceCheckController()

        controller.onFailed("녹음 권한 없음")
        controller.onStopped()

        guard case let .failed(reason) = controller.state else {
            return XCTFail("Failed가 아님: \(controller.state)")
        }
        XCTAssertEqual("녹음 권한 없음", reason)
    }

    func test다시_시도하면_전부_초기화된다() throws {
        let controller = VoiceCheckController()
        _ = controller.onProgress(rms: loud, newFrames: voiced(centerMinVoicedFrames))
        guard case .ready = controller.state else { return XCTFail("Ready가 아님") }

        controller.restart()

        let state = try listening(controller.state)
        XCTAssertEqual([], state.frames)
        XCTAssertEqual(0, state.voicedCount)
        XCTAssertEqual(0, state.level)
        XCTAssertFalse(state.loudEnough, "볼륨 기록도 함께 비운다")
        XCTAssertNil(state.centerHz)
        XCTAssertEqual(.sayIt, state.hint)
    }

    func test레벨은_최근_청크값이라_조용해지면_함께_내려간다() throws {
        let controller = VoiceCheckController()

        _ = controller.onProgress(rms: loud, newFrames: voiced(2))
        _ = controller.onProgress(rms: quiet, newFrames: voiced(2, startMs: 2 * frameMs))

        let state = try listening(controller.state)
        XCTAssertEqual(quiet, state.level)
        XCTAssertTrue(state.loudEnough, "통과 판정은 최댓값이라 내려가지 않는다")
    }

    /// 안드로이드는 `frames.toList()` 복사가 없으면 이미 내보낸 스냅샷이 뒤에서 바뀌었다.
    /// Swift 배열은 값 타입이라 같은 보장이 문법에 들어 있고, 이 테스트가 그 사실을 못 박는다.
    func test상태에_실린_프레임_목록은_값이라_다음_청크에_안_바뀐다() throws {
        let controller = VoiceCheckController()

        _ = controller.onProgress(rms: quiet, newFrames: voiced(2))
        let first = try listening(controller.state).frames
        _ = controller.onProgress(rms: quiet, newFrames: voiced(2, startMs: 2 * frameMs))

        XCTAssertEqual(2, first.count)
    }
}
