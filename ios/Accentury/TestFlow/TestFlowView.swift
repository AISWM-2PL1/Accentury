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
/// ## 이 단계에서 자리 표시로 남는 것 (KAN-108 §6)
///
/// 목소리 점검·세션 게이트 화면·녹음 화면·업로드는 6단계 몫이다. 그 자리에는 **무엇이
/// 빠졌는지 화면에 적힌** 자리 표시 뷰를 둔다 — 아무것도 없으면 흐름이 조용히 멈춘 것처럼
/// 보여서, 6단계 이전의 스모크에서 "어디까지 왔는지"를 눈으로 읽을 수 없다.
struct TestFlowView: View {

    @StateObject private var model = TestFlowModel()

    /// 결과를 웹에 넣으려면 `evaluateJavaScript`를 부를 인스턴스가 필요하다.
    /// 로드 실패 화면·재시도 구간에는 WebView가 아예 없으므로 옵셔널이다.
    @State private var webView: WKWebView?

    var body: some View {
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
            VoiceCheckPlaceholder(onDone: { model.onVoiceCheckDone(centerHz: $0) })

        // 시작 게이트 3칸 — 세션 생성 (KAN-34). 확보되면 테스트 URL이 로드되고 조건이 풀려
        // 이 화면이 사라진다.
        } else if model.startRequested, model.session == nil {
            SessionGatePlaceholder(model: model)

        // 문항 진입 시점의 게이트 — 통과하면 기다리던 문항의 녹음으로 곧장 들어간다.
        } else if case .needsPermission = model.phase {
            PermissionGateView(onGranted: { model.onPermissionGranted() })

        } else if let start = overlayStart {
            RecordingOverlayPlaceholder(
                start: start,
                submitting: isSubmitting,
                onExit: { model.onRecordingExit() }
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

// MARK: - 6단계 자리 표시 화면

/// 목소리 점검 (KAN-105)의 자리. 실제 화면은 6단계에서 온다.
// TODO(KAN-108 §6): 안드로이드 `VoiceCheckScreen` + `VoiceCheckViewModel` 이식본으로 교체.
private struct VoiceCheckPlaceholder: View {

    let onDone: (Double) -> Void

    var body: some View {
        PlaceholderScreen(
            title: "목소리 점검",
            detail: "6단계에서 붙습니다 (KAN-105 이식)",
            // 점검이 잰 중심 음높이는 이후 모든 문항의 곡선 축이 된다. 여기서 넣는 값은
            // 흐름을 끝까지 밀어 보기 위한 것이지 측정값이 아니라, 디버그 빌드에만 둔다.
            debugAction: ("점검을 지난 것으로 두기", { onDone(0) })
        )
        #if DEBUG
        // `-AutoGateSmoke 1` — 위 버튼을 실행 인자로 대신 누른다. 시뮬레이터에는 좌표 입력이
        // 없어서(`xcrun simctl`) 자리 표시 화면을 손으로 넘길 방법이 없다.
        .task {
            guard UserDefaults.standard.bool(forKey: "AutoGateSmoke") else { return }
            onDone(0)
        }
        #endif
    }
}

/// 세션 생성 (KAN-34)의 자리. Debug 빌드는 ``DebugStubSessionClient``가 고정 세션을 즉시 줘서
/// 웹이 `?screen=test`로 넘어가는 것까지 확인된다. Release에는 클라이언트가 없어 여기서 멈춘다.
// TODO(KAN-108 §6): 안드로이드 `SessionGateScreen` + `OkHttpSessionClient` 이식본으로 교체.
private struct SessionGatePlaceholder: View {

    @ObservedObject var model: TestFlowModel

    var body: some View {
        Group {
            switch model.gateState {
            case .creating:
                PlaceholderScreen(title: "테스트를 준비하고 있어요", detail: "세션 생성 (6단계)")

            case .failed(let reason, let retryAfterSeconds):
                PlaceholderScreen(
                    title: "테스트를 시작하지 못했어요",
                    detail: failureDetail(reason: reason, retryAfterSeconds: retryAfterSeconds),
                    primary: ("다시 시도", { model.retrySession() }),
                    secondary: ("처음으로", { model.backToIntro() })
                )

            case .ready:
                // 이 화면이 서 있는 조건 자체가 session == nil이라 도달하지 않는다.
                EmptyView()
            }
        }
        // 게이트가 화면에 서 있는 동안 요청을 건다. attempt가 바뀌면(=[다시 시도]) 다시 건다 —
        // 상태만 되돌리면 이미 한 번 돈 이펙트가 다시 돌 이유가 없어 준비 중인 채로 멈춘다.
        .task(id: model.gateAttempt) { await model.createSessionIfNeeded() }
    }

    /// 갈래별 안내. 안드로이드 `SessionGateScreen`의 문구 자리이고, 6단계에서 그 화면이 정본을 가져간다.
    private func failureDetail(reason: SessionFailureReason, retryAfterSeconds: Int64?) -> String {
        switch reason {
        case .rateLimited:
            if let retryAfterSeconds {
                return "접속이 몰리고 있어요 · \(retryAfterSeconds)초 뒤에 다시 시도해 주세요"
            }
            return "접속이 몰리고 있어요 · 잠시 뒤에 다시 시도해 주세요"
        case .network:
            return "연결이 불안정해요 · 네트워크를 확인하고 다시 시도해 주세요"
        case .server:
            return "잠시 뒤에 다시 시도해 주세요"
        case .unsupported:
            return "앱을 최신 버전으로 업데이트해 주세요"
        }
    }
}

/// 녹음 화면 (KAN-100·KAN-102·KAN-146)의 자리. 실제 화면은 6단계에서 온다.
// TODO(KAN-108 §6): 안드로이드 `RecordingScreen` + `RecordingViewModel` 이식본으로 교체.
private struct RecordingOverlayPlaceholder: View {

    let start: VoiceItemStart
    /// 제출한 시도의 결과를 기다리는 중 — 화면은 그대로 두고 하단만 바꾼다 (KAN-146).
    let submitting: Bool
    let onExit: () -> Void

    var body: some View {
        PlaceholderScreen(
            title: start.prompt,
            detail: "\(start.itemNumber)/\(start.totalItems) · "
                + (submitting ? "결과를 보내는 중 (6단계)" : "녹음 화면 (6단계)"),
            // 안드로이드의 [나가기]는 KAN-147에서 없앴다. 여기 있는 것은 그 버튼이 아니라
            // "결과 없이 돌려보내는" 경로(PCM 없는 제출)를 손으로 태우는 디버그 통로다.
            debugAction: submitting ? nil : ("결과 없이 돌아가기", onExit)
        )
    }
}

/// 자리 표시 화면 한 장. 팔레트·간격은 `PermissionGateView`와 같은 토큰(Papercut)을 쓴다 —
/// 6단계에서 진짜 화면이 오면 이 파일과 함께 사라진다.
private struct PlaceholderScreen: View {

    let title: String
    let detail: String
    var primary: (String, () -> Void)?
    var secondary: (String, () -> Void)?
    var debugAction: (String, () -> Void)?

    var body: some View {
        ZStack {
            Papercut.cream.ignoresSafeArea()
            VStack(spacing: Papercut.space4) {
                Spacer(minLength: 0)

                Text(title)
                    .font(.system(size: 24))
                    .foregroundColor(Papercut.ink)
                    .multilineTextAlignment(.center)
                Text(detail)
                    .font(.system(size: 15))
                    .foregroundColor(Papercut.muted)
                    .multilineTextAlignment(.center)

                Spacer(minLength: 0)

                if let primary {
                    button(primary.0, filled: true, action: primary.1)
                }
                if let secondary {
                    button(secondary.0, filled: false, action: secondary.1)
                }
                #if DEBUG
                if let debugAction {
                    button("[디버그] \(debugAction.0)", filled: false, action: debugAction.1)
                }
                #endif
            }
            .padding(Papercut.space6)
        }
    }

    private func button(_ label: String, filled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 18))
                .foregroundColor(filled ? Papercut.cream : Papercut.ink)
                .frame(maxWidth: .infinity)
                .frame(height: Papercut.controlHeightLarge)
                .background(
                    RoundedRectangle(cornerRadius: Papercut.radiusMD)
                        .fill(filled ? Papercut.ink : Papercut.cream)
                        .overlay(
                            RoundedRectangle(cornerRadius: Papercut.radiusMD)
                                .stroke(Papercut.ink, lineWidth: filled ? 0 : 1)
                        )
                )
        }
        .buttonStyle(.plain)
    }
}
