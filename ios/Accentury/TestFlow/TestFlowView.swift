import AccenturyCore
import SwiftUI
import UIKit
import WebKit

/// 인트로(웹) → 시작 게이트 → 테스트 진입(웹) → VOICE 문항마다 녹음 오버레이 (KAN-100, KAN-34).
/// 안드로이드 `MainActivity.TestFlow` 컴포저블의 이식본이다.
///
/// **WebView는 인트로부터 테스트 끝까지 한 인스턴스로 산다.** 진행의 정본이 웹 상태 머신이라
/// WebView를 내리면 어디까지 왔는지가 같이 사라진다 — 네이티브 화면(권한 게이트·목소리 점검·
/// 세션 준비·녹음)은 화면을 갈아끼우는 대신 그 위를 덮는다. 무엇을 덮을지는
/// ``TestFlowModel``(→ Core ``AccenturyCore/TestFlowController``)이 정하고, 여기는 SwiftUI
/// 결선만 한다.
///
/// ## 세로 배치
///
/// 업로드 상태 바는 오버레이가 아니라 화면 **아래**를 나눠 갖는다 (안드로이드의 `Column`) —
/// 녹음 중에도 실패한 업로드의 재시도 통로가 가려지지 않아야 한다.
struct TestFlowView: View {

    @StateObject private var model = TestFlowModel()

    /// 녹음·목소리 점검·업로드의 주인. 화면 값이 다시 만들어져도 살아남아야 하는 것들이라
    /// `@StateObject`다 — 안드로이드가 `ViewModel`에 둔 자리와 같다.
    @StateObject private var recording = RecordingModel()
    @StateObject private var voiceCheck = VoiceCheckModel()
    @StateObject private var uploads = UploadModel()

    /// 결과를 웹에 넣으려면 `evaluateJavaScript`를 부를 인스턴스가 필요하다.
    /// 로드 실패 화면·재시도 구간에는 WebView가 아예 없으므로 옵셔널이다.
    @State private var webView: WKWebView?

    /// 지금 화면에 서 있는 오버레이 페이즈. 페이즈가 바뀔 때 녹음을 되감을지 정하는 데 쓴다
    /// (안드로이드 `DisposableEffect(phase) { onDispose { ... } }`의 자리).
    @State private var shownOverlayPhase: TestFlowPhase?

    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                WebViewHost(
                    url: model.webUrl,
                    allowedOrigins: model.allowedOrigins,
                    sessionToken: model.bridgeToken,
                    onRequestMicPermission: { model.onRequestMicPermission() },
                    onStartVoiceItem: { model.onStartVoiceItem($0) },
                    onStartRetest: { Task { @MainActor in await handleRetest() } },
                    onShareResult: { model.onShareResult($0) },
                    onWebViewCreated: { webView = $0 },
                    // 내가 들고 있는 인스턴스일 때만 놓는다 — 재생성 순서에 따라 새 WebView가 먼저
                    // 등록된 뒤 옛 것이 해제될 수 있고, 그때 방금 받은 참조를 지우면 안 된다.
                    onWebViewReleased: { released in if webView === released { webView = nil } }
                )

                overlay
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            // 세션 전에는 올라간 것도, 실패한 것도 없다 — 상태 바가 설 이유 자체가 없는 구간이다.
            if model.session != nil {
                UploadStatusBar(
                    entries: uploads.entries,
                    labelOf: { uploads.labelOf($0) },
                    onRetry: { uploads.retry($0) }
                )
            }
        }
        /*
         * 업로드가 어느 세션으로 나가는지는 매니저를 만드는 순간 정해진다 (KAN-34). 세션이
         * 바뀌면 새 매니저를 받아 끝난 응시의 업로드가 새 세션에 섞이지 않는다.
         * `.task(id:)`는 첫 등장에도 한 번 도는 자리라 초기 결선이 따로 필요 없다.
         */
        .task(id: model.session?.sessionId) { uploads.bind(to: model.session) }
        /*
         * 업로드 상태를 흐름 쪽으로 흘려 넣는다. 안드로이드는 `collectAsStateWithLifecycle()`이
         * 만든 `uploads` 값 자체가 두 LaunchedEffect의 키였는데, 여기서는 값이 두 모델에 나뉘어
         * 있어 옮기는 한 줄이 먼저 온다.
         */
        .onChange(of: uploads.entries) { _ in
            model.setUploads(uploads.uploads)
            handleRerecordFailures()
        }
        /*
         * 업로드가 끝난 시도를 웹으로 흘려보낸다.
         *
         * WebView가 없는 동안에는 아예 꺼내지 않는다 — `takeResultsForDelivery()`는 꺼낸 결과를
         * 대기 목록에서 지우므로, 받을 곳이 없을 때 부르면 그 문항의 결과가 영영 사라진다.
         * WebView 인스턴스도 함께 보는 덕에 WebView가 돌아오면 그때 밀린 결과가 실려 나간다.
         */
        .onChange(of: model.uploads) { _ in deliverResults() }
        .onChange(of: webView.map(ObjectIdentifier.init)) { _ in deliverResults() }
        /*
         * 녹음 상태 되감기를 [다음] 자리가 아니라 여기서 한다 (KAN-146). 그 자리에서 즉시
         * 되감으면 제출을 기다리는 동안 화면이 대기 상태로 바뀌어, 방금 그린 '내 억양' 곡선이
         * 사라진다. 되감을지의 판정(`continuesFrom`)은 Core에 있다 — 화면 겹침의 정확성을
         * 좌우하는 판정을 SwiftUI 안에 두면 시뮬레이터 없이 검증할 수 없다. 여기서는 "언제
         * 물어보는가"만 정한다.
         */
        .onChange(of: model.phase) { phase in syncOverlayPhase(phase) }
        .onAppear { syncOverlayPhase(model.phase) }
        /*
         * 흐름이 끝나면 업로드 계층을 명시적으로 놓는다 — 구독을 끊고, 남은 음성 바이트를
         * 폐기하고(FR-DP-02), 빌려 둔 백그라운드 실행 시간을 반납한다.
         *
         * `deinit`에 맡기지 않는 이유는 시점이다. `@StateObject`가 언제 해제되는지는 SwiftUI가
         * 정하는데, 빌린 실행 시간은 제때 반납하지 않으면 iOS가 앱을 죽이는 자원이다. `deinit`은
         * 같은 정리를 한 번 더 시도하는 안전망으로 남긴다(반납은 멱등이라 겹쳐도 한 번이다).
         */
        .onDisappear { uploads.teardown() }
        /*
         * 앱이 뒤로 가면 마이크를 놓는다. 녹음 화면이 떠 있는 채로 홈으로 나가는 것은 흔한
         * 일이고, 그동안 마이크가 열려 있는 것은 사용자에게 설명할 수 없는 상태다. 돌아와서
         * 다시 녹음하는 것은 사용자의 몫으로 둔다 — 자동으로 이어서 녹음하면 나가 있는 동안의
         * 소리가 앞부분에 붙은 것처럼 보인다.
         */
        .onChange(of: scenePhase) { phase in
            if phase != .active { recording.reset() }
        }
        /*
         * 자취 없는 제출을 걷는 최후 안전망 (KAN-146). 안드로이드
         * `LaunchedEffect(submittingAttemptId, holdUnbacked)`와 같은 키 두 개다.
         *
         * attemptId를 키에 둔 덕에, 결과가 먼저 나가 다음 문항으로 넘어가면 이 대기는 취소된다 —
         * 뒤늦게 발화한 타이머가 새로 뜬 화면을 걷어버리면 안 된다.
         */
        .task(id: SubmitHoldKey(attemptId: model.submittingAttemptId, unbacked: model.submitHoldIsUnbacked)) {
            guard model.submitHoldIsUnbacked, let attemptId = model.submittingAttemptId else { return }
            try? await Task.sleep(nanoseconds: UInt64(orphanedSubmitTimeout * 1_000_000_000))
            guard !Task.isCancelled else { return }
            model.onSubmitTimeout(attemptId: attemptId)
        }
        // 결과 공유 (KAN-30). 카카오 SDK 대신 OS 공유 시트다 — 아래 `ShareSheet` 주석 참고.
        .sheet(
            isPresented: Binding(
                get: { model.pendingShare != nil },
                set: { if !$0 { model.consumeShare() } }
            )
        ) {
            if let share = model.pendingShare {
                ShareSheet(payload: share)
            }
        }
        #if DEBUG
        /*
         * `-AutoRecordingOverlay 1` — 백엔드 없이 녹음 화면 자체를 보는 통로다.
         *
         * 웹은 백엔드가 없으면 `GET /v0/tests`에서 멈춰 VOICE 문항을 그리지 못하고, 그러면
         * 브리지의 `startVoiceItem`이 영영 오지 않아 이 화면을 한 번도 볼 수 없다. 시뮬레이터에는
         * 탭을 넣을 방법도 없어서(`xcrun simctl`에 좌표 입력이 없다) 고정 payload를 실행 인자로
         * 흘려 넣는다 — 웹이 부르는 것과 **같은 함수**로 들어가므로 배선까지 함께 확인된다.
         * 마이크 권한은 미리 줘야 한다(권한이 없으면 게이트가 먼저 선다).
         */
        .task {
            guard UserDefaults.standard.bool(forKey: "AutoRecordingOverlay") else { return }
            model.onStartVoiceItem(
                VoiceItemStart(
                    itemId: "it_debug_overlay",
                    prompt: "오늘 날씨가 정말 좋네요",
                    itemNumber: 3,
                    totalItems: 10,
                    maxDurationMs: RecordingEngine.maxDurationMs
                )
            )
            /*
             * `-AutoRecordingDrive 1`을 함께 주면 녹음 버튼도 대신 눌러 세 화면(대기 → 녹음 중
             * → 확인)을 차례로 세운다. 사이를 넉넉히 벌리는 것은 화면 캡처가 끼어들 창을 주기
             * 위해서다 — 시뮬레이터에는 좌표 입력이 없어(`xcrun simctl`) 이 경로 말고는 녹음
             * 화면의 상태 변화를 한 번도 볼 수 없다.
             */
            guard UserDefaults.standard.bool(forKey: "AutoRecordingDrive") else { return }
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            recording.start()
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            recording.stop()
        }
        #endif
    }

    // MARK: 오버레이

    /// 안드로이드 `when { ... }` 사슬을 그대로 옮긴다. **순서가 규칙이다** — 앞 분기가 먼저
    /// 가져간 경우를 뒤 분기가 다시 적지 않는다.
    @ViewBuilder
    private var overlay: some View {
        // 시작 게이트 1칸 — 마이크 권한 (KAN-98). 통과 표시를 따로 두는 이유는 뒤에 세션 생성이
        // 이어지기 때문이다: 세션을 기다리는 동안 권한 화면으로 되돌아가면 안 된다.
        if model.startRequested, model.session == nil, !model.micPassed {
            PermissionGateView(onGranted: { model.onStartGateMicPassed() })

        // 시작 게이트 2칸 — 목소리 점검 (KAN-105). 중심 음높이를 받으면 조건이 풀린다.
        } else if model.startRequested, model.session == nil, model.micPassed, model.voiceCenterHz == nil {
            VoiceCheckScreen(model: voiceCheck) { centerHz in
                model.onVoiceCheckDone(centerHz: Double(centerHz))
            }

        // 시작 게이트 3칸 — 세션 생성 (KAN-34). 확보되면 테스트 URL이 로드되고 조건이 풀려
        // 이 화면이 사라진다.
        } else if model.startRequested, model.session == nil {
            SessionGateScreen(model: model, onBackToIntro: { model.backToIntro() })

        // 문항 진입 시점의 게이트 — 통과하면 기다리던 문항의 녹음으로 곧장 들어간다.
        } else if case .needsPermission = model.phase {
            PermissionGateView(onGranted: { model.onPermissionGranted() })

        } else if let start = overlayStart {
            RecordingOverlay(
                start: start,
                submitting: isSubmitting,
                afterUploadFailure: model.recordingAfterUploadFailure,
                failureMessage: model.recordingFailureMessage,
                recording: recording,
                onSubmit: { attemptId, durationMs, quality in
                    submitRecording(start: start, attemptId: attemptId, durationMs: durationMs, quality: quality)
                }
            )
        }
    }

    /// 녹음·제출 두 페이즈는 같은 화면을 쓰고 아래쪽만 다르다 (KAN-146).
    private var overlayStart: VoiceItemStart? {
        switch model.phase {
        case .recording(let recording): return recording.start
        case .submitting(let submitting): return submitting.start
        default: return nil
        }
    }

    private var isSubmitting: Bool {
        if case .submitting = model.phase { return true }
        return false
    }

    /// 제출 상한 타이머를 다시 걸어야 하는 조건 두 가지 (안드로이드의 키 두 개와 같다).
    private struct SubmitHoldKey: Equatable {
        let attemptId: String?
        let unbacked: Bool
    }

    // MARK: 결선

    /// 검토 화면의 [다음]. 안드로이드 `RecordingOverlay(onSubmit = ...)` 본문의 이식이다.
    @MainActor
    private func submitRecording(
        start: VoiceItemStart,
        attemptId: String,
        durationMs: Int64,
        quality: QualityStatus
    ) {
        // consumeRecording은 PCM을 넘기면서 모델에서 지운다 (FR-DP-02: 보관하지 않음).
        guard let pcm = recording.consumeRecording() else {
            /*
             * 올릴 바이트가 없으면 결과도 만들어질 수 없다. 시도로 등록하면 웹이 오지 않을
             * 결과를 기다리며 그 문항에 멈추므로, 등록 없이 돌려보내 [녹음 화면 다시 열기]로
             * 다시 녹음하게 한다 (KAN-147).
             */
            model.onRecordingExit()
            return
        }

        uploads.enqueue(
            UploadRequest(
                attemptId: attemptId,
                itemId: start.itemId,
                wavBytes: WavWriter.toWavBytes(pcm),
                durationMs: durationMs,
                clientQuality: AudioQuality.measure(pcm)
            ),
            label: "\(start.itemNumber)번 문항"
        )
        /*
         * 화면은 결과가 나갈 때까지 붙들되(Submitting) 진행은 업로드를 기다리지 않는다 —
         * 대기 시도는 여기서 바로 등록된다.
         *
         * 밀려난 앞 시도의 업로드는 여기서 폐기한다 (KAN-147). 새 업로드를 먼저 걸고 지우는
         * 순서라 attemptId가 겹치는 경우에도 방금 건 업로드가 살아남는다.
         */
        for superseded in model.onRecordingFinished(attemptId: attemptId, durationMs: durationMs, quality: quality) {
            uploads.discard(superseded)
        }
    }

    /// 녹음을 새로 해야 풀리는 실패를 걷고 그 문항의 녹음 화면을 다시 연다 (KAN-147, B안).
    ///
    /// 서버가 녹음 자체를 거절한 건(rerecord)만 여기로 온다 — 그것만이 재전송으로 풀리지 않아
    /// 복구 경로가 재녹음 하나뿐이다. 전송 실패는 [재시도]가 계속 서 있고, 그 외 서버 거절은
    /// 서버 문구를 단 실패 행으로 상태 바에 그대로 남는다.
    ///
    /// 폐기는 컨트롤러가 그 시도를 실제로 거둬갔을 때만 한다 — false는 이미 밀려났거나 모르는
    /// 시도라는 뜻이라, 그때 폐기하면 같은 키를 쓰는 다른 흐름의 상태를 건드릴 수 있다.
    @MainActor
    private func handleRerecordFailures() {
        for entry in uploads.entries {
            guard case .failed(let failure) = entry.state, failure.rerecord else { continue }
            // 서버 문구를 그대로 실어 보낸다 — 왜 다시 녹음해야 하는지는 서버만 안다.
            if model.onUploadGivenUp(attemptId: entry.attemptId, message: failure.message) {
                uploads.discard(entry.attemptId)
            }
        }
    }

    /// 오버레이가 걷히거나 다른 문항으로 넘어갈 때 녹음을 되감는다.
    @MainActor
    private func syncOverlayPhase(_ phase: TestFlowPhase) {
        if let shown = shownOverlayPhase, !continuesFrom(shown: shown, current: phase) {
            recording.reset()
        }
        switch phase {
        case .recording, .submitting: shownOverlayPhase = phase
        default: shownOverlayPhase = nil
        }
    }

    @MainActor
    private func deliverResults() {
        guard let webView else { return }
        for result in model.takeResultsForDelivery() {
            webView.evaluateJavaScript(itemResultDeliveryJs(result)) { handed, _ in
                // 주입 JS는 수신 지점이 실제로 있어 결과를 넘겼을 때만 true를 돌려준다 (KAN-146).
                // JS의 boolean은 `NSNumber`로 건너온다.
                guard (handed as? NSNumber)?.boolValue == true else { return }
                Task { @MainActor in model.onResultDelivered(attemptId: result.attemptId) }
            }
        }
    }

    @MainActor
    private func handleRetest() async {
        guard let failure = await model.startRetest() else { return }
        // 결과 화면은 그대로 살아 있다 — 왜 아무 일도 일어나지 않았는지 그 화면에 회신한다.
        webView?.evaluateJavaScript(retestFailedDeliveryJs(failure), completionHandler: nil)
    }
}

// MARK: - 녹음 오버레이

/// 녹음 화면 오버레이. 아래 WebView를 완전히 가린다 — WebView는 살아 있고 배경만 덮는 구조라,
/// 배경이 없으면 웹 화면이 그대로 비친다.
///
/// 전환에 애니메이션을 두지 않는다 (안드로이드와 같은 판단): 웹이 음성 문항을 먼저 그려야
/// 브리지가 호출되는 구조라 등장에 페이드를 걸면 그 대기 화면이 페이드 내내 비쳐, 없앨 수
/// 있던 노출을 되레 늘린다. 퇴장도 즉시다 — 걷히는 자리에는 이미 다음 문항이 그려져 있다.
private struct RecordingOverlay: View {

    let start: VoiceItemStart
    /// 제출한 시도의 결과를 기다리는 중 — 화면은 그대로 두고 하단만 바꾼다 (KAN-146).
    let submitting: Bool
    /// 업로드 재녹음 전환으로 이 화면이 스스로 다시 열렸는가 (KAN-147).
    let afterUploadFailure: Bool
    /// 그 전환에서 서버가 준 문구. nil이면 화면이 기본 안내를 쓴다.
    let failureMessage: String?

    @ObservedObject var recording: RecordingModel

    let onSubmit: (_ attemptId: String, _ durationMs: Int64, _ quality: QualityStatus) -> Void

    var body: some View {
        RecordingScreen(
            questionText: start.prompt,
            questionIndex: start.itemNumber,
            totalQuestions: start.totalItems,
            submitting: submitting,
            afterUploadFailure: afterUploadFailure,
            failureMessage: failureMessage,
            model: recording,
            onNext: onSubmit
        )
        // 화면이 걷히면 마이크를 놓는다. 되감기 판정(`continuesFrom`)은 상위가 하지만, 오버레이
        // 자체가 사라지는 경우(웹으로 돌아감)는 여기서도 한 번 더 막는다 — 상위의 페이즈 변화가
        // 오지 않는 경로(뷰 재구성)에서도 마이크는 반드시 닫혀야 한다.
        .onDisappear { recording.reset() }
    }
}

// MARK: - 공유 시트

/// 결과 공유 (KAN-30) — **iOS는 카카오 SDK를 쓰지 않는다.**
///
/// 안드로이드는 카카오 피드 템플릿(v2)으로 카톡을 직접 열고, 미설치면 OS 공유 시트로 내려간다
/// (`ResultSharer.kt`). iOS 쪽 카카오 링크 SDK 도입은 KAN-30의 안드로이드 범위 밖이라 이 티켓의
/// 일이 아니고, 여기서는 안드로이드의 **폴백 경로에 해당하는 것**만 세운다 —
/// `UIActivityViewController`에 카카오톡이 설치돼 있으면 그 항목이 그대로 뜬다.
///
/// 카드 문구와 링크는 웹이 실어 보낸 값 그대로다(서버가 정한다). 이미지 URL은 시트에 싣지
/// 않는다 — `UIActivityViewController`에 원격 URL을 이미지로 주면 다운로드가 끝날 때까지
/// 시트가 멈추고, 받는 쪽에서 무엇으로 보일지도 앱마다 갈린다. 카드 그림을 실어야 하는
/// 정식 경로는 카카오 템플릿이고 그건 후속 티켓이다.
private struct ShareSheet: UIViewControllerRepresentable {

    let payload: SharePayload

    func makeUIViewController(context: Context) -> UIActivityViewController {
        var items: [Any] = [payload.text]
        if let url = URL(string: payload.webTestUrl) { items.append(url) }
        return UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
