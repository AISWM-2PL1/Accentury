import AccenturyCore
import Combine
import Foundation

/// 인트로(웹) → 시작 게이트(마이크 권한 → 목소리 점검 → 세션 생성) → 테스트 진입(웹) →
/// VOICE 문항마다 녹음 오버레이 (KAN-100, KAN-34)의 **상태 보유자**.
///
/// 안드로이드 `MainActivity.TestFlow` 컴포저블이 `rememberSaveable` 네 칸과 두 컨트롤러로
/// 들고 있던 것을 여기로 옮겼다. 판정은 전부 Core의 순수 컨트롤러
/// (``AccenturyCore/TestFlowController``·``AccenturyCore/SessionGateController``)가 하고,
/// 이 클래스는 그 값을 SwiftUI가 볼 수 있는 `@Published`로 옮기고 디스크에 적는다 —
/// `PermissionGateModel`이 `MicPermissionController`에 하는 일과 같은 구조다.
///
/// ## 저장이 여기 있는 이유
///
/// Compose의 `rememberSaveable`은 OS가 들고 있는 봉투(Bundle)에 값을 넣어 프로세스 사망까지
/// 넘겨준다. iOS에는 그 자리가 없어서 직접 `UserDefaults`에 적는다. 무엇을 적는지는
/// 두 컨트롤러의 `saved()`가 정하고(각각 JSON 한 줄), 이 클래스는 그 문자열을 나르기만 한다.
/*
 * 업로드가 뒷받침하지 않는 붙들기를 걷는 상한 (KAN-146). 안드로이드
 * `MainActivity.ORPHANED_SUBMIT_TIMEOUT_MS`(2_000L)와 같은 값이고, iOS 타이머 API가 초 단위
 * `TimeInterval`을 받으므로 단위만 바꿔 담는다 (Core `loadTimeout`이 8초로 담긴 것과 같은 방식).
 *
 * 업로드가 진행 중인 동안에는 시간으로 걷지 않는다. 진행 중이 아닌데도 화면이 붙들려 있다면
 * 그건 곧 끝나야 할 짧은 창이거나(주입 완료 통지를 기다리는 몇 십 ms) 영영 끝나지 않을
 * 상태(프로세스 사망 복원으로 업로드가 메모리와 함께 사라진 경우)다. 앞엣것은 이 상한이 오기
 * 전에 스스로 풀리고, 뒤엣것은 이 상한만이 풀 수 있다.
 */
let orphanedSubmitTimeout: TimeInterval = 2

@MainActor
final class TestFlowModel: ObservableObject {

    // MARK: 저장 키

    /// ``AccenturyCore/TestFlowController/saved()``의 자리. 안드로이드 `TestFlowController.saver()`.
    static let flowStorageKey = "test_flow_state"
    /// ``AccenturyCore/SessionGateController/saved()``의 자리. 안드로이드 `SessionGateController.saver()`.
    static let gateStorageKey = "session_gate_state"
    /// 안드로이드 `var startRequested by rememberSaveable`.
    static let startRequestedKey = "test_flow_start_requested"
    /// 안드로이드 `var micPassed by rememberSaveable`.
    static let micPassedKey = "test_flow_mic_passed"
    /// 안드로이드 `var voiceCenterHz by rememberSaveable`. 없으면 아직 점검 전이다.
    static let voiceCenterKey = "test_flow_voice_center_hz"

    // MARK: 시작 게이트의 네 칸 (KAN-34, KAN-105)

    /// 웹의 [시작하기]를 눌렀는가. 세션만으로 진입을 정하지 않는 이유는 재응시다 (KAN-34 2단계) —
    /// 재응시는 새 세션을 **손에 든 채** 인트로로 돌아가는 흐름이라, 세션의 존재만으로 진입 URL을
    /// 만들면 인트로가 뜰 새도 없이 테스트가 다시 열린다.
    @Published var startRequested: Bool { didSet { defaults.set(startRequested, forKey: Self.startRequestedKey) } }

    /// 시작 게이트의 마이크 칸을 지났는가. 세션을 기다리는 동안 권한 화면으로 되돌아가지 않게 하는 표시다.
    @Published var micPassed: Bool { didSet { defaults.set(micPassed, forKey: Self.micPassedKey) } }

    /// 목소리 점검(KAN-105)이 잰 중심 음높이. 통과의 결과물이 곧 통과 표시다 —
    /// 통과 여부를 따로 들면 "지났는데 중심이 없다"가 표현 가능해진다.
    @Published var voiceCenterHz: Double? {
        didSet {
            if let voiceCenterHz {
                defaults.set(voiceCenterHz, forKey: Self.voiceCenterKey)
            } else {
                defaults.removeObject(forKey: Self.voiceCenterKey)
            }
        }
    }

    // MARK: 컨트롤러가 정하는 값

    @Published private(set) var phase: TestFlowPhase
    @Published private(set) var gateState: SessionGateState

    /// 세션 생성 재실행 키 (``AccenturyCore/SessionGateController/attempt``).
    /// [다시 시도]는 상태를 `.creating`으로 되돌리기만 해서 값이 그대로일 수 있는데,
    /// 그러면 이미 한 번 돈 이펙트가 다시 돌 이유가 없어 화면이 준비 중인 채로 멈춘다.
    @Published private(set) var gateAttempt: Int

    /// 업로드 한 건씩의 상태. ``UploadModel``이 매니저의 상태 스트림에서 받아 ``setUploads(_:)``로
    /// 흘려 넣는다 — 이 자리가 "결과가 언제 웹으로 나가는가"의 유일한 통로다.
    ///
    /// 업로드 계층을 이 클래스가 직접 알지 않는 이유는 Core `TestFlowController`가 그러지 않는
    /// 이유와 같다: 알면 단위 테스트가 불가능해진다. 여기는 값만 받는다.
    @Published private(set) var uploads: [String: UploadState] = [:]

    /// 공유 시트를 띄울 카드. 화면이 소비하면 nil로 되돌린다 (``consumeShare()``).
    @Published private(set) var pendingShare: SharePayload?

    // MARK: 파생값

    var session: Session? { sessionGate.session }

    /// 브리지 `getSessionToken`이 웹에 건넬 값 (KAN-13). 세션이 없으면 빈 문자열이다 —
    /// 웹 래퍼가 빈 값을 null로 정규화한다.
    var bridgeToken: String { session?.sessionToken ?? "" }

    /// WebView가 로드할 URL. 세션이 곧 진입 URL이다 (KAN-34).
    var webUrl: String {
        buildWebUrl(
            base: AppConfig.webURL,
            appVersionName: AppConfig.appVersionName,
            testEntry: startRequested ? session.map { TestEntry(testVersion: $0.testVersion, sessionId: $0.sessionId) } : nil
        )
    }

    /// allowlist (§7). 우리가 여는 주소의 origin 하나뿐이다.
    var allowedOrigins: Set<String> { Set([webOrigin(AppConfig.webURL)].compactMap { $0 }) }

    // MARK: 내부

    private let flow: TestFlowController
    private let sessionGate: SessionGateController
    private let defaults: UserDefaults
    private let sessionClient: SessionClient?
    private let isMicGranted: () -> Bool

    /// - Parameters:
    ///   - sessionClient: `POST /v0/sessions` 클라이언트. 기본값이 실제 `URLSession` 구현이고,
    ///     Debug 빌드에서 `-StubSession 1`을 주면 서버 없이 고정 세션을 주는 스텁으로 바뀐다
    ///     (``TestFlowModel/defaultSessionClient()``).
    ///   - isMicGranted: 지금의 실제 마이크 권한. 문항 진입마다 다시 묻는다 — 시작 게이트에서
    ///     한 번 허용받았어도 설정에서 회수될 수 있다.
    init(
        defaults: UserDefaults = .standard,
        sessionClient: SessionClient? = TestFlowModel.defaultSessionClient(),
        isMicGranted: @escaping () -> Bool = { MicPermission.currentStatus().granted }
    ) {
        self.defaults = defaults
        self.sessionClient = sessionClient
        self.isMicGranted = isMicGranted

        let flow = TestFlowController.restored(from: defaults.string(forKey: Self.flowStorageKey) ?? "")
            ?? TestFlowController()
        let gate = SessionGateController.restored(from: defaults.string(forKey: Self.gateStorageKey) ?? "")
        self.flow = flow
        self.sessionGate = gate
        self.phase = flow.phase
        self.gateState = gate.state
        self.gateAttempt = gate.attempt

        self.startRequested = defaults.bool(forKey: Self.startRequestedKey)
        self.micPassed = defaults.bool(forKey: Self.micPassedKey)
        self.voiceCenterHz = defaults.object(forKey: Self.voiceCenterKey) as? Double

        /*
         * 대응 업로드가 없는 대기 시도를 한 번 걷어낸다 (안드로이드의 첫 LaunchedEffect).
         *
         * 여기서 넘기는 키가 **늘 비어 있는 것이 규칙 그 자체다**. 업로드는 메모리에만 산다
         * (PCM을 디스크에 남기지 않는 것이 FR-DP-02) — 이 모델이 만들어지는 순간은 프로세스가
         * 막 뜬 뒤라 살아 있는 업로드가 있을 수 없고, 그러면 저장에서 복원된 대기 시도는 전부
         * 영영 조립되지 않을 가짜 대기다. 안드로이드가 `uploadViewModel?.uploads?.value?.keys`를
         * 넘기는 자리인데, 그쪽도 프로세스 사망 복원에서는 같은 빈 집합이다(회전에서만 값이
         * 있고, iOS에는 회전으로 인한 재생성이 없다 — 세로 고정이고 모델은 `@StateObject`다).
         */
        flow.pruneAttemptsWithoutUpload([])
        syncFlow()
    }

    // MARK: 브리지 콜백

    /// 웹의 [시작하기] → 시작 게이트를 연다.
    func onRequestMicPermission() {
        startRequested = true
        #if DEBUG
        smokeLog("FLOW: startRequested=true")
        #endif
    }

    /// 웹이 VOICE 문항에 진입했다 (KAN-100).
    func onStartVoiceItem(_ start: VoiceItemStart) {
        // 시작 게이트를 통과했어도 설정에서 회수됐을 수 있어 진입마다 다시 확인한다.
        flow.onStartVoiceItem(start, micGranted: isMicGranted())
        syncFlow()
        #if DEBUG
        smokeLog("FLOW: startVoiceItem item=\(start.itemId) number=\(start.itemNumber)/\(start.totalItems)")
        #endif
    }

    /// 결과 화면의 [친구에게 공유하기] (KAN-30).
    func onShareResult(_ payload: SharePayload) {
        pendingShare = payload
    }

    func consumeShare() {
        pendingShare = nil
    }

    // MARK: 게이트

    /// 시작 게이트의 마이크 칸을 통과했다.
    func onStartGateMicPassed() {
        micPassed = true
    }

    /// 목소리 점검이 끝났다 (KAN-105).
    func onVoiceCheckDone(centerHz: Double) {
        voiceCenterHz = centerHz
    }

    /// 문항 진입 게이트를 통과했다 — 기다리던 문항의 녹음으로 곧장 들어간다.
    func onPermissionGranted() {
        flow.onPermissionGranted()
        syncFlow()
    }

    /// 결과 없이 웹으로 돌아간다 (PCM 없는 제출 경로 — KAN-147).
    func onRecordingExit() {
        flow.onRecordingExit()
        syncFlow()
    }

    /// 인트로로 되돌린다 (세션 게이트 실패 화면의 [처음으로]).
    func backToIntro() {
        startRequested = false
        micPassed = false
        // 점검도 함께 되돌린다 — 다시 시작하면 마이크를 새로 열게 되므로, 그 마이크가 잘 잡히는지는
        // 그때 다시 확인해야 맞다.
        voiceCenterHz = nil
        sessionGate.restart()
        syncGate()
    }

    // MARK: 세션

    /// 세션 게이트가 화면에 서 있는 동안 한 번 건다. 이미 확보됐거나 실패 화면이면 아무 일도 하지 않는다.
    func createSessionIfNeeded() async {
        guard case .creating = sessionGate.state else { return }
        guard let sessionClient else { return }
        let result = await sessionClient.create(
            appVersion: AppConfig.appVersionName,
            previousToken: sessionGate.pendingPreviousToken
        )
        sessionGate.onResult(result)
        syncGate()
    }

    /// 세션 게이트 실패 화면의 [다시 시도].
    func retrySession() {
        sessionGate.restart()
        syncGate()
    }

    /// 결과 화면의 [다시 테스트하기] (KAN-34 2단계, KAN-107).
    ///
    /// - Returns: 실패했으면 웹에 회신할 payload. 성공(교체)이거나 걸 요청이 없으면 nil이다 —
    ///   성공은 회신하지 않는다. 새 세션을 든 채 인트로로 돌아가므로 회신을 받을 페이지가 사라진다.
    func startRetest() async -> RetestFailure? {
        // nil이면 이미 요청이 나가 있거나 버릴 세션이 없다 — 어느 쪽이든 할 일은 없다.
        guard let previousToken = sessionGate.beginRetest() else { return nil }
        guard let sessionClient else {
            /*
             * 클라이언트를 nil로 주입한 경우(테스트)뿐이다 — 앱은 늘 실제 클라이언트를 든다.
             * 그래도 **웹에는 회신해야 한다** — 결과 화면은 [다시 테스트하기]를 누른 뒤 버튼을
             * 잠그고 실패 회신만 기다린다(시간 기반 해제는 금지, §8). 여기서 조용히 돌아서면
             * 그 버튼이 영영 잠긴 채로 남는다.
             *
             * 진행 중 플래그를 푸는 것도 같은 호출이 한다 — 안 풀면 다시 누를 수도 없다.
             */
            let outcome = sessionGate.onRetestResult(
                .transportError(reason: "세션 클라이언트 없음")
            )
            syncGate()
            guard case .failed(let failure) = outcome else { return nil }
            return retestFailurePayload(failure)
        }
        let result = await sessionClient.create(
            appVersion: AppConfig.appVersionName,
            previousToken: previousToken
        )
        let outcome = sessionGate.onRetestResult(result)
        syncGate()
        switch outcome {
        case .replaced:
            // 새 세션을 든 채 인트로로 돌린다. 진입 URL은 startRequested를 함께 보므로
            // (`webUrl`) 이 한 줄이 곧 인트로 리로드다. micPassed는 되돌리지 않는다 —
            // 권한이 이미 허용이면 다시 묻지 않는 것이 KAN-34 AC다.
            startRequested = false
            return nil
        case .failed(let failure):
            return retestFailurePayload(failure)
        }
    }

    // MARK: 업로드

    /// ``UploadModel``이 매니저의 상태 스트림에서 받은 값을 흘려 넣는다.
    ///
    /// 값이 그대로면 아무것도 하지 않는다 — 스트림은 라벨 등록 같은 화면과 무관한 변화에도
    /// 울리는데, 같은 값을 다시 발행하면 결과 전달 이펙트가 헛돈다.
    func setUploads(_ next: [String: UploadState]) {
        guard uploads != next else { return }
        uploads = next
    }

    /// 녹음이 끝났다 — 대기 시도로 등록하고 화면을 제출 중으로 붙든다 (KAN-146).
    ///
    /// - Returns: 같은 문항의 앞 시도 중 이 녹음에 밀려난 attemptId들. 호출자가 그 업로드를
    ///   폐기한다 (KAN-147) — 남겨두면 상태 바의 [재시도]가 그대로 서 있고, 그걸 누르면 같은
    ///   문항에 분석 작업이 둘 생겨 웹이 결과를 두 번 받는다.
    @discardableResult
    func onRecordingFinished(attemptId: String, durationMs: Int64, quality: QualityStatus) -> [String] {
        let superseded = flow.onRecordingFinished(
            attemptId: attemptId,
            durationMs: durationMs,
            quality: quality
        )
        syncFlow()
        return superseded
    }

    /// 이 시도의 업로드를 포기하고 녹음부터 다시 한다 — 서버가 녹음 자체를 거절해
    /// `rerecord`가 선 경우다 (KAN-147, 2026-08-25 B안).
    ///
    /// - Returns: 컨트롤러가 시도를 거둬갔는가. true면 호출자가 그 업로드의 바이트를 폐기한다.
    @discardableResult
    func onUploadGivenUp(attemptId: String, message: String?) -> Bool {
        // 권한이 그새 회수됐을 수 있다 — 다시 열 화면이 녹음인지 권한 게이트인지를 이 값이 가른다.
        let taken = flow.onUploadGivenUp(attemptId: attemptId, micGranted: isMicGranted(), message: message)
        syncFlow()
        return taken
    }

    /// 지금 페이즈가 업로드 실패로 스스로 다시 열린 녹음 화면인가 (KAN-147). 제출을 기다리는
    /// 중에는 뜻이 없는 값이라 Recording일 때만 참이다.
    var recordingAfterUploadFailure: Bool {
        if case .recording(let recording) = phase { return recording.afterUploadFailure }
        return false
    }

    /// 그 전환에서 서버가 준 문구. nil이면 화면이 기본 안내를 쓴다.
    var recordingFailureMessage: String? {
        if case .recording(let recording) = phase { return recording.failureMessage }
        return nil
    }

    // MARK: 결과 전달

    /// 업로드가 끝난 시도를 ``AccenturyCore/ItemResult``로 조립해 돌려준다 (KAN-100).
    /// 실제 주입(`evaluateJavaScript`)은 화면 몫이다 — 여기서 WebKit을 부르면 이 클래스가
    /// 시뮬레이터 없이 검증 불가능해진다.
    func takeResultsForDelivery() -> [ItemResult] {
        let delivered = flow.onUploadsChanged(uploads)
        syncFlow()
        return delivered
    }

    /// 결과 주입이 끝났다 — 웹이 받아 다음 문항을 그리기 시작했다는 뜻이다 (KAN-146).
    func onResultDelivered(attemptId: String) {
        flow.onResultDelivered(attemptId: attemptId)
        syncFlow()
    }

    /// 붙들어 둔 화면의 상한이 찼다 (KAN-146). 안드로이드의
    /// `LaunchedEffect { delay(ORPHANED_SUBMIT_TIMEOUT_MS); flow.onSubmitTimeout(...) }` 자리다.
    ///
    /// **발화 시점의** 업로드 상태를 넘긴다 — 타이머를 걸 때는 아직 목록에 안 올라와 있을 수 있고
    /// (등록과 화면 반영 사이 한 프레임), 그 사이 채워졌으면 걷지 않는 것이 맞다. 걷을지 말지의
    /// 판정 자체는 Core가 한다.
    func onSubmitTimeout(attemptId: String) {
        flow.onSubmitTimeout(attemptId: attemptId, uploads: uploads)
        syncFlow()
    }

    /// 지금 제출 결과를 기다리는 시도. 없으면 nil이다.
    var submittingAttemptId: String? {
        if case .submitting(let submitting) = phase { return submitting.attemptId }
        return nil
    }

    /// 붙들린 화면을 **업로드가 뒷받침하지 않는다** (KAN-146).
    ///
    /// 업로드가 진행 중인 동안에는 시간으로 걷지 않는다 — 끝나면 결과가 나가 다음 문항이 그려지고,
    /// 실패하면 ``takeResultsForDelivery()``가 그것도 종료로 보고 놓는다. 시간이 개입하면 느린
    /// 망에서 업로드가 아직 도는데 화면을 놓아 버려, 이 티켓이 없애려던 대기 화면이 바로 그
    /// 구간에 다시 생긴다.
    var submitHoldIsUnbacked: Bool {
        guard let submittingAttemptId else { return false }
        if case .inFlight? = uploads[submittingAttemptId] { return false }
        return true
    }

    // MARK: 반영·저장

    private func syncFlow() {
        if phase != flow.phase { phase = flow.phase }
        defaults.set(flow.saved(), forKey: Self.flowStorageKey)
    }

    /// 진입 URL(``webUrl``)과 브리지 토큰(``bridgeToken``)은 계산 프로퍼티라 그 자체로는
    /// 관측 대상이 아니다. 둘의 재료가 전부 여기서 바뀌는 값(세션)이므로, 이 발행이 곧 그 둘의
    /// 갱신 신호다.
    private func syncGate() {
        if gateState != sessionGate.state { gateState = sessionGate.state }
        if gateAttempt != sessionGate.attempt { gateAttempt = sessionGate.attempt }
        defaults.set(sessionGate.saved(), forKey: Self.gateStorageKey)
    }

    /// 이 앱이 쓸 세션 클라이언트. 안드로이드 `remember { OkHttpSessionClient(BuildConfig.API_BASE_URL) }`
    /// 자리이고, 기본은 **실제 서버**다.
    ///
    /// `nonisolated`인 이유는 이 함수가 ``init(defaults:sessionClient:isMicGranted:)``의 기본
    /// 인자이기 때문이다 — 기본 인자 식은 언제나 격리 밖에서 평가된다
    /// (`PermissionGateView`의 모델 주입 생성자가 갈린 것과 같은 제약).
    nonisolated private static func defaultSessionClient() -> SessionClient? {
        #if DEBUG
        /*
         * 백엔드 없이 게이트 뒤 화면을 보려면 `-StubSession 1`을 준다. 기본이 아니라 인자인
         * 것이 요점이다 — 스텁이 기본이면 "세션이 실패하는 화면"을 개발 중에 한 번도 못 본다.
         * 릴리스 바이너리에는 이 분기도, `DebugStubSessionClient`라는 문자열도 없다.
         */
        if UserDefaults.standard.bool(forKey: "StubSession") { return DebugStubSessionClient() }
        #endif
        return URLSessionSessionClient(baseURL: AppConfig.apiBaseURL)
    }
}

#if DEBUG
/// 백엔드 없이 시작 게이트를 통과시키는 디버그 스텁 (`-StubSession 1`). 고정 세션을 즉시 돌려줘
/// 웹이 `?screen=test`로 넘어가는 것까지를 시뮬레이터에서 확인할 수 있게 한다.
///
/// **네트워크를 타지 않는다** — 그래서 이 세션의 `sessionId`·`sessionToken`은 서버가 모르는
/// 값이고, 웹이 이어서 부르는 `GET /v0/tests/{testVersion}`은 백엔드가 붙는 8단계 전에는 실패한다.
/// 여기까지가 이 스텁이 확인해 주는 범위다. 업로드도 같은 이유로 거절된다 — 서버가 모르는
/// 세션이라 401이 온다.
struct DebugStubSessionClient: SessionClient {
    func create(appVersion: String, previousToken: String?) async -> SessionResult {
        .created(
            Session(
                sessionId: "s_debug_stub",
                sessionToken: "st_debug_stub",
                // 웹 테스트가 쓰는 정의 버전 표기와 같은 꼴 (web/src/App.test.tsx).
                testVersion: "gn-2026.08.1",
                scoreVersion: "sv-debug",
                expiresAt: "2099-01-01T00:00:00Z"
            )
        )
    }
}
#endif
