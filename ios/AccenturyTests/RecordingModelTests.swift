import AccenturyCore
import XCTest
@testable import Accentury

/// 앱 계층 테스트. 상태 머신 자체는 `AccenturyCoreTests/Recording/RecordingControllerTests`가
/// 덮으므로, 여기서 확인하는 것은 그 위에 얹힌 결선 하나다 — **화면을 떠나면 마이크를 놓고
/// 남은 음성을 버린다** (FR-DP-02, `RecordingModel.reset()`).
///
/// 그 규칙이 이 자리에서 검증돼야 하는 이유: 실제로 어기게 되는 경로가 화면 쪽이다. 오버레이가
/// 걷히거나(`onDisappear`) 앱이 뒤로 갈 때(`scenePhase`) 이 메서드를 부르는 것이 `TestFlowView`의
/// 일이고, 부르기만 하면 마이크가 정말 닫히는지는 여기서 못 박아야 그 결선이 믿을 만해진다.
@MainActor
final class RecordingModelTests: XCTestCase {

    /// 녹음 중에 떠나면 캡처가 닫히고 PCM이 남지 않는다.
    func testLeavingWhileRecordingReleasesTheMicrophoneAndDropsPcm() async {
        let source = SpyPcmSource()
        let model = RecordingModel(engine: RecordingEngine(source: source))

        model.start()
        await Self.waitForAudio(model)
        XCTAssertTrue(source.isOpened, "캡처가 열리지 않았다면 아래 단언에 뜻이 없다")

        model.reset()

        // 마이크를 놓는 것은 정지 요청과 Task 취소를 거쳐 소스의 정리 구간에서 일어난다 —
        // 동기로 끝나는 일이 아니라 조건으로 기다린다.
        await waitUntil("화면을 떠났는데 캡처가 열려 있다") { source.isReleased }
        XCTAssertNil(model.consumeRecording(), "떠난 뒤에도 음성 바이트가 남아 있다")
        XCTAssertEqual(.idle, model.uiState)
    }

    /// 녹음을 시작하지 않은 채 떠나도 안전하다 — 오버레이는 사용자가 버튼을 누르기 전에도 걷힌다.
    func testLeavingBeforeRecordingIsSafe() async {
        let source = SpyPcmSource()
        let model = RecordingModel(engine: RecordingEngine(source: source))

        model.reset()

        XCTAssertEqual(.idle, model.uiState)
        XCTAssertNil(model.consumeRecording())
        XCTAssertFalse(source.isOpened, "녹음을 시작하지도 않았는데 마이크가 열렸다")
    }

    /// 떠난 뒤 다시 들어오면 새 녹음이 열린다 — 되감기가 모델을 못 쓰게 만들면 안 된다.
    func testRecordingCanStartAgainAfterLeaving() async {
        let source = SpyPcmSource()
        let model = RecordingModel(engine: RecordingEngine(source: source))

        model.start()
        await Self.waitForAudio(model)
        model.reset()
        await waitUntil { source.isReleased }

        model.start()
        await waitUntil("되감은 뒤 녹음이 다시 시작되지 않았다") {
            if case .recording = model.uiState { return true }
            return false
        }
        model.reset()
    }

    /// 청크가 **실제로 흘렀는지**까지 기다린다.
    ///
    /// `.recording` 상태만 보고 넘어가면 안 된다 — 컨트롤러는 엔진을 걸기 전에 `elapsedMs = 0`
    /// 짜리 녹음 상태를 먼저 발행한다(화면이 버튼을 즉시 정지로 바꿔야 해서다). 그 순간에
    /// 정지를 부르면 캡처된 오디오가 0이라 검토가 아니라 실패로 떨어진다.
    static func waitForAudio(_ model: RecordingModel) async {
        await waitUntil("녹음 청크가 흐르지 않았다") {
            if case .recording(let recording) = model.uiState { return recording.elapsedMs > 0 }
            return false
        }
    }
}
