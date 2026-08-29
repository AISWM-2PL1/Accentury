import XCTest
@testable import AccenturyCore

/// 8초 경고의 경계 (KAN-161 4단계). 안드로이드 `recording/RecordingCountdownTest.kt`의 이식본.
///
/// 화면에서는 "경고가 안 떴다"나 "3초 남았다고 나온다"로만 드러나는데, 그 증상을 만드는 것은
/// 부등호 하나와 반올림 방향 하나다 — 사람이 확인하려면 매번 스톱워치를 들고 녹음해야 하므로
/// 경계를 여기서 고정한다.
///
/// 웹 `WebVoiceRecorder.tsx`·안드로이드가 같은 규칙을 쓴다. 값이 갈리면 한 테스트 안에서
/// 번갈아 나오는 두 화면이 서로 다른 순간에 경고를 띄운다.
final class RecordingCountdownTests: XCTestCase {

    private let max = RecordingEngine.maxDurationMs // 10_000

    func test8초에_닿는_순간부터_경고다_그_직전은_아니다() {
        XCTAssertFalse(isCountdownWarning(elapsedMs: 7_999, maxDurationMs: max))
        XCTAssertTrue(isCountdownWarning(elapsedMs: 8_000, maxDurationMs: max))
        XCTAssertTrue(isCountdownWarning(elapsedMs: 8_001, maxDurationMs: max))
    }

    func test녹음_초반은_경고_구간이_아니다() {
        XCTAssertFalse(isCountdownWarning(elapsedMs: 0, maxDurationMs: max))
        XCTAssertFalse(isCountdownWarning(elapsedMs: 4_000, maxDurationMs: max))
    }

    func test상한에_닿거나_넘겨도_경고다_남은_시간은_음수로_내려가지_않는다() {
        XCTAssertTrue(isCountdownWarning(elapsedMs: 10_000, maxDurationMs: max))
        XCTAssertTrue(isCountdownWarning(elapsedMs: 12_000, maxDurationMs: max))
        XCTAssertEqual(0, remainingMs(elapsedMs: 12_000, maxDurationMs: max))
    }

    func test상한이_달라지면_경고도_따라_움직인다_비율이_아니라_남은_시간이_기준이다() {
        // 상한 5초짜리 문항: 3초에 경고가 시작한다. 비율(80%)이었다면 4초였다
        XCTAssertFalse(isCountdownWarning(elapsedMs: 2_999, maxDurationMs: 5_000))
        XCTAssertTrue(isCountdownWarning(elapsedMs: 3_000, maxDurationMs: 5_000))
    }

    func test캡슐의_초는_올림이다_남은_시간을_실제보다_짧게_말하지_않는다() {
        XCTAssertEqual(2, remainingSeconds(elapsedMs: 8_000, maxDurationMs: max))
        // 1.999초 남았는데 1초라고 적으면 사용자가 1초 뒤를 끝으로 잡는다
        XCTAssertEqual(2, remainingSeconds(elapsedMs: 8_001, maxDurationMs: max))
        XCTAssertEqual(1, remainingSeconds(elapsedMs: 9_000, maxDurationMs: max))
        XCTAssertEqual(1, remainingSeconds(elapsedMs: 9_999, maxDurationMs: max))
        // 상한에 정확히 닿으면 0이다. 그 순간 엔진이 스스로 멈춘다
        XCTAssertEqual(0, remainingSeconds(elapsedMs: 10_000, maxDurationMs: max))
    }

    func test상태의_countdownActive가_같은_판정을_쓴다() {
        XCTAssertFalse(RecordingUiState.Recording(elapsedMs: 7_999, rms: 0).countdownActive)
        XCTAssertTrue(RecordingUiState.Recording(elapsedMs: 8_000, rms: 0).countdownActive)
        XCTAssertEqual(2, RecordingUiState.Recording(elapsedMs: 8_000, rms: 0).remainingSeconds)
    }

    func test경과_표기는_시계꼴이고_버림이다() {
        XCTAssertEqual("00:00", formatElapsed(0))
        // 0.9초에서 00:01이 뜨면 아직 1초가 안 됐는데 1초로 보인다 (품질 게이트가 1초 미만을 거절)
        XCTAssertEqual("00:00", formatElapsed(900))
        XCTAssertEqual("00:04", formatElapsed(4_000))
        XCTAssertEqual("00:04", formatElapsed(4_999))
        XCTAssertEqual("00:10", formatElapsed(10_000))
        XCTAssertEqual("00:04", RecordingUiState.Recording(elapsedMs: 4_999, rms: 0).elapsedLabel)
    }
}
