import Foundation

/// 업로드 한 건의 상태를 attemptId와 함께 들고 다니는 값. 상태 바가 읽는 단위다.
///
/// 안드로이드는 `Map<String, UploadState>`를 `LinkedHashMap`으로 두어 **넣은 순서**를 그대로
/// 화면에 썼다. Swift `Dictionary`에는 순서가 없어서, 순서를 값으로 승격시킨 것이 이 타입이다.
public struct UploadEntry: Equatable, Sendable {
    public let attemptId: String
    public let state: UploadState

    public init(attemptId: String, state: UploadState) {
        self.attemptId = attemptId
        self.state = state
    }
}

/// 업로드 한 건의 수명(등록 - 전송 - 실패 - 재시도 - 폐기)을 쥐는 자리.
/// 안드로이드 `upload/UploadManager.kt`의 1:1 이식본이다.
///
/// 재시도 횟수에 상한을 두지 않는다 (KAN-147, 2026-08-25 B안). ``UploadResult/transportError(failure:reason:)``은
/// 응답이 오지 않았다는 뜻이지 녹음에 문제가 있다는 뜻이 아니고(멱등 키 덕에 서버가 이미 받았을
/// 수도 있다), 잠깐 끊긴 사용자에게서 녹음을 빼앗는 것은 되돌릴 수 없는 손실이다. 그래서 전송
/// 실패에는 [재시도]를 계속 남긴다.
///
/// 자동 재녹음으로 넘어가는 것은 서버가 녹음 자체를 거절했다고 답한 코드(``rerecordCodes``)뿐이다.
/// 그 신호가 `UploadState.Failure.rerecord`고, 그 외의 재시도 불가 거절(세션 만료·재녹음 횟수
/// 초과 등)은 서버가 준 문구를 단 실패 행으로 화면에 그대로 남는다 - 사용자가 읽어야 할 안내를
/// 자동 전환이 지워버리지 않게 한다.
///
/// **락 하나 대신 actor다.** 안드로이드는 상태(uploads)·원본(originals)·진행 중 코루틴(jobs)을
/// `synchronized(lock)` 하나로 선형화한다 — 셋을 따로 원자적으로 다루면 "조건 확인 → 등록" 사이에
/// 폐기가 끼어들어 폐기 이후에 시작된 업로드가 원본 바이트를 영구히 남기기 때문이다(FR-DP-02 위반).
/// Swift에서는 그 선형화가 actor의 기본 성질이라 락을 손으로 들지 않는다. 대신 지켜야 할 규칙이
/// 하나 생긴다 — **``enqueue(_:label:)``·``retry(_:)``·``discard(_:)``의 본문에는 `await`가 없다.**
/// 중간에 await가 끼면 그 지점에서 다른 호출이 끼어들어 락을 쪼갠 것과 같은 창이 열린다.
///
/// 그 규칙 덕에 안드로이드의 `CoroutineStart.LAZY`도 필요 없다. 코틀린은 코루틴 본문이 호출
/// 스레드에서 즉시 인라인으로 돌 수 있어 `jobs`에 등록하기 전에 전송이 시작될 수 있었지만,
/// 여기서 만드는 `Task`의 본문은 actor 위에서 돌아 이 동기 구간이 끝난 뒤에야 시작한다.
public actor UploadManager {

    /// 백엔드 ErrorCode 중 녹음을 새로 해야 풀리는 것들 (KAN-147, 2026-08-25 B안).
    /// 같은 바이트를 다시 보내면 서버가 같은 답을 할 뿐이라 재전송이 아니라 재녹음이 복구 경로다.
    ///
    /// AUDIO_TOO_QUIET은 서버가 `retryable = true`로 주지만 여기서는 재녹음이 이긴다 -
    /// 재전송해도 같은 바이트가 같은 판정을 받는다.
    ///
    /// AUDIO_FORMAT_UNSUPPORTED는 넣지 않는다. 포맷은 클라이언트가 만드는 것이라 사용자가
    /// 다시 녹음해도 같은 포맷이 나간다 - 재녹음을 시켜도 벗어날 수 없는 클라이언트 버그다.
    public static let rerecordCodes: Set<String> = ["AUDIO_TOO_LONG", "AUDIO_TOO_LARGE", "AUDIO_TOO_QUIET"]

    /// `UploadState`는 itemId를 들고 있지 않아 실패 표시에 쓸 문항 라벨을 모르는 경우의 대체 문구.
    public static let defaultLabel = "문항"

    private let client: UploadClient
    private let sessionId: String
    private let sessionToken: String

    private var states: [String: UploadState] = [:]
    /// 넣은 순서. 안드로이드 `LinkedHashMap`의 자리이고, 재시도는 순서를 바꾸지 않는다.
    private var order: [String] = []
    // 재시도는 같은 멱등 키로 같은 바이트를 다시 보내야 하므로 원본을 들고 있는다.
    private var originals: [String: UploadRequest] = [:]
    // 폐기 시 진행 중 전송을 실제로 끊으려면 Task를 잡고 있어야 한다.
    private var tasks: [String: Task<Void, Never>] = [:]
    /// 안드로이드의 `jobs[attemptId] === job` 동일성 비교 자리. `Task`에는 그 비교가 없어
    /// 등록마다 발급하는 세대 번호로 대신한다 — 폐기·교체된 시도의 뒤늦은 결과를 여기서 버린다.
    private var generations: [String: Int] = [:]
    private var nextGeneration = 0
    /// 실패 표시에 쓸 문항 라벨. 안드로이드는 `UploadViewModel`이 들고 있지만, 그쪽 대응물인
    /// §6b의 `@MainActor` 래퍼는 화면 결선만 하는 얇은 층이라 이 자리로 내렸다.
    private var labels: [String: String] = [:]

    private var observers: [UUID: AsyncStream<[UploadEntry]>.Continuation] = [:]

    /// 테스트가 **임계 구역 진입 순서**를 관측하는 자리. 기본 nil이라 앱 동작에는 전혀 관여하지
    /// 않고, `internal`이라 패키지 밖(앱 타깃)에서는 보이지도 않는다.
    ///
    /// 여기 있는 이유: 경합 테스트가 "두 인터리빙이 실제로 일어났는가"를 증명하려면 actor에
    /// **누가 먼저 들어갔는지**를 알아야 한다. Task를 깨운 순서는 그 답이 아니다 — continuation
    /// 재개 순서와 실행기 진입 순서는 다르다. 그 간극을 메우는 관측점이다.
    private var criticalSectionObserver: (@Sendable (String) -> Void)?

    /// ``criticalSectionObserver``를 건다. 테스트 전용이고, actor 상태라 밖에서 대입할 수 없어 메서드로 둔다.
    func observeCriticalSections(_ observer: (@Sendable (String) -> Void)?) {
        criticalSectionObserver = observer
    }

    public init(client: UploadClient, sessionId: String, sessionToken: String) {
        self.client = client
        self.sessionId = sessionId
        self.sessionToken = sessionToken
    }

    // MARK: - 읽기

    /// 지금 상태를 넣은 순서대로. 상태 바(``summarize(_:)``)가 그대로 받는 모양이다.
    public var entries: [UploadEntry] {
        order.compactMap { id in states[id].map { UploadEntry(attemptId: id, state: $0) } }
    }

    /// 안드로이드 `uploads.value` 자리. 순서가 필요 없는 조회용이다.
    public var uploads: [String: UploadState] { states }

    public func state(of attemptId: String) -> UploadState? { states[attemptId] }

    public func labelOf(_ attemptId: String) -> String { labels[attemptId] ?? Self.defaultLabel }

    /// 안드로이드 `StateFlow`의 자리. 구독하는 순간 현재 값을 한 번 흘리고, 이후 변화를 잇는다.
    public func stateChanges() -> AsyncStream<[UploadEntry]> {
        let id = UUID()
        var continuation: AsyncStream<[UploadEntry]>.Continuation!
        let stream = AsyncStream<[UploadEntry]>(bufferingPolicy: .bufferingNewest(32)) { continuation = $0 }
        continuation.onTermination = { [weak self] _ in
            Task { await self?.removeObserver(id) }
        }
        observers[id] = continuation
        continuation.yield(entries)
        return stream
    }

    private func removeObserver(_ id: UUID) {
        observers.removeValue(forKey: id)
    }

    // MARK: - 변이 (본문에 await를 두지 않는다)

    /**
     처음 보는 멱등 키만 받는다. 이미 아는 키는 상태와 무관하게 무시해,
     하나의 멱등 키에 서로 다른 payload가 붙는 일을 원천 차단한다.
     실패한 업로드의 재전송 경로는 ``retry(_:)`` 하나뿐이다.

     - Parameter label: 실패 표시에 쓸 문항 라벨. 안드로이드 `UploadViewModel.enqueue(request, label)`의 자리다.
     */
    public func enqueue(_ request: UploadRequest, label: String? = nil) {
        criticalSectionObserver?("enqueue")
        if states[request.attemptId] != nil { return }
        // 안드로이드가 `wavBytes.copyOf()`로 뜨던 스냅샷은 `Data`가 값 타입이라 대입이 곧 스냅샷이다.
        originals[request.attemptId] = request
        if let label { labels[request.attemptId] = label }
        register(request)
    }

    /// 실패한 전송을 같은 멱등 키와 같은 바이트로 다시 보낸다. 횟수 제한은 없다 (KAN-147, B안).
    ///
    /// 재시도 불가로 내려온 실패는 그대로 무시한다 - 판정을 여기가 아니라 상태를 만드는
    /// 자리(``register(_:)``)에 둔 이유는 화면이 [재시도] 버튼을 그릴지 말지를 같은 값 하나로 정하기
    /// 때문이다. 버튼은 보이는데 눌러도 무시되는 구간이 없다.
    public func retry(_ attemptId: String) {
        guard let request = originals[attemptId] else { return }
        guard case let .failed(failure) = states[attemptId], failure.retryable else { return }
        register(request)
    }

    /// 이 시도의 음성 바이트를 확정적으로 폐기한다 (FR-DP-02).
    /// 진행 중이면 전송을 끊고(Task 취소가 `URLSession`까지 내려간다),
    /// 원본 바이트와 상태 항목을 함께 지운다.
    ///
    /// 폐기 후 같은 attemptId로 ``enqueue(_:label:)``하면 새 시도로 다시 받는다.
    /// 폐기는 시도 자체를 버리는 것이므로 멱등 키를 다시 열어주는 게 맞다.
    ///
    /// ⚠️ 단, 폐기 직전 전송이 이미 서버에 도달했을 수 있다. 그 경우 같은 키의 재요청에는
    /// 서버 멱등 규칙(명세서 §5.2)이 기존 작업을 반환하므로, **폐기된 키를 새 녹음(다른
    /// 바이트)에 재사용하면 새 녹음이 옛 analysisJobId에 조용히 묶인다.** 새 시도는 항상
    /// 새 attemptId를 발급할 것.
    ///
    /// 호출처는 둘 다 KAN-147에서 생겼다: 재녹음 전환(rerecord)이 확정된 업로드(그 문항은 녹음
    /// 화면이 다시 열린다)와, 같은 문항의 새 녹음이 등록되면서 밀려난(supersede) 앞 시도다.
    /// 둘 다 결과가 나올 일이 없어진 시도라 바이트를 들고 있을 이유가 없다.
    public func discard(_ attemptId: String) {
        // tasks에서 먼저 떼어낸 뒤 취소한다. 세대 번호도 함께 지워, 취소를 삼킨 옛 전송이
        // 결과를 들고 돌아와도 ``publish(_:generation:state:)``에서 버려진다.
        let task = tasks.removeValue(forKey: attemptId)
        generations.removeValue(forKey: attemptId)
        originals.removeValue(forKey: attemptId)
        labels.removeValue(forKey: attemptId)
        states.removeValue(forKey: attemptId)
        order.removeAll { $0 == attemptId }
        task?.cancel()
        notify()
    }

    /// 화면이 완전히 끝날 때 남아 있는 음성 바이트를 전부 폐기한다 (FR-DP-02).
    /// ``discard(_:)``와 같은 정리를 전 키에 적용한다.
    public func clearAll() {
        criticalSectionObserver?("clearAll")
        let running = Array(tasks.values)
        tasks.removeAll()
        generations.removeAll()
        originals.removeAll()
        labels.removeAll()
        states.removeAll()
        order.removeAll()
        running.forEach { $0.cancel() }
        notify()
    }

    // MARK: - 내부

    /// InFlight 표식과 Task 등록을 한 번에 끝낸다. 반드시 `await` 없는 구간에서 부른다.
    private func register(_ request: UploadRequest) {
        let attemptId = request.attemptId
        nextGeneration += 1
        let generation = nextGeneration
        generations[attemptId] = generation
        if states[attemptId] == nil { order.append(attemptId) }
        states[attemptId] = .inFlight

        tasks[attemptId] = Task { [client, sessionId, sessionToken] in
            let result = await client.upload(request, sessionId: sessionId, sessionToken: sessionToken)
            let state: UploadState
            switch result {
            case let .accepted(analysisJobId):
                state = .done(analysisJobId: analysisJobId)
            case let .rejected(code, message, retryable, _):
                // 녹음을 새로 해야 풀리는 거절이면 재전송 쪽은 닫는다 (KAN-147). 두 복구
                // 경로를 함께 세우면 화면이 어느 쪽을 권하는지 말할 수 없다.
                let rerecord = code.map(UploadManager.rerecordCodes.contains) ?? false
                state = .failed(retryable: retryable && !rerecord, message: message, rerecord: rerecord)
            case let .transportError(failure, _):
                // 응답이 오지 않은 것은 녹음의 문제가 아니다. 언제든 다시 보낼 수 있게 남긴다.
                state = .failed(retryable: true, message: failure.userMessage)
            }
            // `await`가 없는 것이 이 이식의 핵심이다 — 이 Task 본문은 actor 위에서 돌기 때문에
            // 등록을 끝낸 동기 구간이 지나기 전에는 시작조차 하지 않는다.
            self.publish(attemptId, generation: generation, state: state)
        }
        notify()
    }

    /// 등록된 세대 번호가 여전히 이 전송의 것일 때만 결과를 반영한다.
    /// ``discard(_:)``/``clearAll()``은 번호를 지우고 ``enqueue(_:label:)``/``retry(_:)``는 새 번호로 덮으므로,
    /// 폐기되거나 교체된 시도의 뒤늦은 결과는 여기서 버려진다.
    /// 반영과 원본 정리가 같은 actor 구간 안에 있어, 늦은 Done이 새 시도의 원본을 지우는 일도 없다.
    private func publish(_ attemptId: String, generation: Int, state: UploadState) {
        guard generations[attemptId] == generation else { return }
        states[attemptId] = state
        tasks.removeValue(forKey: attemptId)
        // 성공한 업로드의 WAV 바이트는 더 쓸 일이 없다. 실패분은 재시도용으로 남긴다.
        if case .done = state { originals.removeValue(forKey: attemptId) }
        notify()
    }

    private func notify() {
        guard !observers.isEmpty else { return }
        let snapshot = entries
        for continuation in observers.values { continuation.yield(snapshot) }
    }
}

extension UploadManager {
    /// 폐기 불변식(FR-DP-02)만 확인하려고 여는 창. 안드로이드 테스트가 리플렉션으로
    /// `originals`를 들여다본 자리이고, 여기서는 `@testable import`가 그 역할을 한다.
    var retainedOriginalKeys: Set<String> { Set(originals.keys) }
}
