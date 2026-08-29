import Foundation

/// 웹 위에 지금 무엇이 겹쳐 있는지 (KAN-100).
///
/// WebView는 어느 페이즈에서도 살아 있고 네이티브 화면은 그 위를 덮을 뿐이다 —
/// 진행의 정본은 웹 상태 머신이라 웹을 내리면 돌아갈 자리를 잃는다.
///
/// 안드로이드의 `sealed interface` + `data class`를 열거형 + 중첩 구조체로 옮겼다. 케이스에는
/// 기본 인자를 적을 수 없어서, 안드로이드 호출 자리(`Recording(start)`)를 그대로 옮기려면
/// 구조체 생성자의 기본값이 필요하다.
public enum TestFlowPhase: Equatable, Sendable {

    /// 웹이 전면. 문항 진행·결과 표시는 전부 여기서 돈다.
    case web

    /// VOICE 진입 요청이 왔는데 마이크 권한이 없다 — 게이트(KAN-98)를 다시 세운다.
    case needsPermission(NeedsPermission)

    /// 녹음 화면이 웹 위를 덮고 있다.
    case recording(Recording)

    /// 녹음은 끝났고 그 시도의 결과가 웹에 닿기를 기다리는 중이다 (KAN-146).
    case submitting(Submitting)

    /// 시작 게이트에서 한 번 허용받았어도 설정에서 회수될 수 있어, 진입마다 확인이 필요하다.
    /// 통과하면 ``pending``으로 녹음을 이어간다 — 권한 때문에 문항 하나를 잃지 않는다.
    ///
    /// ``afterUploadFailure``와 ``failureMessage``는 ``Recording``의 같은 이름 필드로 그대로
    /// 넘어간다 (KAN-147) — 이 게이트가 서는 경로 하나가 업로드 재녹음 전환이라, 여기서 들고
    /// 있지 않으면 권한을 허용받아 녹음 화면이 열리는 순간 "왜 다시 녹음하는지"가 사라진다.
    public struct NeedsPermission: Equatable, Sendable {
        public let pending: VoiceItemStart
        public let afterUploadFailure: Bool
        public let failureMessage: String?

        public init(
            pending: VoiceItemStart,
            afterUploadFailure: Bool = false,
            failureMessage: String? = nil
        ) {
            self.pending = pending
            self.afterUploadFailure = afterUploadFailure
            self.failureMessage = failureMessage
        }
    }

    /// ``afterUploadFailure``는 이 화면이 업로드 재녹음 전환
    /// (``TestFlowController/onUploadGivenUp(attemptId:micGranted:message:)``)으로 스스로 다시
    /// 열린 것인지다 (KAN-147). 사용자가 [다음]을 누르고 웹으로 돌아간 뒤에 벌어지는 일이라,
    /// 이유를 적어두지 않으면 녹음 화면이 까닭 없이 되돌아온 것으로 보인다. 기본값 false는
    /// 웹 요청으로 정상 진입한 경우다.
    ///
    /// ``failureMessage``는 그 이유로 서버가 준 문구다. 녹음이 왜 거절됐는지(너무 길다, 너무
    /// 작다)는 서버만 아는 것이라, 앱이 지어낸 일반 문구로 덮으면 사용자는 다음 녹음에서 같은
    /// 실패를 반복한다. nil이면 화면이 기본 안내를 쓴다.
    public struct Recording: Equatable, Sendable {
        public let start: VoiceItemStart
        public let afterUploadFailure: Bool
        public let failureMessage: String?

        public init(
            start: VoiceItemStart,
            afterUploadFailure: Bool = false,
            failureMessage: String? = nil
        ) {
            self.start = start
            self.afterUploadFailure = afterUploadFailure
            self.failureMessage = failureMessage
        }
    }

    /// 오버레이는 그대로 서 있다.
    ///
    /// 예전에는 [다음]을 누른 순간 웹으로 돌아갔다. 그런데 결과는 업로드가 끝나야 나가므로 그 사이
    /// 웹의 대기 화면이 잠깐 드러났다가 다음 문항으로 교체됐다 — 음성 문항마다 반복되는 깜빡임의
    /// 마지막 조각이다. 결과가 나갈 때까지 화면을 붙들면 그 순간 자체가 없어진다.
    ///
    /// 놓는 자리는 셋이다: 결과 주입이 끝나면 ``TestFlowController/onResultDelivered(attemptId:)``,
    /// 업로드가 실패로 확정되면 ``TestFlowController/onUploadsChanged(_:)``, 그 둘 어느 쪽도 오지
    /// 않으면(업로드 자취가 사라진 복원 경로 등)
    /// ``TestFlowController/onSubmitTimeout(attemptId:uploads:)``가 받는다.
    public struct Submitting: Equatable, Sendable {
        public let start: VoiceItemStart
        public let attemptId: String

        public init(start: VoiceItemStart, attemptId: String) {
            self.start = start
            self.attemptId = attemptId
        }
    }

    public static func needsPermission(
        _ pending: VoiceItemStart,
        afterUploadFailure: Bool = false,
        failureMessage: String? = nil
    ) -> TestFlowPhase {
        .needsPermission(
            NeedsPermission(
                pending: pending,
                afterUploadFailure: afterUploadFailure,
                failureMessage: failureMessage
            )
        )
    }

    public static func recording(
        _ start: VoiceItemStart,
        afterUploadFailure: Bool = false,
        failureMessage: String? = nil
    ) -> TestFlowPhase {
        .recording(
            Recording(start: start, afterUploadFailure: afterUploadFailure, failureMessage: failureMessage)
        )
    }

    public static func submitting(_ start: VoiceItemStart, attemptId: String) -> TestFlowPhase {
        .submitting(Submitting(start: start, attemptId: attemptId))
    }
}

/// 화면에 떠 있던 `shown`이 `current`로 그대로 이어지는가 (KAN-146).
///
/// 오버레이가 화면에서 빠질 때 녹음 상태를 되감을지 정하는 판정이다. 화면 재생성은 뷰를 통째로
/// 버렸다가 다시 만드므로 페이즈가 그대로여도 이 자리를 지나가는데, 그때 되감으면 진행 중인
/// 녹음이나 기다리는 중인 제출이 죽는다.
///
/// 판정이 방향에 따라 다르다:
/// - 녹음 중이었다면 같은 문항의 녹음이거나 **그 문항의 제출로 넘어간 것까지** 이어짐이다.
///   [다음]으로 제출에 들어갈 때 되감으면 방금 그린 '내 억양' 곡선이 제출 화면에서 사라진다.
/// - 제출을 기다리던 중이었다면 **같은 문항의 제출만** 이어짐이다. 제출에서 녹음으로 되돌아온 것은
///   그 문항을 처음부터 다시 하는 것이므로(웹이 결과를 못 받고 문항을 다시 열었을 때 생긴다)
///   되감아야 한다 — 안 그러면 이미 제출해 PCM이 빠져나간 확인 화면이 그대로 뜨고, 거기서
///   [다음]은 아무 일도 못 한다.
///
/// 여기 있는 이유는 ``TestFlowController``가 분리된 이유와 같다 — 화면 겹침의 정확성을 좌우하는
/// 판정을 SwiftUI 안에 두면 시뮬레이터 없이 검증할 수 없다.
public func continuesFrom(shown: TestFlowPhase, current: TestFlowPhase) -> Bool {
    switch shown {
    case .submitting(let shownSubmitting):
        if case .submitting(let currentSubmitting) = current {
            return currentSubmitting.start.itemId == shownSubmitting.start.itemId
        }
        return false

    case .recording(let shownRecording):
        switch current {
        case .recording(let currentRecording):
            return currentRecording.start.itemId == shownRecording.start.itemId
        case .submitting(let currentSubmitting):
            return currentSubmitting.start.itemId == shownRecording.start.itemId
        default:
            return false
        }

    // 오버레이가 떠 있지 않던 페이즈는 이어질 것도 없다.
    default:
        return false
    }
}

/// 웹 ↔ 네이티브 화면 전환 오케스트레이션 (KAN-100). 브리지 콜백·권한 결과·녹음 종료·업로드
/// 완료가 여기로 모인다.
///
/// 화면에서 분리한 이유: 어떤 화면을 겹칠지의 판정과 "끝난 시도를 언제 한 번만 웹으로 돌려주는가"가
/// 진행의 정확성을 좌우하는데, SwiftUI·WKWebView·업로드에 붙어 있으면 시뮬레이터 없이 검증할 수
/// 없다 (``WebLoadController``·`MicPermissionController`와 같은 구조).
///
/// 호출은 전부 메인 스레드에서 온다 — 브리지가 메인으로 넘기고 나머지는 SwiftUI 콜백이다.
/// 그래서 대기 목록에 동기화를 두지 않는다. 앱 계층의 `ObservableObject` 래퍼가 호출 직후
/// ``phase``를 다시 읽는다.
public final class TestFlowController {

    /// 대기 시도 하나. `meta`는 결과 조립에 쓰는 브리지 계약 값이고, `start`는 그 시도가 어느
    /// 문항의 것이었는지를 화면 단위로 되살리기 위한 원본 요청이다 (KAN-147) — 업로드를 포기했을
    /// 때 녹음 화면을 다시 열려면 문항 문구, 번호, 가이드 곡선이 전부 필요한데, `meta`에는
    /// itemId밖에 없다.
    ///
    /// `start`가 nil인 것은 이 필드가 생기기 전 형식으로 저장됐다가 복원된 시도다. 그 시도는
    /// 자동 재개를 할 수 없어 웹의 [녹음 화면 다시 열기]로 되돌아간다.
    private struct PendingAttempt {
        let meta: ItemAttempt
        let start: VoiceItemStart?
    }

    public private(set) var phase: TestFlowPhase

    /// 업로드가 끝나기를 기다리는 시도들. 화면이 이 값을 읽지 않으므로(결과는
    /// ``onUploadsChanged(_:)``의 반환값으로만 나간다) 관측 대상으로 둘 이유가 없다.
    /// 등록 순서대로 내보내려고 안드로이드는 `LinkedHashMap`을 쓰고, 이쪽은 배열로 순서를 지킨다
    /// (attemptId는 유일하다).
    private var pendingAttempts: [PendingAttempt] = []

    private init(phase: TestFlowPhase, restoredAttempts: [PendingAttempt]) {
        self.phase = phase
        self.pendingAttempts = restoredAttempts
    }

    public convenience init() {
        self.init(phase: .web, restoredAttempts: [])
    }

    /// 웹이 VOICE 문항에 진입했다. 녹음 중이거나 권한 게이트가 서 있으면 무시한다 — 브리지 콜백은
    /// 임의 타이밍에 오고(§8) 웹 리로드·이중 호출로 같은 요청이 두 번 들어올 수 있는데, 뒤늦은
    /// 요청이 진행 중인 녹음을 갈아치우면 이미 녹음된 음성을 잃는다.
    ///
    /// 제출을 기다리는 중(``TestFlowPhase/submitting(_:)``)에는 받아준다 (KAN-146). 그 가드가
    /// 지키려는 것은 아직 손에 있는 녹음인데, 제출 뒤에는 PCM이 이미 업로드로 넘어가 잃을 것이
    /// 없다. 반대로 여기서 막으면 웹이 다음 문항으로 넘어갔는데 네이티브가 따라가지 못해 진행이
    /// 멈춘다 — 진행의 정본은 웹이므로 웹이 다음 문항을 열면 화면도 따라가야 한다. 앞 시도의
    /// 결과는 대기 목록에 그대로 남아 준비되는 대로 실려 나간다.
    ///
    /// 같은 itemId가 다시 오는 것(재녹음)은 막지 않는다. 결과 유실·재시도 경로에서 자연스러운
    /// 흐름이고, 중복 제출은 웹 상태 머신의 가드가 거른다.
    public func onStartVoiceItem(_ start: VoiceItemStart, micGranted: Bool) {
        switch phase {
        case .recording, .needsPermission: return
        default: break
        }
        phase = micGranted ? .recording(start) : .needsPermission(start)
    }

    /// 게이트를 통과했다. 기다리던 문항으로 곧장 들어간다 — 웹에 되돌려 다시 요청하게 만들면
    /// 사용자가 같은 문항을 두 번 시작하는 셈이다.
    ///
    /// 게이트가 서 있지 않을 때 오는 허용 통지(설정 복귀 시의 재확인 등)는 무시한다.
    ///
    /// 게이트가 들고 있던 재녹음 사유는 그대로 옮긴다 (KAN-147) — 권한 팝업이 한 번 끼었다고
    /// 사용자가 읽어야 할 서버 안내가 사라지면 안 된다.
    public func onPermissionGranted() {
        guard case .needsPermission(let gate) = phase else { return }
        phase = .recording(
            gate.pending,
            afterUploadFailure: gate.afterUploadFailure,
            failureMessage: gate.failureMessage
        )
    }

    /// 녹음을 마치고 제출했다. 결과가 웹에 나갈 때까지 ``TestFlowPhase/submitting(_:)``으로 화면을
    /// 붙든다 (KAN-146) — 여기서 곧장 웹으로 돌아가면 결과가 도착하기 전의 대기 화면이 한 번 드러난다.
    ///
    /// 진행 자체는 여전히 업로드를 기다리지 않는다: 대기 시도는 지금 등록되고, 결과는 준비되는 대로
    /// ``onUploadsChanged(_:)``가 실어 보낸다. 붙드는 것은 화면뿐이고, 그 화면은 주입이 끝나는 대로
    /// ``onResultDelivered(attemptId:)``가 놓는다.
    ///
    /// 녹음 화면 밖에서 오는 종료 통지는 무시한다. 이탈·화면 재생성으로 이미 화면이 내려간 뒤의
    /// 뒤늦은 콜백이라 어느 문항의 시도인지 말할 수 없다.
    ///
    /// 같은 문항의 앞 시도들은 여기서 대기 목록에서 빠지고, 그 attemptId가 반환값으로 나간다
    /// (KAN-147). 한 문항에 살아 있는 시도는 하나여야 한다 — 앞 시도가 남아 있으면 상태 바에
    /// 그것의 [재시도]가 그대로 서 있고, 그걸 누르면 같은 문항에 분석 작업이 둘 생겨 웹이 결과를
    /// 두 번 받는다. 밀려난 업로드의 바이트를 실제로 폐기하는 것은 호출자 몫이다 — 업로드를 이
    /// 클래스가 알면 단위 테스트가 불가능해진다.
    @discardableResult
    public func onRecordingFinished(
        attemptId: String,
        durationMs: Int64,
        quality: QualityStatus
    ) -> [String] {
        guard case .recording(let current) = phase else { return [] }
        let start = current.start
        let superseded = pendingAttempts
            .filter { $0.meta.itemId == start.itemId && $0.meta.attemptId != attemptId }
            .map(\.meta.attemptId)
        pendingAttempts.removeAll { superseded.contains($0.meta.attemptId) }
        let attempt = PendingAttempt(
            meta: ItemAttempt(
                itemId: start.itemId,
                attemptId: attemptId,
                durationMs: durationMs,
                quality: quality
            ),
            start: start
        )
        // 같은 키가 이미 있으면 자리를 지킨 채 값만 바꾼다 (LinkedHashMap.put과 같은 동작).
        if let index = pendingAttempts.firstIndex(where: { $0.meta.attemptId == attemptId }) {
            pendingAttempts[index] = attempt
        } else {
            pendingAttempts.append(attempt)
        }
        phase = .submitting(start, attemptId: attemptId)
        return superseded
    }

    /// 이 시도의 업로드를 포기하고 녹음부터 다시 한다 — 서버가 녹음 자체를 거절해
    /// ``UploadState/Failure/rerecord``가 선 경우다 (KAN-147, 2026-08-25 B안).
    ///
    /// 전송 실패는 여기로 오지 않는다. 그쪽은 [재시도]가 계속 서 있어 사용자가 직접 다시 보낸다 —
    /// 응답이 오지 않은 것과 서버가 이 녹음을 못 쓰겠다고 답한 것은 복구 경로가 다르다.
    ///
    /// 웹이 아니라 네이티브가 화면을 다시 여는 이유: 브리지 표면을 최소로 두기로 한 계약이라
    /// 웹은 네이티브 쪽 업로드 실패를 통지받지 않는다. 그래서 웹은 결과가 올 때까지 그 문항의 대기
    /// 화면에 그대로 머물러 있고 — 바로 그 점이 여기서 화면을 다시 열어도 되는 근거다.
    ///
    /// 시도를 대기 목록에서 버리는 이유는 그 시도의 결과가 영영 조립되지 않기 때문이다. 남겨두면
    /// ``onUploadsChanged(_:)``가 매번 훑고 지나가는 가짜 대기가 된다. 새 녹음은 새 attemptId를 받는다.
    ///
    /// 사용자가 이미 다른 무언가를 녹음하는 중(``TestFlowPhase/recording(_:)``,
    /// ``TestFlowPhase/needsPermission(_:)``)이면 화면은 건드리지 않는다 — 앞 문항의 뒤늦은 포기가
    /// 손에 든 녹음을 갈아치우면 안 된다.
    ///
    /// - Parameter message: 서버가 이 녹음을 거절하며 준 문구. 다시 열리는 녹음 화면이 그대로
    ///   보여준다 — 왜 다시 녹음해야 하는지는 서버만 아는 것이라, 앱이 지어낸 일반 문구로 덮으면
    ///   사용자가 같은 실패를 반복한다. nil이면 화면이 기본 안내를 쓴다.
    /// - Returns: 이 컨트롤러가 시도를 거둬갔는가. false면 이미 밀려났거나 모르는 시도라 할 일이
    ///   없다. true면 호출자가 그 업로드의 바이트와 상태를 폐기한다.
    @discardableResult
    public func onUploadGivenUp(attemptId: String, micGranted: Bool, message: String? = nil) -> Bool {
        guard let index = pendingAttempts.firstIndex(where: { $0.meta.attemptId == attemptId }) else {
            return false
        }
        let dropped = pendingAttempts.remove(at: index)
        switch phase {
        case .recording, .needsPermission:
            break
        default:
            // start가 없는 것은 구버전 형식에서 복원된 시도뿐이다. 다시 열 화면을 만들 수 없어
            // 웹의 [녹음 화면 다시 열기]에 맡긴다 — 업로드 폐기는 그대로 진행한다.
            if let start = dropped.start {
                // 권한이 회수됐으면 게이트가 먼저 서지만 사유는 게이트가 들고 간다 —
                // 통과 직후 열리는 녹음 화면이 그대로 이어받는다.
                phase = micGranted
                    ? .recording(start, afterUploadFailure: true, failureMessage: message)
                    : .needsPermission(start, afterUploadFailure: true, failureMessage: message)
            }
        }
        return true
    }

    /// 붙들어 둔 화면의 상한 (KAN-146). 업로드가 뒷받침하지 않는 붙들기를 걷는 최후 안전망이다.
    ///
    /// 업로드가 아직 진행 중이면 걷지 않는다 — 끝날 때까지 현재 문항 화면을 유지하는 것이 이 티켓의
    /// 요구고, 여기서 시간으로 끊으면 없애려던 대기 화면이 정확히 그 자리에 생긴다. 발화 시점에 다시
    /// 확인하는 이유가 이것이다: 타이머를 걸 때는 업로드가 아직 목록에 안 올라와 있을 수 있고
    /// (등록과 화면 반영 사이 한 프레임), 그 사이 앱이 백그라운드로 가면 그 상태가 굳는다.
    ///
    /// attemptId를 받아 대조하는 이유: 이미 결과가 나가 다음 문항으로 넘어간 뒤 뒤늦게 도착한
    /// 타이머가 새로 뜬 화면을 걷어버리면 안 된다.
    public func onSubmitTimeout(attemptId: String, uploads: [String: UploadState]) {
        guard case .submitting(let awaiting) = phase else { return }
        if awaiting.attemptId != attemptId { return }
        if case .inFlight? = uploads[attemptId] { return }
        phase = .web
    }

    /// 시도를 등록하지 않고 웹으로 돌아간다.
    ///
    /// 남은 호출처는 PCM 없는 제출 하나뿐이다: 올릴 바이트가 없으면 결과도 만들어질 수 없어,
    /// 시도로 등록하면 웹이 오지 않을 결과를 기다리며 그 문항에 멈춘다. 등록 없이 돌려보내
    /// [녹음 화면 다시 열기]로 다시 녹음하게 하는 쪽이 정본이다.
    /// (그 경로에서는 녹음 소비가 이미 PCM을 가져가 폐기까지 끝냈다 — FR-DP-02)
    ///
    /// 진행 전체를 초기화하지는 않는다: 진행의 정본은 웹 상태 머신이고 돌아가기는 해당 문항을
    /// 다시 시도하겠다는 뜻일 뿐이라, 여기서 앞 문항들의 대기 시도까지 버리면 이미 끝난 업로드의
    /// 결과가 웹에 영영 도착하지 않는다.
    ///
    /// 녹음 화면의 [나가기] 버튼은 KAN-147에서 없앴다 (2026-08-19 결정: 이탈 UX는 KAN-39 몫).
    public func onRecordingExit() {
        guard case .recording = phase else { return }
        phase = .web
    }

    /// 대응 업로드가 없는 대기 시도를 걷어낸다. 복원 직후 한 번 부른다.
    ///
    /// 실제로 지우는 건 프로세스 사망 복원 경로다: 대기 시도는 저장값이 살리지만 업로드는 메모리에만
    /// 있어 함께 사라진다 — 남겨두면 ``onUploadsChanged(_:)``가 영영 조립하지 못할 가짜 대기가 된다.
    /// 그 문항은 웹이 결과를 받지 못한 채로 남아 [녹음 화면 다시 열기]로 다시 요청하는 쪽이 정본이다.
    ///
    /// 화면 재생성은 업로드를 든 객체가 살아남아 키가 그대로이므로 아무것도 지우지 않는다.
    public func pruneAttemptsWithoutUpload(_ knownAttemptIds: Set<String>) {
        pendingAttempts.removeAll { !knownAttemptIds.contains($0.meta.attemptId) }
    }

    /// 업로드 상태가 바뀔 때마다 부른다. 완료된 시도만 ``ItemResult``로 조립해 반환하고 대기
    /// 목록에서 지운다 — 같은 시도를 두 번 내보내지 않는다(웹은 문항당 결과 1회를 전제로 진행한다).
    /// 진행 중·실패는 남겨 둔다: 재시도가 성공하면 그때 실려 나간다.
    ///
    /// 반환값을 브리지로 넘기는 결선은 호출자 몫이다. 여기서 `evaluateJavaScript`를 부르지 않는
    /// 것이 이 클래스를 시뮬레이터 없이 검증 가능하게 유지하는 조건이다.
    @discardableResult
    public func onUploadsChanged(_ uploads: [String: UploadState]) -> [ItemResult] {
        var delivered: [ItemResult] = []
        var remaining: [PendingAttempt] = []
        for attempt in pendingAttempts {
            if let result = assembleItemResult(meta: attempt.meta, uploads: uploads) {
                delivered.append(result)
            } else {
                remaining.append(attempt)
            }
        }
        pendingAttempts = remaining

        /*
         * 업로드가 실패했으면 붙들고 있던 화면을 여기서 놓는다 (KAN-146). 결과는 영영 조립되지
         * 않는데, 그걸 이미 아는 자리에서 계속 기다리면 오버레이는 "제출 중…"이라 말하는 동안 그
         * 아래 업로드 상태 바는 같은 화면에서 이미 "업로드 실패 [재시도]"를 띄운다 — 한 화면이 서로
         * 다른 두 말을 하는 구간이라 바로 놓는다.
         *
         * 성공한 경우는 여기서 놓지 않는다. 결과를 조립했다는 것과 웹이 그 결과를 받아 다음 문항을
         * 그렸다는 것은 다르고, 그 사이에 놓으면 걷힌 자리에 아직 앞 문항의 대기 화면이 남아 한
         * 프레임 드러난다. 주입이 끝난 뒤 ``onResultDelivered(attemptId:)``가 놓는다.
         */
        if case .submitting(let awaiting) = phase, case .failed? = uploads[awaiting.attemptId] {
            phase = .web
        }
        return delivered
    }

    /// 결과 주입이 끝났다 — 웹이 ``onUploadsChanged(_:)``가 돌려준 결과를 받아 다음 문항으로
    /// 넘어갔다는 뜻이다 (KAN-146). 이제 붙들고 있던 화면을 놓는다.
    ///
    /// 조립 시점이 아니라 주입 완료 시점인 이유: 그 둘 사이에 웹이 다시 그릴 틈이 있어, 조립
    /// 자리에서 놓으면 걷힌 아래에 아직 앞 문항의 대기 화면이 남아 한 프레임 드러난다.
    ///
    /// attemptId를 대조해 지금 기다리는 시도의 주입일 때만 놓는다 — 앞 문항의 뒤늦은 주입이 새로
    /// 뜬 화면을 걷어버리면 안 된다.
    public func onResultDelivered(attemptId: String) {
        guard case .submitting(let awaiting) = phase else { return }
        if awaiting.attemptId != attemptId { return }
        phase = .web
    }

    // MARK: - 저장·복원

    /// 화면 재생성·프로세스 복원을 넘기는 저장값. 안드로이드 `rememberSaveable`의 `Saver` 자리다.
    ///
    /// 저장하는 이유: 화면이 재생성돼도 녹음을 든 객체는 살아남는데 ``phase``만 증발하면 녹음 화면이
    /// 사라진 자리에 웹이 드러나고, 웹은 보내지도 않은 결과를 기다리며 그 문항에 멈춘다. 대기 시도도
    /// 마찬가지로, 증발하면 곧 완료될 업로드의 결과가 갈 곳을 잃는다. 어느 쪽이든 사용자가 스스로
    /// 빠져나올 수 없는 상태라 저장한다.
    ///
    /// 복원할 때 실제 권한과 대조하지 않는다(`MicPermissionController.restored`와 다른 점):
    /// 설정에서 권한을 회수하면 OS가 프로세스를 재시작해 이 상태 자체가 남지 않고, 반대로
    /// 허용된 채 복원된 needsPermission은 게이트의 재확인이 곧바로 통과시킨다.
    public func saved() -> String {
        let flow = SavedFlow(
            phase: {
                switch phase {
                case .web: return .web
                case .needsPermission: return .needsPermission
                case .recording: return .recording
                case .submitting: return .submitting
                }
            }(),
            start: {
                switch phase {
                case .web: return nil
                case .needsPermission(let gate): return gate.pending
                case .recording(let recording): return recording.start
                case .submitting(let submitting): return submitting.start
                }
            }(),
            attemptId: {
                if case .submitting(let submitting) = phase { return submitting.attemptId }
                return nil
            }(),
            // 재녹음 사유는 두 페이즈가 나눠 든다 (KAN-147) — 게이트에서 재생성돼도 사유를 잃지 않는다.
            afterUploadFailure: {
                switch phase {
                case .recording(let recording): return recording.afterUploadFailure
                case .needsPermission(let gate): return gate.afterUploadFailure
                default: return false
                }
            }(),
            failureMessage: {
                switch phase {
                case .recording(let recording): return recording.failureMessage
                case .needsPermission(let gate): return gate.failureMessage
                default: return nil
                }
            }(),
            attempts: pendingAttempts.map {
                SavedAttempt(
                    itemId: $0.meta.itemId,
                    attemptId: $0.meta.attemptId,
                    durationMs: $0.meta.durationMs,
                    quality: $0.meta.quality,
                    start: $0.start
                )
            }
        )
        guard let data = try? JSONEncoder().encode(flow),
              let json = String(data: data, encoding: .utf8)
        else { return "" }
        return json
    }

    /// 저장값이 깨져 있으면(구버전 형식 등) 저장이 없었던 것으로 본다 — nil을 돌려주면 호출자가
    /// 새 컨트롤러를 만들고, 진행의 정본인 웹이 문항을 다시 요청한다.
    public static func restored(from saved: String) -> TestFlowController? {
        guard let data = saved.data(using: .utf8),
              let flow = try? JSONDecoder().decode(SavedFlow.self, from: data)
        else { return nil }

        // start 없는 needsPermission·recording·submitting은 성립하지 않는 조합이라 웹으로 되돌린다
        // (submitting은 attemptId까지 있어야 한다 — 어느 시도를 기다리는지 모르면 걷을 수도 없다).
        let phase: TestFlowPhase
        if let start = flow.start {
            switch flow.phase {
            case .needsPermission:
                phase = .needsPermission(
                    start,
                    afterUploadFailure: flow.afterUploadFailure,
                    failureMessage: flow.failureMessage
                )
            case .recording:
                phase = .recording(
                    start,
                    afterUploadFailure: flow.afterUploadFailure,
                    failureMessage: flow.failureMessage
                )
            case .submitting:
                if let attemptId = flow.attemptId {
                    phase = .submitting(start, attemptId: attemptId)
                } else {
                    phase = .web
                }
            case .web:
                phase = .web
            }
        } else {
            phase = .web
        }

        return TestFlowController(
            phase: phase,
            restoredAttempts: flow.attempts.map {
                PendingAttempt(
                    meta: ItemAttempt(
                        itemId: $0.itemId,
                        attemptId: $0.attemptId,
                        durationMs: $0.durationMs,
                        quality: $0.quality
                    ),
                    start: $0.start
                )
            }
        )
    }
}

/// 저장 형식. 손수 풀어 쓰는 대신 JSON 한 줄로 접는다 — ``VoiceItemStart``가 브리지 계약에서
/// 이미 `Codable`이라 그대로 재사용된다. ``ItemAttempt``는 브리지 계약 타입이라 거기에 저장용
/// 표기를 더하는 대신 여기서 사본을 둔다.
///
/// 이 형식은 두 플랫폼이 주고받는 값이 아니라 기기 안에서만 도는 값이다 — 안드로이드 저장값과
/// 호환될 필요가 없고, 필요한 것은 **자기 자신의 구버전 저장값**과의 호환뿐이다. 그래서 나중에
/// 생긴 필드는 전부 기본값을 갖는다.
private struct SavedFlow: Codable {
    let phase: SavedPhase
    let start: VoiceItemStart?
    /// submitting이 기다리는 시도. 다른 페이즈에서는 nil이다.
    let attemptId: String?
    /// recording·needsPermission이 업로드 재녹음 전환으로 다시 열린 화면인가 (KAN-147).
    /// 다른 페이즈에서는 뜻이 없다.
    let afterUploadFailure: Bool
    /// 그 전환에서 서버가 준 문구 (KAN-147). 기본값 nil은 이 필드가 생기기 전 형식으로 저장된
    /// 값도 그대로 복원되게 한다 — 그렇게 복원된 화면은 기본 안내를 쓴다.
    let failureMessage: String?
    let attempts: [SavedAttempt]

    init(
        phase: SavedPhase,
        start: VoiceItemStart?,
        attemptId: String?,
        afterUploadFailure: Bool,
        failureMessage: String?,
        attempts: [SavedAttempt]
    ) {
        self.phase = phase
        self.start = start
        self.attemptId = attemptId
        self.afterUploadFailure = afterUploadFailure
        self.failureMessage = failureMessage
        self.attempts = attempts
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        phase = try container.decode(SavedPhase.self, forKey: .phase)
        start = try container.decodeIfPresent(VoiceItemStart.self, forKey: .start)
        attemptId = try container.decodeIfPresent(String.self, forKey: .attemptId)
        afterUploadFailure = try container.decodeIfPresent(Bool.self, forKey: .afterUploadFailure) ?? false
        failureMessage = try container.decodeIfPresent(String.self, forKey: .failureMessage)
        attempts = try container.decodeIfPresent([SavedAttempt].self, forKey: .attempts) ?? []
    }
}

private enum SavedPhase: String, Codable {
    case web = "WEB"
    case needsPermission = "NEEDS_PERMISSION"
    case recording = "RECORDING"
    case submitting = "SUBMITTING"
}

private struct SavedAttempt: Codable {
    let itemId: String
    let attemptId: String
    let durationMs: Int64
    let quality: QualityStatus
    /// 업로드를 포기했을 때 이 문항의 녹음 화면을 다시 세우려면 원본 요청이 필요하다 (KAN-147).
    /// 기본값 nil은 이 필드가 생기기 전 형식으로 저장된 값도 그대로 복원되게 한다 — 그렇게 복원된
    /// 시도는 자동 재개만 못 할 뿐 결과 조립은 정상이다.
    let start: VoiceItemStart?

    init(itemId: String, attemptId: String, durationMs: Int64, quality: QualityStatus, start: VoiceItemStart?) {
        self.itemId = itemId
        self.attemptId = attemptId
        self.durationMs = durationMs
        self.quality = quality
        self.start = start
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        itemId = try container.decode(String.self, forKey: .itemId)
        attemptId = try container.decode(String.self, forKey: .attemptId)
        durationMs = try container.decode(Int64.self, forKey: .durationMs)
        quality = try container.decode(QualityStatus.self, forKey: .quality)
        start = try container.decodeIfPresent(VoiceItemStart.self, forKey: .start)
    }
}
