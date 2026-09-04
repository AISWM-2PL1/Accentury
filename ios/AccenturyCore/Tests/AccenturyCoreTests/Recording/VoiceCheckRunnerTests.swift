import XCTest
@testable import AccenturyCore

/// 안드로이드 `recording/VoiceCheckViewModelTest.kt`의 이식본 (2개).
///
/// 보는 것은 하나다 — **마이크가 겹쳐 열리지 않는가**. 취소가 돌아왔다고 캡처가 풀린 것이
/// 아니라서(반납은 정리 구간에서 늦게 끝난다) 회전으로 stop() 직후 start()가 불리면 두 캡처가
/// 겹칠 수 있고, 실기기에서는 그 순간 "마이크 점유 중"으로 초기화가 실패한다.
final class VoiceCheckRunnerTests: XCTestCase {

    /// 청크 간격과 반납 지연. 코틀린 테스트의 32ms·200ms를 실시간으로 줄인 값이다 —
    /// 가상 시간이 없어 실제로 기다리므로, 순서를 가릴 수 있는 만큼만 벌려 둔다.
    private let chunkIntervalNanos: UInt64 = 2_000_000
    private let releaseDelay: TimeInterval = 0.05

    private func makeRunner() -> (VoiceCheckRunner, FakePcmSource) {
        let source = FakePcmSource(
            limit: nil,
            intervalNanos: chunkIntervalNanos,
            releaseDelay: releaseDelay
        )
        return (VoiceCheckRunner(engine: RecordingEngine(source: source)), source)
    }

    func teststop_직후_start는_이전_캡처가_마이크를_놓을_때까지_기다렸다가_연다() async {
        let (runner, source) = makeRunner()

        runner.start()
        await waitUntil("첫 캡처가 열리지 않았다") { source.events.contains("open1") }
        XCTAssertEqual(["open1"], source.events.all)

        // 회전: 화면이 빠지며 stop, 새 화면이 곧바로 start
        runner.stop()
        runner.start()
        await waitUntil("두 번째 캡처가 열리지 않았다: \(source.events.all)") { source.events.contains("open2") }

        // release1이 open2보다 **앞**에 있어야 한다. 뒤집히면 두 캡처가 겹쳐 열려
        // 실기기에서 "마이크 점유 중"으로 초기화가 실패한다.
        XCTAssertEqual(["open1", "release1", "open2"], source.events.all)

        runner.stop()
    }

    func teststop_뒤_start하면_다시_Listening으로_들어가_프레임을_새로_쌓는다() async throws {
        let (runner, source) = makeRunner()

        runner.start()
        await waitUntil("첫 캡처가 열리지 않았다") { source.events.contains("open1") }

        runner.stop()
        runner.start()
        guard case .listening = runner.state else { return XCTFail("Listening이 아님: \(runner.state)") }

        await waitUntil("두 번째 캡처의 프레임이 쌓이지 않았다: \(source.events.all)") {
            if case let .listening(listening) = runner.state { return !listening.frames.isEmpty }
            return false
        }
        XCTAssertTrue(source.events.contains("open2"), "두 번째 캡처가 열려야 한다: \(source.events.all)")
        guard case let .listening(listening) = runner.state else { return XCTFail("Listening이 아님") }
        // 새 캡처의 프레임이다 - 시각이 0부터 다시 시작한다.
        XCTAssertEqual(64, listening.frames.first?.timestampMs)

        runner.stop()
    }

    /// 화면을 떠나면 마이크를 놓는다 — ``VoiceCheckRunner/stop()``이 정지 요청과 취소를 함께
    /// 보내고, 소스의 정리 구간(실기기에서는 AVAudioEngine 정지·세션 반납)이 실제로 돈다.
    /// `RecordingControllerTests`의 같은 이름 테스트와 짝이다.
    ///
    /// **PCM은 애초에 없다** — 점검은 사용자를 재는 것이 아니라 마이크가 잘 열렸는지 확인하는
    /// 절차라 `listen()`이 Success의 `pcm`을 바인딩조차 하지 않는다 (FR-DP-02). 그래서 여기서는
    /// 녹음 쪽처럼 `consumeRecording()`이 nil인지 물을 자리가 없고, 대신 판정이 듣기 상태를
    /// 벗어났는지를 본다.
    ///
    /// 한계를 적어 둔다: 이 단언은 "캡처가 닫혔다"까지고 **"`requestStop()`을 불렀다"는 아니다.**
    /// `PcmSource`가 `AsyncThrowingStream`이라 어떤 가짜 소스를 써도 Task 취소가 이터레이터에
    /// 닿아 루프를 끝내므로, 정지 요청 하나만 빼도 이 테스트는 통과한다(실제로 확인했다).
    /// 두 신호를 갈라 볼 수 있는 seam이 없어서 위 `RecordingControllerTests`도 같은 자리에서
    /// 멈춘다. 정지 요청을 함께 보내는 근거는 테스트가 아니라 실기기 캡처의 성질이다 —
    /// ``VoiceCheckRunner/stop()`` 주석 참고.
    func test화면을_떠나면_마이크를_놓는다() async {
        let (runner, source) = makeRunner()

        runner.start()
        await waitUntil("캡처가 열리지 않았다") { source.events.contains("open1") }

        runner.stop()

        await waitUntil("캡처가 반납되지 않았다: \(source.events.all)") { source.events.contains("release1") }
        if case .listening = runner.state {
            XCTFail("마이크를 놓았는데 아직 듣는 중이다")
        }
    }
}
