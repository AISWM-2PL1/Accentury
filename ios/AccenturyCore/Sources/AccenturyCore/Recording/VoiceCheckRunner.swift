import Foundation

/// 목소리 점검 화면의 구동부 (KAN-105 2단계). 엔진을 돌리고 ``VoiceCheckController``에 먹인다.
/// 안드로이드 `recording/VoiceCheckViewModel.kt`에서 **판정이 아닌 결선 규칙**만 옮긴 것이다.
///
/// 안드로이드가 `ViewModel`인 자리인데도 순수 계층에 있는 이유는 여기 담긴 규칙이 화면 결선이
/// 아니라 **마이크 수명**이기 때문이다 (아래 ``listen()`` 주석). 시뮬레이터에서만 확인할 수 있으면
/// 회전 한 번에 깨지는 순서를 아무도 못 지킨다. SwiftUI 래퍼는 §6b 몫이고 이 타입을 그대로 쓴다.
///
/// ``RecordingController``와 같은 루프지만 결정적인 차이가 하나 있다: **PCM을 받지 않는다.**
/// 점검은 사용자를 재는 것이 아니라 마이크가 잘 열렸는지 확인하는 절차라, 저장하거나 서버로
/// 보낼 이유가 전혀 없다 (FR-DP-02).
///
/// 스레드 규약은 ``RecordingController``와 같다 — 모든 메서드를 한 스레드(앱에서는 메인)에서 부른다.
public final class VoiceCheckRunner: @unchecked Sendable {

    private let engine: RecordingEngine

    private let lock = NSLock()
    /// 판정기 자체는 스레드 안전하지 않다. 이 락 안에서만 만진다 —
    /// 진행 콜백이 어느 실행기에서 올지 정해져 있지 않기 때문이다(``RecordingController`` 주석 참고).
    private let controller = VoiceCheckController()
    private var currentState: VoiceCheckState
    private var stateChangeHandler: ((VoiceCheckState) -> Void)?
    private var listeningTask: Task<Void, Never>?
    private var listeningActive = false
    /// 지금 유효한 듣기의 번호. ``listen()``이 올린다 — 취소·재시작에 밀린 캡처의 뒤늦은
    /// 진행·결과는 이 번호가 안 맞아 버려진다. 코틀린에서는 취소된 코루틴이 중단점에서 그냥
    /// 죽어 `when (outcome)`까지 못 갔지만, Swift의 `record`는 취소를 Failure로 접어 돌려주므로
    /// 그 결과가 새 듣기의 상태를 덮지 않게 여기서 막아야 한다.
    private var generation = 0

    public init(engine: RecordingEngine) {
        self.engine = engine
        currentState = controller.state
    }

    public var state: VoiceCheckState {
        lock.lock()
        defer { lock.unlock() }
        return currentState
    }

    /// 상태가 바뀔 때마다 불린다. 안드로이드 `StateFlow` 구독 자리다.
    public var onStateChange: ((VoiceCheckState) -> Void)? {
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

    /// 듣기를 시작한다. 화면 진입마다 불려도 안전하다 — 이미 듣는 중이거나 판정이 끝난 뒤에는
    /// 아무 일도 하지 않는다. 끝난 판정을 되돌리는 건 ``restart()`` 하나뿐이다.
    public func start() {
        lock.lock()
        // 코틀린 `listeningJob?.isActive == true` 자리. 취소가 시작된 Task는 이미 활성이 아니다 —
        // 화면이 빠지며 stop()한 직후 새 화면이 start()하는 경로가 여기로 온다.
        let busy = listeningActive && !(listeningTask?.isCancelled ?? true)
        let listening: Bool
        if case .listening = controller.state { listening = true } else { listening = false }
        lock.unlock()

        if busy || !listening { return }
        listen()
    }

    /// 시간이 다 됐거나 실패한 뒤의 [다시 시도]. 쌓인 것을 전부 버리고 처음부터 듣는다.
    public func restart() {
        listen()
    }

    /// 마이크를 놓는다. 화면이 사라질 때 부른다 — 이 구동부는 화면보다 오래 살 수 있어서
    /// (회전·상태 복원) 여기서 안 끊으면 점검 화면이 사라진 뒤에도 마이크가 열려 있다.
    ///
    /// ## 정지 요청과 취소를 **둘 다** 한다
    ///
    /// 처음에는 취소만 했다. 취소는 소스의 정리 구간까지 즉시 내려가고 정지 요청은 다음 청크
    /// 경계까지 기다리니, 빠른 쪽 하나면 충분해 보였다. 그건 **소스가 취소를 관측한다는 가정**에
    /// 기대는 것이고, 그 가정이 참인 것은 우리가 만든 가짜 소스뿐이다 — 실제 캡처
    /// (`AudioRecorder`)는 AVAudioEngine의 입력 탭 콜백에서 버퍼를 받아 넘기고, 그 콜백은
    /// 오디오 스레드가 부른다. 취소 깃발을 읽는 지점(suspension point)에 언제 닿을지는 다음
    /// 버퍼가 언제 오느냐에 달렸고, 그때까지 마이크는 열려 있다.
    ///
    /// 그래서 순서가 이렇다: **정지 요청이 먼저, 취소가 나중.** 정지 요청은 엔진이 자기 루프를
    /// 다음 경계에서 끊게 하고(그 경로는 소스의 협조가 필요 없다), 취소는 협조하는 소스에서
    /// 그보다 빨리 끝낸다. 둘 중 먼저 닿는 쪽이 이기고, 어느 쪽도 안 닿는 경우가 없어진다.
    /// ``RecordingController/reset()``이 같은 순서를 쓰는 이유도 같다.
    ///
    /// 이 경로가 PCM을 남기지 않는 것은 그대로다 — 정지 요청으로 끝난 녹음은
    /// ``AccenturyCore/RecordingEngine/Outcome/success(pcm:durationMs:autoStopped:)``로 오지만
    /// 아래 ``listen()``이 그 케이스에서 `pcm`을 **바인딩하지 않는다** (FR-DP-02).
    ///
    /// 취소만 하고 Task 참조는 지우지 않는다 — 다음 ``listen()``이 이 Task의 **완료**를 기다려야 한다.
    public func stop() {
        engine.requestStop()

        lock.lock()
        let task = listeningTask
        lock.unlock()
        task?.cancel()
    }

    private func listen() {
        /*
         * 중간에 끊긴 듣기의 프레임은 물려받지 않는다. 엔진이 새로 서면 timestampMs가 0부터
         * 다시 시작하는데, 남아 있던 프레임과 이어 붙이면 시간축이 뒤로 감겨 곡선이 뒤엉킨다.
         */
        lock.lock()
        controller.restart()
        let restarted = controller.state
        currentState = restarted
        let previous = listeningTask
        listeningActive = true
        generation += 1
        let generation = self.generation
        let handler = stateChangeHandler
        lock.unlock()
        handler?(restarted)

        /*
         * 이전 캡처가 **완전히 끝난 뒤에** 새 캡처를 연다. 취소가 돌아왔다고 마이크가 풀린
         * 것이 아니다 — AVAudioEngine 정지·세션 비활성화는 소스의 정리 구간에서, 그것도 다른
         * 스레드에서 일어나므로 `cancel()` 반환 시점엔 아직 마이크를 쥐고 있을 수 있다. 화면이
         * 즉시 다시 서서 stop() 직후 start()가 불리면, 새 입력 탭이 아직 살아 있는 이전 것과
         * 겹쳐 초기화에 실패한다. 완료를 기다려(cancelAndJoin) 직렬화한다.
         */
        let task = Task { [weak self, engine] in
            if let previous {
                previous.cancel()
                _ = await previous.value
            }
            guard let self else { return }
            let outcome = await engine.record { progress in
                // true면 준비가 끝났다는 뜻 - 더 들어도 판정이 안 바뀌므로 마이크를 놓는다.
                self.apply(generation: generation) { controller in
                    if controller.onProgress(rms: progress.rms, newFrames: progress.pitchFrames) {
                        engine.requestStop()
                    }
                }
            }
            switch outcome {
            /*
             * 성공 케이스의 pcm은 **바인딩하지 않는다**. 점검 오디오는 보관도 전송도 하지 않으므로
             * (FR-DP-02) 여기서 참조를 만들지 않는 것이 그 규칙의 실제 이행이다 —
             * 지역 변수에 한 번 담는 순간 "어디까지 살아 있는가"를 따져야 할 값이 생긴다.
             */
            case .success: self.apply(generation: generation) { $0.onStopped() }
            case let .failure(reason): self.apply(generation: generation) { $0.onFailed(reason) }
            }
            self.markIdle(generation: generation)
        }

        lock.lock()
        listeningTask = task
        lock.unlock()
    }

    /// 듣기가 끝났다는 표시. 락을 만지는 일을 동기 메서드에 가둬 두는 이유는
    /// `NSLock.lock()`이 async 본문에서 직접 불리면 경고(Swift 6에서는 오류)이기 때문이다 —
    /// `RecordingEngine`의 `SessionSlot`·`StopFlag`와 같은 자리다.
    private func markIdle(generation: Int) {
        lock.lock()
        if self.generation == generation { listeningActive = false }
        lock.unlock()
    }

    /// 판정기를 한 번 만지고 그 결과를 상태로 옮긴다. 콜백은 락 밖에서 부른다.
    /// 밀려난 캡처(세대 번호 불일치)의 진행·결과는 여기서 버려진다.
    private func apply(generation: Int, _ body: (VoiceCheckController) -> Void) {
        lock.lock()
        guard self.generation == generation else {
            lock.unlock()
            return
        }
        body(controller)
        let next = controller.state
        currentState = next
        let handler = stateChangeHandler
        lock.unlock()
        handler?(next)
    }
}
