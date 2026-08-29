import AccenturyCore
import Combine
import Foundation

/// 목소리 점검 화면의 상태 보유자 (KAN-105 2단계). 안드로이드 `recording/VoiceCheckViewModel.kt`가
/// 있던 자리인데, 판정(``AccenturyCore/VoiceCheckController``)도 구동(``AccenturyCore/VoiceCheckRunner``)도
/// 이미 Core에 있어서 여기 남는 것은 상태를 `@Published`로 옮기는 일과 엔진을 만드는 일뿐이다.
///
/// 구동부가 Core에 있는 이유는 거기 담긴 규칙이 화면 결선이 아니라 **마이크 수명**이기
/// 때문이다 — 이전 캡처가 완전히 끝난 뒤에 새 캡처를 여는 직렬화가 그것이고, 시뮬레이터에서만
/// 확인할 수 있으면 아무도 그 순서를 지킬 수 없다.
///
/// ``RecordingModel``과 결정적으로 다른 점 하나: **PCM을 받지 않는다.** 점검은 사용자를 재는
/// 것이 아니라 마이크가 잘 열렸는지 확인하는 절차라, 저장하거나 서버로 보낼 이유가 전혀 없다
/// (FR-DP-02).
@MainActor
final class VoiceCheckModel: ObservableObject {

    @Published private(set) var state: VoiceCheckState

    private let runner: VoiceCheckRunner

    /// - Parameter engine: 캡처 엔진. 기본값이 녹음 화면과 **같은 소스**를 문다 — 디버그의 가짜
    ///   마이크가 점검 화면에도 그대로 흐르고, 점검이 잰 중심이 실제 문항에서 쓸 마이크와 같은
    ///   경로에서 나온다.
    init(engine: RecordingEngine = RecordingEngine(source: defaultPcmSource())) {
        let runner = VoiceCheckRunner(engine: engine)
        self.runner = runner
        state = runner.state
        // 진행 콜백은 캡처 쪽 스레드에서 온다 (``RecordingModel`` 주석과 같은 자리).
        runner.onStateChange = { [weak self] state in
            Task { @MainActor in self?.state = state }
        }
    }

    /// 듣기를 시작한다. 화면 진입마다 불려도 안전하다 — 이미 듣는 중이거나 판정이 끝난 뒤에는
    /// 아무 일도 하지 않는다.
    func start() { runner.start() }

    /// 시간이 다 됐거나 실패한 뒤의 [다시 시도]. 쌓인 것을 전부 버리고 처음부터 듣는다.
    func restart() { runner.restart() }

    /// 화면을 떠난다 — 마이크를 놓는다. 화면이 사라질 때(`onDisappear`)와 앱이 뒤로 갈 때
    /// (`scenePhase != .active`) 부른다. 이 모델은 화면보다 오래 살 수 있어서 여기서 안 끊으면
    /// 점검 화면이 사라진 뒤에도 마이크가 열려 있다.
    ///
    /// 이름이 `stop()`이 아니라 `leave()`인 이유는 ``RecordingModel/reset()``과 같다 — 부르는
    /// 자리가 "정지 버튼"이 아니라 "화면 이탈"이라, 그 뜻이 이름에 있어야 새 이탈 경로가 생겼을
    /// 때 여기를 부를 자리라는 게 읽힌다. Core 쪽 정지 요청·취소는 그 안에서 함께 나간다
    /// (``AccenturyCore/VoiceCheckRunner/stop()``).
    func leave() { runner.stop() }
}
