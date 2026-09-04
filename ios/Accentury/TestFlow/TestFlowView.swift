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
///
/// ## 종이색은 화면 끝까지 간다
///
/// 내용(WebView·오버레이·상태 바)은 안전 영역 **안쪽**에 그대로 두고, 배경만
/// `ignoresSafeArea()`로 상태 바·홈 인디케이터 자리까지 넓힌다 — 자세한 이유는 `body`의 주석.
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

    /// OS 공유 시트에 실을 카드 (KAN-180). ``TestFlowModel/pendingShare``와 다른 값이다 —
    /// 그쪽은 "웹이 공유를 요청했다"이고, 이건 "카카오로 못 가서 시트로 내려왔다"다.
    /// 통로 판정이 끝난 뒤에만 채워지므로, 카카오로 나간 공유는 여기까지 오지 않는다.
    @State private var sheetShare: SharePayload?

    /// 앱 안 이벤트가 나가는 창구 하나 (KAN-33). 안드로이드 `MainActivity`의
    /// `remember { EventSink.create(context) }` 자리다 — 웹이 브리지로 보낸 것도, 네이티브 화면이
    /// 직접 세는 것도(공유·재녹음) 전부 여기로 모인다. 같은 사건이 두 경로로 가지 않게 하는 것이
    /// 이 창구가 하나뿐이라는 사실 자체다.
    ///
    /// `@StateObject`가 아니라 값인 이유: 고른 sink는 상태가 없다 (``FirebaseEventSink``). 뷰 값이
    /// 다시 만들어질 때마다 새로 골라도 같은 판정이 나오고, 판정 자체는 ``FirebaseSetup``이 앱
    /// 시작에 한 번 끝내 둔 값을 읽을 뿐이다.
    private let events: EventSink = makeEventSink()

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
                    /*
                     * 웹이 센 사건을 앱 스트림으로 넘긴다 (KAN-33). 이름을 여기서 손대지 않는 것이
                     * 요점이다 — 웹과 앱이 같은 이름으로 쌓여야 하나의 퍼널이 되고, 그 정본은
                     * `web/src/analytics/events.ts` 하나다. 값 검증은 브리지가 이미 끝냈다.
                     */
                    onLogEvent: { name, params in events.log(name, params) },
                    onWebViewCreated: { created in
                        webView = created
                        #if DEBUG
                        smokeLog("WEBVIEW: created \(ObjectIdentifier(created))")
                        #endif
                    },
                    // 내가 들고 있는 인스턴스일 때만 놓는다 — 재생성 순서에 따라 새 WebView가 먼저
                    // 등록된 뒤 옛 것이 해제될 수 있고, 그때 방금 받은 참조를 지우면 안 된다.
                    onWebViewReleased: { released in
                        #if DEBUG
                        smokeLog("WEBVIEW: released \(ObjectIdentifier(released)) held=\(webView.map { "\(ObjectIdentifier($0))" } ?? "nil")")
                        #endif
                        if webView === released { webView = nil }
                    }
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
         * 종이색을 화면 끝까지 민다.
         *
         * SwiftUI는 내용을 안전 영역 안쪽에 놓으므로, 이 `VStack`은 상태 바(다이내믹 아일랜드)와
         * 홈 인디케이터 자리를 비워 둔다. 그 자리를 아무도 칠하지 않으면 창의 기본 배경인 **흰색**이
         * 그대로 드러나, 웹 화면(인트로·문항·결과)에서만 위아래에 흰 띠가 생긴다. 네이티브 화면들이
         * 이미 각자 `Papercut.cream.ignoresSafeArea()`를 깔고 있어 녹음 오버레이·로딩 화면에서는
         * 안 보이던 증상이다.
         *
         * 넓히는 것은 **배경뿐이다.** 내용까지 안전 영역 밖으로 내보내면 웹 화면의 첫 줄이
         * 다이내믹 아일랜드 밑으로 들어가고 하단 버튼이 홈 인디케이터에 물린다. WebView 자체도
         * 안전 영역 안에 그대로 둔다 — 웹 레이아웃은 인셋을 모르는 채 짜여 있다.
         *
         * 안드로이드의 같은 자리는 `MainActivity`의 `Scaffold`다 (KAN-161 4단계): 창은
         * `enableEdgeToEdge()`로 시스템 바 밑까지 열어 두고, 크림
         * (`colorScheme.background`)이 창 전체를 칠하며, 내용은 `innerPadding`으로 인셋
         * 안쪽에 앉는다. 첫 프레임의 시스템 바 아이콘 색은 `themes.xml`이 맡는다.
         */
        .background(Papercut.cream.ignoresSafeArea())
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
         * 공유 링크로 앱이 열렸다 (KAN-32 3단계). 안드로이드 `MainActivity`의
         * `onCreate`/`onNewIntent` + `applyAppLink` 자리다.
         *
         * iOS에서 Universal Link는 Intent가 아니라 `NSUserActivity`로 온다. 앱이 살아 있을 때
         * 눌린 링크는 물론 콜드 스타트도 이 한 자리로 들어온다 — SwiftUI가 씬이 붙은 뒤에
         * 배달해 주기 때문에 안드로이드처럼 `onCreate`와 `onNewIntent`로 갈리지 않는다.
         *
         * 그 대신 콜드 스타트에서는 첫 로드가 코드 없이 한 번 나간 뒤 URL이 바뀌어 다시
         * 로드된다(안드로이드는 `setContent` 전에 읽어 이 리로드가 없다). 인트로 구간이라
         * 잃을 진행이 없어 그대로 둔다.
         */
        .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
            model.applyAppLink(activity.webpageURL)
        }
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
         *
         * `!= .active`가 아니라 `== .background`인 이유 (PR #62 리뷰): `.inactive`는 제어 센터
         * 내리기·앱 전환기 열기·전화 배너에서도 오는 "잠깐 가려짐"이라, 거기서 버리면 10초
         * 녹음이나 검토 중 PCM이 안내 없이 사라진다. 홈으로 실제로 나가면 `.active → .inactive
         * → .background` 순서로 반드시 `.background`를 지나므로, 좁혀도 "앱 전환 시 마이크
         * 해제" AC는 그대로 지켜진다.
         */
        .onChange(of: scenePhase) { phase in
            if phase == .background { recording.reset() }
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
        // 결과 공유 (KAN-30, KAN-180). 웹이 공유를 요청하면 통로부터 정한다 — 카톡이면
        // 카카오 카드가 나가고, 아니면 아래 시트가 뜬다 (`routeShare`).
        .onChange(of: model.pendingShare) { payload in routeShare(payload) }
        .sheet(
            isPresented: Binding(
                get: { sheetShare != nil },
                set: { if !$0 { sheetShare = nil } }
            )
        ) {
            if let share = sheetShare {
                ShareSheet(payload: share)
            }
        }
        #if DEBUG
        /*
         * `-AppLinkURL "http://localhost:5173/t?c=kko_share"` — 링크 진입 경로를 그대로 밟아 본다.
         *
         * AASA(`/.well-known/apple-app-site-association`)가 각 호스트에 서빙되기 전에는
         * (KAN-32 4단계) 시뮬레이터가 Universal Link를 앱으로 넘기지 못한다 —
         * `xcrun simctl openurl`은 사파리만 연다. 그래서 실제 링크 탭이 부르는 것과 **같은 함수**로
         * URL을 흘려 넣는다. 배선(코드 → 진입 URL의 `&c=` → 세션 바디)까지 함께 확인된다.
         *
         * 디버그 `WEB_URL`이 App Link origin 목록에 더해지는 것이 이 인자가 성립하는 근거다
         * (`AccenturyCore/appLinkOrigins(webUrl:)`).
         */
        .task {
            guard let url = UserDefaults.standard.string(forKey: "AppLinkURL"), !url.isEmpty else { return }
            model.applyAppLink(URL(string: url))
        }
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
                    maxDurationMs: RecordingEngine.maxDurationMs,
                    guideF0: debugGuideF0
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
        /*
         * `-AutoFlowDrive 1` — 통합 스모크의 **네이티브 절반** (§8).
         *
         * `-AutoRecordingDrive`와 갈리는 지점은 "몇 번인가"다. 그쪽은 고정 payload로 세운 화면
         * 하나를 시간에 맞춰 밀지만, 통합 스모크에서는 녹음 오버레이가 음성 문항 수만큼 뜨고
         * 언제 뜨는지는 서버가 준 정의가 정한다. 그래서 시간이 아니라 **페이즈 변화**를 듣는다.
         *
         * 정지도 누르지 않는다. 가짜 마이크의 WAV가 끝나면 캡처 스트림이 닫혀 엔진이 스스로
         * 검토로 넘어가므로(`FilePcmSource`), 정지를 걸면 그 자연스러운 종료 대신 사람이 끊은
         * 녹음을 재게 된다 — 안드로이드 캡처와 나란히 놓을 값이 아니게 된다.
         */
        .onChange(of: model.phase) { phase in autoFlowDrive(phase) }
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
                // 사용자 곡선의 y축 중심 (KAN-105). 목소리 점검이 잰 값을 모든 문항이 함께
                // 쓴다 — 문항마다 다시 잡으면 같은 사람의 곡선이 문항마다 다른 축에 놓인다.
                centerHz: model.voiceCenterHz.map { Float($0) },
                recording: recording,
                /*
                 * 네이티브 녹음 화면의 [재녹음] (KAN-33). 웹 녹음기가 세는 것과 같은 사건이라
                 * 이름·파라미터를 그대로 맞춘다 — 앱 사용자의 재녹음만 다른 지표로 갈리면 문항
                 * 난이도를 두 표본으로 나눠 보게 된다.
                 *
                 * 사유가 USER 하나인 이유는 이 자리가 실패 없이 사용자가 다시 읽기로 한 지점이라서다.
                 * 서버가 되돌려보낸 재녹음(QUALITY·FAILED)은 웹의 분석 대기 화면이 소유하고 거기서
                 * 이미 센다.
                 */
                onRetake: {
                    events.log(
                        RecordingEvents.retake,
                        [
                            // 사람이 읽는 1-기반 번호다 (웹 `item_seq`와 같은 값).
                            RecordingEvents.paramItemSeq: .count(Int64(start.itemNumber)),
                            RecordingEvents.paramReason: .text(RecordingEvents.reasonUser),
                        ]
                    )
                },
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

    /// 웹이 요청한 공유를 통로로 보낸다 (KAN-180).
    ///
    /// 모델의 대기 값을 **먼저** 비우는 이유는 이 값이 "요청이 도착했다"는 신호이지 화면 상태가
    /// 아니어서다. 시트가 뜨는 경우에는 `sheetShare`가 그 자리를 이어받고, 카카오로 나가는
    /// 경우에는 띄울 화면 자체가 없다 — 비우지 않으면 카카오로 나간 뒤에도 대기 값이 남아
    /// 다음 공유 요청이 같은 payload일 때 `onChange`가 울리지 않는다.
    ///
    /// ``ResultSharer``를 여기서 만든다: 시트를 띄우는 건 SwiftUI 상태를 건드리는 일이라
    /// 그쪽이 직접 할 수 없고, 화면이 넘겨주는 클로저 하나로 끝난다.
    @MainActor
    private func routeShare(_ payload: SharePayload?) {
        guard let payload else { return }
        model.consumeShare()
        /*
         * 탭은 여기서 세지 않는다 (FR-SH-06). 그 한 건은 웹이 `share_clicked`로 이미 세고, 앱
         * 안에서는 브리지 `logEvent`를 타고 같은 sink로 들어온다 — 네이티브가 이름을 하나 더
         * 붙이면 같은 탭이 앱과 웹에서 다른 축으로 갈린다. 네이티브가 세는 것은 통로가 실제로
         * 열린 일뿐이고, 그쪽은 ``ResultSharer``가 통로를 붙여 울린다. 클릭 수와 실행 수의
         * 차이는 그대로 "눌렀는데 아무 데도 못 간" 비율이다.
         */
        ResultSharer.forApp(
            presentSheet: { sheetShare = $0 },
            // 띄운 통로만 싣는다. 세션·점수는 익명 규칙에서 제외 대상이다 (``EventSink``).
            onLaunched: { channel in
                events.log(ShareEvents.launched, [ShareEvents.paramChannel: .text(channelParam(channel))])
            }
        ).share(payload)
    }

    @MainActor
    private func deliverResults() {
        guard let webView else {
            #if DEBUG
            smokeLog("RESULT: deliver skipped — webView 없음")
            #endif
            return
        }
        let pending = model.takeResultsForDelivery()
        #if DEBUG
        smokeLog("RESULT: deliver n=\(pending.count)")
        #endif
        for result in pending {
            webView.evaluateJavaScript(itemResultDeliveryJs(result)) { handed, error in
                // 주입 JS는 수신 지점이 실제로 있어 결과를 넘겼을 때만 true를 돌려준다 (KAN-146).
                // JS의 boolean은 `NSNumber`로 건너온다.
                let accepted = (handed as? NSNumber)?.boolValue == true
                #if DEBUG
                /*
                 * 결과가 웹에 실제로 닿았는지는 흐름이 멈췄을 때 가장 먼저 묻게 되는 값인데,
                 * 여기 로그가 없으면 «업로드는 됐는데 화면이 안 넘어간다»에서 원인이 네이티브
                 * 쪽인지 웹 수신 지점인지 가를 방법이 없다 (KAN-108 §8에서 실제로 겪었다).
                 */
                smokeLog(
                    "RESULT: attempt=\(result.attemptId) item=\(result.itemId) accepted=\(accepted)"
                        + (error.map { " error=\($0.localizedDescription)" } ?? "")
                )
                #endif
                guard accepted else { return }
                Task { @MainActor in model.onResultDelivered(attemptId: result.attemptId) }
            }
        }
    }

    #if DEBUG
    /// 지금 구동 중인 문항. 녹음 오버레이가 걷히면 비워서, 같은 문항이 다시 열려도
    /// (업로드 실패 → 재녹음, KAN-147) 한 번 더 구동된다.
    @State private var autoDrivenItem: String?

    /// 녹음 오버레이가 뜰 때마다 [녹음] → (가짜 마이크가 끝날 때까지) → [다음]을 대신 누른다.
    @MainActor
    private func autoFlowDrive(_ phase: TestFlowPhase) {
        guard UserDefaults.standard.bool(forKey: "AutoFlowDrive") else { return }
        guard case .recording(let recordingPhase) = phase else {
            // 제출을 기다리는 동안에는 열쇠를 쥐고 있는다 — 여기서 비우면 같은 문항이 다시
            // 구동돼 이미 올라간 녹음 위에 두 번째 시도가 겹친다.
            if case .submitting = phase { return }
            autoDrivenItem = nil
            return
        }

        let start = recordingPhase.start
        guard autoDrivenItem != start.itemId else { return }
        autoDrivenItem = start.itemId

        Task { @MainActor in
            smokeLog("FLOW: recording overlay item=\(start.itemId) number=\(start.itemNumber)/\(start.totalItems) → [녹음]")
            // 오버레이가 자리를 잡을 짬. 화면 캡처가 «대기» 상태를 잡을 창이기도 하다.
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            recording.start()

            /*
             * 검토로 넘어가기를 기다린다. 상한은 녹음 상한(10초)에 여유를 얹은 값이다 —
             * 가짜 마이크가 안 물려 있으면 실제 마이크로 10초를 채우고 넘어오는데, 그것도
             * 결국은 넘어오므로 상한이 그보다 짧으면 스모크만 헛되이 실패한다.
             */
            let deadline = Date().addingTimeInterval(20)
            while Date() < deadline {
                switch recording.uiState {
                case .review(let review):
                    smokeLog(
                        "FLOW: review item=\(start.itemId) duration=\(review.durationMs)ms "
                            + "quality=\(review.quality) autoStopped=\(review.autoStopped) "
                            + "frames=\(review.pitchFrames.count)"
                    )
                    guard review.canProceed else {
                        // 화면의 [다음]도 이 조건으로 서지 않는다 (FR-AD-08). 여기서 억지로
                        // 제출하면 스모크가 사람이 못 하는 일을 해 버려 통과가 거짓말이 된다.
                        smokeLog("FLOW: [다음] 잠김 — 품질 \(review.quality), 이 문항에서 멈춘다")
                        return
                    }
                    // 검토 화면을 캡처할 창.
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    smokeLog("FLOW: review item=\(start.itemId) attempt=\(review.attemptId) → [다음]")
                    submitRecording(
                        start: start,
                        attemptId: review.attemptId,
                        durationMs: review.durationMs,
                        quality: review.quality
                    )
                    return

                case .failed(let reason):
                    smokeLog("FLOW: recording failed item=\(start.itemId) reason=\(reason)")
                    return

                case .idle, .recording:
                    try? await Task.sleep(nanoseconds: 200_000_000)
                }
            }
            smokeLog("FLOW: recording drive timeout item=\(start.itemId) state=\(recording.uiState)")
        }
    }
    #endif

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

    /// 사용자 곡선 y축의 중심 음높이 (KAN-105). 목소리 점검이 잰 값이고, 아직 없으면 nil이다.
    let centerHz: Float?

    @ObservedObject var recording: RecordingModel

    /// 검토 화면의 [재녹음]을 눌렀다 (KAN-33 계측). 되감기 자체는 화면이 ``RecordingModel``에 직접 건다.
    let onRetake: () -> Void

    let onSubmit: (_ attemptId: String, _ durationMs: Int64, _ quality: QualityStatus) -> Void

    var body: some View {
        RecordingScreen(
            questionText: start.prompt,
            questionIndex: start.itemNumber,
            totalQuestions: start.totalItems,
            submitting: submitting,
            afterUploadFailure: afterUploadFailure,
            failureMessage: failureMessage,
            // 가이드 곡선은 문항 payload가 실어 온 그대로다 (KAN-102). 없으면 위 레인만 빈다.
            guideF0: start.guideF0,
            centerHz: centerHz,
            model: recording,
            onRetake: onRetake,
            onNext: onSubmit
        )
        // 화면이 걷히면 마이크를 놓는다. 되감기 판정(`continuesFrom`)은 상위가 하지만, 오버레이
        // 자체가 사라지는 경우(웹으로 돌아감)는 여기서도 한 번 더 막는다 — 상위의 페이즈 변화가
        // 오지 않는 경로(뷰 재구성)에서도 마이크는 반드시 닫혀야 한다.
        .onDisappear { recording.reset() }
    }
}

// MARK: - 공유 시트

/// 결과 공유의 **폴백 통로** (KAN-30, KAN-180). 정식 경로는 카카오 피드 템플릿이고
/// (`Share/ResultSharer.swift`), 여기는 그쪽이 막혔을 때만 뜬다 — 앱 키가 없거나, 카톡이
/// 안 깔렸거나, 카카오·카톡 전환이 실패한 경우다. 안드로이드 `buildSystemShareIntent`와 같은 자리다.
///
/// 카톡이 깔린 기기라면 이 시트에도 카카오톡 항목이 뜨지만 그건 텍스트 한 줄이 가는 경로다.
/// 카드가 필요하면 카카오 경로여야 한다.
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

#if DEBUG
/// `-AutoRecordingOverlay 1`이 실어 보내는 가이드 곡선 (KAN-108 §7b).
///
/// 실제 정의(KAN-17 산출물)를 가져올 수 없는 자리라 **모양만 흉내 낸 합성 곡선**이다 —
/// 서술문 억양처럼 앞머리에서 올랐다 문장 끝으로 내려오고, 어절 사이 두 곳이 무성(nil)이라
/// 가이드의 무성 보간(``AccenturyCore/guideCurveDisplayPoints(_:)``)까지 화면에서 확인된다.
/// 값이 아니라 배선과 렌더를 보는 데이터이므로 실제 발화에서 뽑을 이유가 없다.
///
/// 1.2초(10ms × 120)는 시드 문항의 길이 범위(0.9~1.2초) 위쪽이다. 사용자 창은 그 2배인
/// 2.4초가 되어(``AccenturyCore/userCurveWindowMs(frameIntervalMs:valueCount:)``) 가짜 마이크
/// WAV 2.5초가 거의 그대로 들어온다.
private var debugGuideF0: GuideF0 {
    let count = 120
    let values: [Double?] = (0..<count).map { index in
        // 어절 사이 무성 구간 둘. 90ms·70ms라 실제 자음 구간과 같은 자릿수다.
        if (38...46).contains(index) || (78...84).contains(index) { return nil }
        let t = Double(index) / Double(count - 1)
        // 올라갔다 내려오는 봉우리(sin) 위에 문장 끝으로 향하는 하강(-4t)을 얹는다.
        // 등락 폭이 9.6 semitone 남짓이라 실측 발화의 최대치와 같은 범위다.
        return 3.5 * sin(t * .pi * 1.6) - 4.0 * t + 1.0
    }
    return GuideF0(unit: "semitone", frameIntervalMs: 10, values: values)
}
#endif
