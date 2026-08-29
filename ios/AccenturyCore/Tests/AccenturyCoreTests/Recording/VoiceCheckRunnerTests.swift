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
}
