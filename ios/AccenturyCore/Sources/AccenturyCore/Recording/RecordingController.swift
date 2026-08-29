import Foundation

/// 녹음 화면의 순수 상태 머신 (Idle → Recording → Review). 안드로이드
/// `recording/RecordingViewModel.kt`의 이식본이고, 안드로이드가 `ViewModel`에 두던 것을
/// 여기서는 프레임워크 없는 클래스로 둔다 — `TestFlowController`·`SessionGateController`와
/// 같은 판단이다. SwiftUI 래퍼(`@MainActor` `ObservableObject`)는 §6b 몫이고, 그쪽은 이
/// 컨트롤러의 ``state``를 그대로 옮겨 담기만 한다.
///
/// **재생은 없다** (FR-AD-09). 검토 화면의 선택지는 [재녹음]과 [다음]뿐이고, [다음]은
/// 품질 판정이 통과일 때만 선다 (FR-AD-08 — ``RecordingUiState/Review/canProceed``).
///
/// **화면을 떠나면 마이크를 놓는다.** ``reset()``은 진행 중 Task를 취소하는 데 그치지 않고
/// ``RecordingEngine/requestStop()``까지 부른다 — 취소만으로는 소스 구현에 따라 캡처가 다음
/// 청크 경계까지 살아 있을 수 있고, 화면이 사라진 뒤에도 마이크가 열려 있는 것은
/// 사용자에게 설명할 수 없는 상태다. 같은 자리에서 PCM도 함께 버린다 (FR-DP-02).
///
/// **스레드**: 안드로이드는 `viewModelScope`(메인 디스패처)가 상태 갱신을 한 스레드로 모아
/// 준다. Swift에서 `RecordingEngine.record`는 nonisolated async라 진행 콜백이 어느 실행기에서
/// 올지 정해져 있지 않으므로, 상태를 락으로 감싸고 늦게 도착한 결과는 세대 번호로 거른다
/// (`RecordingEngine`의 `StopFlag`·`SessionSlot`과 같은 수법). ``onStateChange``는 락 밖에서
/// 불리고, 어느 스레드에서 오는지 모르므로 §6b 래퍼가 메인으로 넘겨 받는다.
public final class RecordingController: @unchecked Sendable {

    private let engine: RecordingEngine
    private let makeAttemptId: () -> String

    private let lock = NSLock()
    private var currentState: RecordingUiState = .idle
    private var lastPcm: [Int16]?
    private var recordingTask: Task<Void, Never>?
    private var stateChangeHandler: ((RecordingUiState) -> Void)?
    /// 지금 유효한 녹음의 번호. ``startRecording()``과 ``reset()``이 올린다 —
    /// 취소·재시작에 밀린 녹음의 뒤늦은 진행·결과는 이 번호가 안 맞아 버려진다.
    private var generation = 0

    public init(
        engine: RecordingEngine,
        makeAttemptId: @escaping () -> String = { "at_" + UUID().uuidString }
    ) {
        self.engine = engine
        self.makeAttemptId = makeAttemptId
    }

    /// 지금 화면이 그릴 값 한 장.
    public var state: RecordingUiState {
        lock.lock()
        defer { lock.unlock() }
        return currentState
    }

    /// 상태가 바뀔 때마다 불린다. 안드로이드 `StateFlow` 구독 자리이고,
    /// §6b의 SwiftUI 래퍼가 여기에 `objectWillChange`를 잇는다.
    public var onStateChange: ((RecordingUiState) -> Void)? {
        get {
            lock.lock()
            defer { lock.unlock() }
            return stateChangeHandler
        }
        set {
            lock.lock()
            stateChangeHandler = newValue
            lock.unlock()
        }
    }

    /// 녹음을 시작한다. 이미 녹음 중이면 아무 일도 하지 않는다.
    public func startRecording() {
        lock.lock()
        if case .recording = currentState {
            lock.unlock()
            return
        }
        lastPcm = nil
        generation += 1
        let generation = self.generation
        let attemptId = makeAttemptId()
        lock.unlock()

        publish(.recording(RecordingUiState.Recording(elapsedMs: 0, rms: 0)), generation: generation)

        let task = Task { [weak self, engine] in
            // 녹음 1회분 누적. 상한이 있는 목록이다 - 녹음이 10초에서 끊기고 프레임은 32ms
            // 간격이라 최대 313개 남짓이라, 링버퍼 없이 그냥 쌓아도 된다.
            var pitchFrames: [RecordingEngine.PitchFrame] = []
            let outcome = await engine.record { progress in
                pitchFrames.append(contentsOf: progress.pitchFrames)
                // 상태에 넣는 목록은 값 복사다 — 안드로이드가 `pitchFrames.toList()`로 막던
                // "이미 방출한 상태의 내용까지 다음 청크가 바꾼다"는 사고가 Swift 배열에서는
                // 대입 자체로 막힌다.
                self?.publish(
                    .recording(
                        RecordingUiState.Recording(
                            elapsedMs: progress.elapsedMs,
                            rms: progress.rms,
                            pitchFrames: pitchFrames
                        )
                    ),
                    generation: generation
                )
            }
            guard let self else { return }
            switch outcome {
            case let .success(pcm, durationMs, autoStopped):
                self.publish(
                    .review(
                        RecordingUiState.Review(
                            attemptId: attemptId,
                            durationMs: durationMs,
                            quality: AudioQuality.judge(pcm, durationMs: durationMs),
                            autoStopped: autoStopped,
                            pitchFrames: pitchFrames
                        )
                    ),
                    generation: generation,
                    pcm: pcm
                )
            case let .failure(reason):
                self.publish(.failed(reason: reason), generation: generation, pcm: nil)
            }
        }

        lock.lock()
        // 이미 다음 녹음이 시작됐거나 화면을 떠났다면 이 Task를 등록하지 않는다.
        if self.generation == generation { recordingTask = task }
        lock.unlock()
    }

    /// 사용자가 정지를 눌렀다. 엔진이 다음 청크 경계에서 멈추고 ``RecordingUiState/review(_:)``로 넘어간다.
    /// 여기서 PCM을 버리지 않는 것이 ``reset()``과의 차이다 — 이 경로의 끝은 검토 화면이다.
    public func stopRecording() {
        engine.requestStop()
    }

    /// 검토 화면의 [재녹음]. 새 attemptId로 처음부터 다시 녹음한다.
    public func retryRecording() {
        startRecording()
    }

    /// 방금 녹음한 PCM을 **한 번만** 꺼낸다. 두 번째 호출은 nil이다 —
    /// 업로드가 가져간 바이트를 화면이 계속 쥐고 있지 않게 하는 규칙이다 (FR-DP-02).
    public func consumeRecording() -> [Int16]? {
        lock.lock()
        defer { lock.unlock() }
        let pcm = lastPcm
        lastPcm = nil
        return pcm
    }

    /// 화면을 떠난다. 마이크를 놓고(정지 요청 + Task 취소) 남은 PCM을 버린 뒤 처음 상태로 돌아간다.
    public func reset() {
        // 취소보다 정지 요청이 먼저다. 취소만 하면 소스 구현에 따라 캡처 루프가 다음 청크까지
        // 살아 있을 수 있는데, 그 사이는 화면이 없는 채로 마이크가 열려 있는 구간이다.
        engine.requestStop()

        lock.lock()
        let task = recordingTask
        recordingTask = nil
        lastPcm = nil
        generation += 1
        currentState = .idle
        let handler = stateChangeHandler
        lock.unlock()

        task?.cancel()
        handler?(.idle)
    }

    /// 이 녹음이 아직 유효할 때만 상태를 바꾼다. 콜백은 락 밖에서 부른다 —
    /// 구독자가 그 안에서 컨트롤러를 다시 만지면 재진입으로 잠기기 때문이다.
    private func publish(_ state: RecordingUiState, generation: Int, pcm: [Int16]?? = nil) {
        lock.lock()
        guard self.generation == generation else {
            lock.unlock()
            return
        }
        currentState = state
        if let pcm { lastPcm = pcm }
        let handler = stateChangeHandler
        lock.unlock()
        handler?(state)
    }
}
