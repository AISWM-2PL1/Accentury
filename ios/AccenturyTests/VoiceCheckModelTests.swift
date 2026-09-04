import AccenturyCore
import XCTest
@testable import Accentury

/// 앱 계층 테스트. 판정(`VoiceCheckController`)과 마이크 수명 규칙(`VoiceCheckRunner`)은
/// `AccenturyCoreTests/Recording`이 덮으므로, 여기서 확인하는 것은 그 위에 얹힌 결선 하나다 —
/// **화면을 떠나면 마이크를 놓는다.**
///
/// 그 규칙이 이 자리에서 검증돼야 하는 이유는 `RecordingModelTests`와 같다: 실제로 어기게 되는
/// 경로가 화면 쪽이다. 점검 화면이 걷힐 때(`onDisappear`)와 앱이 뒤로 갈 때(`scenePhase`)
/// ``VoiceCheckModel/leave()``를 부르는 것이 `VoiceCheckScreen`의 일이고, 부르기만 하면 마이크가
/// 정말 닫히는지는 여기서 못 박아야 그 결선이 믿을 만해진다.
///
/// 점검에는 꺼낼 PCM이 없다 — 사용자를 재는 것이 아니라 마이크가 잘 열렸는지 확인하는 절차라
/// 구동부가 성공 결과의 `pcm`을 바인딩조차 하지 않는다 (FR-DP-02). 그래서 녹음 쪽 테스트가
/// `consumeRecording()`을 묻는 자리에서 여기는 "듣기 상태를 벗어났는가"를 본다.
@MainActor
final class VoiceCheckModelTests: XCTestCase {

    /// 화면이 걷히는 경로(`onDisappear`) — 듣는 중에 떠나면 캡처가 닫힌다.
    func testLeavingWhileListeningReleasesTheMicrophone() async {
        let source = SpyPcmSource()
        let model = VoiceCheckModel(engine: RecordingEngine(source: source))

        model.start()
        await Self.waitForListening(model, source: source)

        model.leave()

        await waitUntil("화면을 떠났는데 캡처가 열려 있다") { source.isReleased }
        if case .listening = model.state {
            XCTFail("마이크를 놓았는데 아직 듣는 중이다")
        }
    }

    /// 앱이 뒤로 가는 경로(`scenePhase != .active`) — 화면은 그대로인데 마이크만 놓는다.
    ///
    /// 같은 메서드를 부르지만 뒤가 다르다: 돌아오면 `start()`가 다시 불린다. 그때 새 캡처가
    /// 열려야 하고, 그러려면 앞 캡처가 확실히 닫혀 있어야 한다 — 겹쳐 열리면 실기기에서
    /// "마이크 점유 중"으로 초기화가 실패한다.
    func testLeavingForBackgroundReleasesTheMicrophoneAndComingBackOpensANewCapture() async {
        let source = SpyPcmSource()
        let model = VoiceCheckModel(engine: RecordingEngine(source: source))

        model.start()
        await Self.waitForListening(model, source: source)

        // scenePhase가 .active를 벗어났다.
        model.leave()
        await waitUntil("뒤로 갔는데 캡처가 열려 있다") { source.isReleased }

        // 돌아왔다. 판정이 아직 끝나지 않았으면 다시 듣기 시작한다.
        model.restart()
        await waitUntil("돌아왔는데 새 캡처가 열리지 않았다") {
            if case .listening(let listening) = model.state { return !listening.frames.isEmpty }
            return false
        }
    }

    /// 듣기를 시작하지 않은 채 떠나도 안전하다 — 점검 화면은 마이크가 열리기 전에도 걷힌다.
    func testLeavingBeforeListeningIsSafe() async {
        let source = SpyPcmSource()
        let model = VoiceCheckModel(engine: RecordingEngine(source: source))

        model.leave()

        XCTAssertFalse(source.isOpened, "듣기를 시작하지도 않았는데 마이크가 열렸다")
        guard case .listening = model.state else {
            return XCTFail("아직 아무것도 안 했는데 듣기 상태가 아니다: \(model.state)")
        }
    }

    /// 청크가 **실제로 흘렀는지**까지 기다린다 — 캡처가 열리기 전에 떠나면 아무것도 재지 못한다.
    private static func waitForListening(_ model: VoiceCheckModel, source: SpyPcmSource) async {
        await waitUntil("점검 청크가 흐르지 않았다") {
            if case .listening(let listening) = model.state { return !listening.frames.isEmpty }
            return false
        }
        XCTAssertTrue(source.isOpened, "캡처가 열리지 않았다면 아래 단언에 뜻이 없다")
    }
}
