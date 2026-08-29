import AccenturyCore
import Combine
import Foundation

/// 녹음 화면의 상태 보유자. 안드로이드 `recording/RecordingViewModel.kt`가 있던 자리인데,
/// 판정은 이미 Core의 순수 상태 머신(``AccenturyCore/RecordingController``)이 전부 갖고 있어
/// 여기 남는 것은 셋뿐이다 — 상태를 SwiftUI가 볼 수 있는 `@Published`로 옮기고, 엔진을
/// 만들고, 화면이 사라질 때 마이크를 놓는다. `PermissionGateModel`이
/// `MicPermissionController`에 하는 일과 같은 구조다.
///
/// ## 화면보다 오래 사는 이유
///
/// 안드로이드가 `ViewModel`을 쓴 이유는 회전이었다 — 화면이 재생성돼도 녹음과 PCM이 살아남아야
/// 한다. iOS는 세로 고정이라 회전은 없지만 같은 자리가 여전히 필요하다: SwiftUI는 뷰 값을
/// 언제든 다시 만들고, 오버레이가 제출 페이즈로 넘어가는 동안에도 방금 그린 곡선이 남아 있어야
/// 한다(KAN-146). `TestFlowView`가 `@StateObject`로 들고 있어 문항이 바뀌어도 인스턴스가 같다.
///
/// ## 화면을 떠나면 마이크를 놓는다
///
/// ``reset()``이 그 자리다. 안드로이드의 `viewModel.reset()`과 달리 취소만 하지 않고
/// ``AccenturyCore/RecordingEngine/requestStop()``까지 부르는데, 그 규칙은 Core 컨트롤러가
/// 들고 있다 — 취소만으로는 소스 구현에 따라 캡처가 다음 청크 경계까지 살아 있을 수 있고,
/// 화면이 사라진 뒤에도 마이크가 열려 있는 것은 사용자에게 설명할 수 없는 상태다.
/// 부르는 자리는 셋이다: 오버레이가 걷힐 때(`onDisappear`), 앱이 뒤로 갈 때
/// (`scenePhase != .active`), 그리고 다른 문항으로 넘어갈 때(`continuesFrom`가 아니라고 할 때).
@MainActor
final class RecordingModel: ObservableObject {

    /// 화면이 그리는 값 한 장. Core 컨트롤러의 ``AccenturyCore/RecordingController/state``를
    /// 그대로 옮겨 담는다.
    @Published private(set) var uiState: RecordingUiState = .idle

    private let engine: RecordingEngine
    private let controller: RecordingController

    /// - Parameter engine: 캡처 엔진. 기본값이 실제 마이크(또는 디버그의 가짜 마이크)이고,
    ///   테스트는 가짜 소스를 문 엔진을 끼워 "떠날 때 마이크를 놓는가"를 단언한다.
    init(engine: RecordingEngine = RecordingEngine(source: defaultPcmSource())) {
        self.engine = engine
        controller = RecordingController(engine: engine)
        uiState = controller.state
        /*
         * 진행 콜백은 캡처 쪽 스레드에서 온다(Core `RecordingController` 주석) — `@Published`는
         * 메인에서만 건드릴 수 있으므로 메인 액터로 넘겨 받는다.
         *
         * `Task`는 같은 우선순위에서 등록 순서대로 실행되고, 이 콜백은 엔진의 수집 루프
         * 하나에서 순차로 오므로 상태 순서가 유지된다. 설령 한 프레임이 뒤집혀도 상태가
         * 누적값(경과·프레임 전체)이라 다음 청크가 곧바로 덮는다.
         */
        controller.onStateChange = { [weak self] state in
            Task { @MainActor in self?.uiState = state }
        }
    }

    /// 녹음을 시작한다. 이미 녹음 중이면 아무 일도 하지 않는다.
    func start() { controller.startRecording() }

    /// 사용자가 정지를 눌렀다. 엔진이 다음 청크 경계에서 멈추고 검토 화면으로 넘어간다.
    func stop() { controller.stopRecording() }

    /// 검토 화면의 [재녹음]. 새 attemptId로 처음부터 다시 녹음한다.
    func retry() { controller.retryRecording() }

    /// 방금 녹음한 PCM을 **한 번만** 꺼낸다. 두 번째 호출은 nil이다 — 업로드가 가져간 바이트를
    /// 화면이 계속 쥐고 있지 않게 하는 규칙이다 (FR-DP-02).
    func consumeRecording() -> [Int16]? { controller.consumeRecording() }

    /// 화면을 떠난다. 마이크를 놓고(정지 요청 + Task 취소) 남은 PCM을 버린 뒤 처음 상태로
    /// 돌아간다. 여러 번 불려도 안전하다.
    func reset() { controller.reset() }
}
