import AccenturyCore
import Combine
import SwiftUI
import WebKit
#if DEBUG
import os
#endif

// MARK: - 순수 판정

/// 내비게이션 한 건을 허용할지 (§7). `WKNavigationActionPolicy`를 그대로 쓰지 않고 한 겹 두는
/// 이유는 이 판정이 보안 경계이기 때문이다 — WebKit 없이 직접 검증할 수 있어야 한다.
enum WebNavigationDecision: Equatable {
    case allow
    case cancel
}

/// allowlist 밖 URL은 로드 자체를 막는다 (§7). 브리지가 마이크 권한 게이트를 호출하므로
/// 이 검사가 곧 보안 경계다 — 임의 원격 페이지가 우리 권한 요청을 트리거하면 안 된다.
///
/// 안드로이드 `shouldOverrideUrlLoading`이 `!isAllowedWebUrl(...)`을 돌려주는 자리와 같은 판정이다.
/// `http(s)`가 아닌 스킴(`javascript:`·`tel:`·`mailto:`·앱 스킴)은 ``AccenturyCore/webOrigin(_:)``이
/// nil을 돌려줘 자동으로 걸린다.
///
/// 외부 링크를 여는 길은 아직 없다. 안드로이드가 "생기면 Custom Tabs로"라고 적어 둔 자리이고,
/// iOS의 대응물은 `SFSafariViewController`다 — 인트로·결과 화면에 외부 링크가 생기는 티켓에서 붙인다.
func navigationDecision(url: String?, allowedOrigins: Set<String>) -> WebNavigationDecision {
    isAllowedWebUrl(url, allowedOrigins: allowedOrigins) ? .allow : .cancel
}

/// 이 응답을 메인 프레임 오류로 볼 것인가 (§6).
///
/// 안드로이드는 `onReceivedHttpError(request.isForMainFrame)`가 이 판정을 대신 해 준다.
/// WKWebView에는 그 콜백이 없어서 `decidePolicyFor navigationResponse`에서 상태 코드를 직접 본다 —
/// 서브리소스 하나가 404라고 화면 전체를 접지 않는다는 규칙은 그대로다.
func mainFrameHttpErrorDecision(status: Int, isMainFrame: Bool) -> Bool {
    isMainFrame && status >= 400
}

/// 우리가 스스로 끊은 내비게이션인가.
///
/// `decisionHandler(.cancel)`을 부르면 WebKit이 곧바로 실패 콜백을 쏜다
/// (`NSURLErrorCancelled`, 정책 취소면 `WebKitErrorDomain` 102). 그걸 실패로 접으면 allowlist
/// 밖 링크를 한 번 막을 때마다 화면이 오류로 뒤집힌다. 안드로이드에서는
/// `shouldOverrideUrlLoading`이 true를 돌려줘도 `onReceivedError`가 오지 않아 이 구분이
/// 필요 없었다 — WebKit은 취소도 오류로 통지한다.
func isSelfInflictedCancellation(_ error: Error) -> Bool {
    let nsError = error as NSError
    if nsError.domain == NSURLErrorDomain, nsError.code == NSURLErrorCancelled { return true }
    // WebKitErrorFrameLoadInterruptedByPolicyChange. 상수가 공개 헤더에 없어 값을 적는다.
    if nsError.domain == "WebKitErrorDomain", nsError.code == 102 { return true }
    return false
}

/// 이 실패 통지를 메인 프레임 로드 실패로 볼 것인가 (§6).
///
/// 안드로이드는 `onReceivedError(request.isForMainFrame)` 한 줄로 끝난다 — 그쪽 콜백은
/// 서브리소스·서브프레임 실패까지 전부 받기 때문이다. WKWebView의 대응물에는 그 플래그가 없고,
/// 대신 실패한 **내비게이션 객체**가 온다. 그래서 판정을 "지금 기다리는 메인 프레임 로드가
/// 이것인가"로 바꾼다.
///
/// 이 형태가 잡아주는 것이 하나 더 있다: **밀려난 로드의 뒤늦은 실패**. 인트로를 로드하던 중
/// 테스트 진입 URL로 갈아타면 앞 로드가 취소되며 실패를 쏘는데, 그걸 그대로 접으면 방금 시작한
/// 정상 로드 위로 오류 화면이 덮인다. 값을 비교하지 않으면 이 창이 열린 채로 남는다.
///
/// - Parameters:
///   - navigation: 실패를 통지받은 내비게이션의 신원. 통지에 객체가 없으면 nil이다.
///   - currentMainFrame: 지금 진행 중인 메인 프레임 로드의 신원. 없으면 nil —
///     기다리는 로드가 없다는 뜻이라 무엇이 실패했든 화면을 뒤집을 근거가 아니다.
func shouldReportMainFrameFailure(
    navigation: ObjectIdentifier?,
    currentMainFrame: ObjectIdentifier?,
    error: Error
) -> Bool {
    if isSelfInflictedCancellation(error) { return false }
    guard let currentMainFrame, let navigation else { return false }
    return navigation == currentMainFrame
}

// MARK: - 상태 보유자

/// ``AccenturyCore/WebLoadController``(순수 상태 머신)를 SwiftUI가 볼 수 있는 `@Published`로 감싼다.
/// `PermissionGateModel`이 `MicPermissionController`에 하는 일과 같다 — Core는 Combine·SwiftUI를 모른다.
@MainActor
final class WebLoadModel: ObservableObject {

    @Published private(set) var state: WebLoadState = .loading

    /// 재시도 횟수이자 WebView 재생성 키. 안드로이드 `key(controller.attempt)`가 하는 일을
    /// SwiftUI에서는 `.id(attempt)`가 한다 — 값이 바뀌면 뷰가 통째로 새로 만들어진다.
    @Published private(set) var attempt: Int = 0

    private let controller = WebLoadController()

    func onPageFinished() { controller.onPageFinished(); sync() }
    func onMainFrameError() { controller.onMainFrameError(); sync() }
    func onTimeout() { controller.onTimeout(); sync() }
    func onNavigationStarted() { controller.onNavigationStarted(); sync() }
    func retry() { controller.retry(); sync() }

    private func sync() {
        if state != controller.state { state = controller.state }
        if attempt != controller.attempt { attempt = controller.attempt }
    }
}

// MARK: - 호스트

/// 원격 전용 WKWebView 호스트 (webview-layer.md §3·§6·§7). 안드로이드 `WebViewHost.kt`의 이식본이다.
///
/// 사파리 기본 오류 페이지를 사용자에게 절대 노출하지 않는다 — 실패가 감지되면 WebView를 통째로
/// 걷어내고 네이티브 오류 화면으로 바꾼다. 오프라인 동작이 목표가 아니라(테스트 자체가 서버 필수)
/// "실패의 질"이 목표다.
///
/// ``url``이 바뀌면 같은 WebView에서 이어 로드한다 (인트로 → 테스트 진입, KAN-100).
struct WebViewHost: View {

    let url: String
    let allowedOrigins: Set<String>

    /// 브리지 `getSessionToken`이 웹에 건넬 세션 토큰 (KAN-13).
    ///
    /// 안드로이드는 공급자 람다(`() -> String`)를 브리지에 실어 보내고 JS가 물을 때마다 최신 값을
    /// 읽는다. 여기서는 토큰이 **문서에 매인 JS 변수**라 밀어 넣는 쪽이 값의 변화를 알아야 한다 —
    /// 그래서 클로저가 아니라 값이다. 값이 바뀌면 이 뷰가 다시 그려지고, 그때 현재 문서로 다시 민다.
    let sessionToken: String

    let onRequestMicPermission: () -> Void
    let onStartVoiceItem: (VoiceItemStart) -> Void
    let onStartRetest: () -> Void
    let onShareResult: (SharePayload) -> Void

    /// 결과를 웹으로 주입하려면(`evaluateJavaScript`) 상위가 인스턴스를 알아야 한다.
    var onWebViewCreated: (WKWebView) -> Void = { _ in }
    /// 해제된 인스턴스. 상위가 들고 있는 참조를 놓을 자리다.
    var onWebViewReleased: (WKWebView) -> Void = { _ in }

    var timeout: TimeInterval = loadTimeout

    @StateObject private var model = WebLoadModel()

    var body: some View {
        ZStack {
            if model.state == .failed {
                // 안드로이드가 Failed에서 곧바로 return해 WebView를 컴포지션에서 빼는 자리다 —
                // 실패한 WebView의 내부 상태(오류 페이지)가 화면 뒤에 남지 않는다.
                LoadFailureScreen(onRetry: model.retry)
            } else {
                WebViewRepresentable(
                    url: url,
                    allowedOrigins: allowedOrigins,
                    sessionToken: sessionToken,
                    model: model,
                    onRequestMicPermission: onRequestMicPermission,
                    onStartVoiceItem: onStartVoiceItem,
                    onStartRetest: onStartRetest,
                    onShareResult: onShareResult,
                    onWebViewCreated: onWebViewCreated,
                    onWebViewReleased: onWebViewReleased
                )
                // 안드로이드 `key(controller.attempt)`. 값이 바뀌면 SwiftUI가 뷰를 버리고 새로
                // 만들어, 실패한 WebView를 이어받지 않는다.
                .id(model.attempt)

                if model.state == .loading {
                    LoadingScreen()
                }
            }
        }
        /*
         * 자체 타임아웃 (§6) — 로드 완료 콜백이 오지 않는 실패(끊긴 연결에서의 무한 대기 등)를
         * 오류 콜백 대신 시간으로 잡는다. 안드로이드 `LaunchedEffect(attempt, url) { delay(...) }`
         * 자리이고, `.task(id:)`가 키가 바뀔 때마다 앞의 대기를 취소하고 새로 시작하는 것까지 같다.
         *
         * 실패 화면일 때도 이 이펙트는 붙어 있지만, 늦게 도착한 타임아웃은 상태 머신이 무시한다
         * (`WebLoadController.onTimeout`은 loading일 때만 움직인다).
         */
        .task(id: TimeoutKey(attempt: model.attempt, url: url)) {
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            guard !Task.isCancelled else { return }
            model.onTimeout()
        }
    }

    /// 타임아웃을 다시 걸어야 하는 조건 두 가지를 한 값으로 묶는다 (안드로이드의 키 두 개와 같다).
    private struct TimeoutKey: Equatable {
        let attempt: Int
        let url: String
    }
}

// MARK: - UIKit 결선

private struct WebViewRepresentable: UIViewRepresentable {

    let url: String
    let allowedOrigins: Set<String>
    let sessionToken: String
    let model: WebLoadModel
    let onRequestMicPermission: () -> Void
    let onStartVoiceItem: (VoiceItemStart) -> Void
    let onStartRetest: () -> Void
    let onShareResult: (SharePayload) -> Void
    let onWebViewCreated: (WKWebView) -> Void
    let onWebViewReleased: (WKWebView) -> Void

    func makeCoordinator() -> WebViewCoordinator {
        let coordinator = WebViewCoordinator(
            allowedOrigins: allowedOrigins,
            model: model,
            onRequestMicPermission: onRequestMicPermission,
            onStartVoiceItem: onStartVoiceItem,
            onStartRetest: onStartRetest,
            onShareResult: onShareResult
        )
        // 해제 콜백은 `dismantleUIView`가 static이라 Coordinator를 거쳐야 한다.
        coordinator.onReleased = onWebViewReleased
        return coordinator
    }

    func makeUIView(context: Context) -> WKWebView {
        let controller = WKUserContentController()
        controller.addUserScript(BridgeUserScript.makeUserScript())
        // 약한 중계자만 등록한다 — 진짜 핸들러의 소유권은 Coordinator에 있다
        // (`WeakScriptMessageHandler` 주석: 순환 참조를 끊는 자리).
        controller.add(
            WeakScriptMessageHandler(target: context.coordinator.messageHandler),
            name: BridgeUserScript.messageHandlerName
        )

        let configuration = WKWebViewConfiguration()
        configuration.userContentController = controller
        // 세션·localStorage가 프로세스 재시작을 넘어 남는다 — 안드로이드 `domStorageEnabled = true`가
        // 켠 것과 같은 자리다 (웹의 진행 스냅샷이 여기에 앉는다).
        configuration.websiteDataStore = .default()
        // 녹음 안내음·결과 화면 미디어가 전체화면으로 튀지 않게 한다.
        configuration.allowsInlineMediaPlayback = true
        // 안드로이드 `mixedContentMode = NEVER_ALLOW`에 딱 맞는 스위치는 없다 — WebKit은 https
        // 문서 안의 평문 하위 리소스를 기본으로 막고, ATS(Info-Release.plist에 예외 없음)가
        // 평문 접속 자체를 한 겹 더 막는다. `allowFileAccess`·`allowContentAccess`도 마찬가지로
        // WKWebView에는 애초에 켜져 있지 않다(원격 전용이 기본값이다).

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        // 웹이 자기 배경을 그리기 전 한 프레임 흰 종이가 비치지 않게 한다.
        webView.isOpaque = false
        webView.backgroundColor = UIColor(Papercut.cream)
        webView.scrollView.backgroundColor = UIColor(Papercut.cream)
        // 스크롤 바운스는 네이티브 화면과 웹 화면의 경계를 드러낸다.
        webView.scrollView.bounces = false

        #if DEBUG
        // 릴리스 빌드에서 원격 디버깅 차단 — 안드로이드 `setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`.
        // 이 속성은 iOS 16.4에서 생겼고 배포 하한이 16.0이라 가용성 검사가 함께 붙는다.
        if #available(iOS 16.4, *) {
            webView.isInspectable = true
        }
        #endif

        context.coordinator.webView = webView
        context.coordinator.userContentController = controller
        /*
         * 등록을 한 틱 미룬다. `updateUIView`가 `model.onNavigationStarted()`를 미루는 것과 같은
         * 이유이고(아래 주석), 같은 이유가 여기에도 있다는 것을 §8 통합 스모크에서 알았다.
         *
         * 이 자리는 SwiftUI의 갱신 사이클 **안**이라 상위의 `@State`를 그 자리에서 쓰면 값이
         * 반영되지 않는다. 증상이 조용해서 오래 숨어 있었다: 상위(`TestFlowView`)가 WebView
         * 참조를 영영 nil로 들고, 그러면 문항 결과 주입(`deliverResults`)이 매번 "받을 곳이
         * 없다"로 건너뛴다 — 첫 음성 문항의 업로드까지는 멀쩡히 끝나고 그 다음부터 웹이 오지
         * 않을 결과를 기다리며 멈춘다.
         */
        DispatchQueue.main.async { onWebViewCreated(webView) }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.allowedOrigins = allowedOrigins
        context.coordinator.sessionToken = sessionToken

        // 실제로 로드를 건 URL. update는 갱신마다 도는데 매번 load하면 로드가 끝나지 않으므로,
        // 값이 달라졌을 때만 다시 건다 (안드로이드 `loadedUrl`). 뷰가 attempt로 새로 만들어지면
        // Coordinator도 새것이라 nil에서 시작해 새 WebView가 다시 로드한다.
        if context.coordinator.loadedUrl != url {
            context.coordinator.loadedUrl = url
            guard let target = URL(string: url) else { return }
            /*
             * 상태 변경을 한 틱 미루는 이유: 여기는 SwiftUI의 갱신 사이클 안이라 관측 대상을
             * 그 자리에서 바꾸면 "뷰 갱신 중 상태 변경" 경고가 뜬다. 로드 자체는 지금 걸고,
             * "다시 로딩으로 내려라"만 다음 틱에 알린다 — 그 사이는 여전히 앞 페이지가 보이는
             * 구간이라 순서가 뒤집혀도 화면이 어긋나지 않는다.
             */
            let model = self.model
            DispatchQueue.main.async { model.onNavigationStarted() }
            webView.load(URLRequest(url: target))
            return
        }

        // 토큰이 바뀌었으면 지금 문서에 다시 민다 (세션이 뒤늦게 생기는 경로 — 인트로에서
        // 시작을 누르고 세션을 받는 사이 문서는 그대로다).
        context.coordinator.pushSessionTokenIfNeeded()
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: WebViewCoordinator) {
        /*
         * 참조를 놓게 한 뒤 끊는다 — 상위가 죽은 WebView를 붙들 틈을 주지 않는다
         * (안드로이드 `onRelease`가 `onWebViewReleased` → `destroy()` 순서인 것과 같다).
         *
         * 여기도 갱신 사이클 안이라 통지를 한 틱 미룬다(`makeUIView` 주석). 등록과 해제가 같은
         * 큐를 지나므로 순서는 그대로다 — 새 WebView가 먼저 등록되고 옛 것이 뒤에 해제되는
         * 경우에도 상위의 신원 대조(`webView === released`)가 방금 받은 참조를 지킨다.
         */
        DispatchQueue.main.async { coordinator.onReleased?(webView) }
        webView.stopLoading()
        webView.navigationDelegate = nil
        /*
         * `webView.configuration`은 **사본**을 돌려준다 — 거기서 핸들러를 지워도 살아 있는
         * 컨트롤러는 그대로다. 그래서 만들 때 든 인스턴스를 Coordinator가 따로 붙잡고 있다가
         * 여기서 지운다. 등록된 것이 약한 중계자라 이게 없어도 순환은 닫히지 않지만,
         * 죽은 WebView가 메시지를 계속 흘려보내는 구간을 남기지 않는다.
         */
        coordinator.userContentController?.removeAllUserScripts()
        coordinator.userContentController?.removeScriptMessageHandler(
            forName: BridgeUserScript.messageHandlerName
        )
    }
}

/// `WKNavigationDelegate` 결선. 판정은 위의 순수 함수들이 하고, 여기는 WebKit 콜백을 그리로 나른다.
///
/// `@MainActor`인 것이 계약이다. WebKit은 내비게이션 콜백과 스크립트 메시지를 **메인 스레드에서**
/// 부르고(안드로이드의 `postToMain`이 사라진 이유), 이 클래스가 만지는 것은 전부 그 전제 위에 있다 —
/// `WebLoadModel`·`WKWebView`·문서 단위 토큰 상태. 표기해 두지 않으면 그 전제가 주석에만 남는다.
@MainActor
final class WebViewCoordinator: NSObject, WKNavigationDelegate {

    var allowedOrigins: Set<String>
    var sessionToken: String = ""
    var loadedUrl: String?
    weak var webView: WKWebView?
    weak var userContentController: WKUserContentController?
    var onReleased: ((WKWebView) -> Void)?

    /// 이 문서에 마지막으로 밀어 넣은 토큰. `nil`은 "이 문서에는 아직 아무것도 안 밀었다"이고,
    /// 새 문서가 커밋될 때마다 그리로 되돌아간다 — 문서가 바뀌면 JS 쪽 `token`도 `""`로
    /// 초기화되므로(``BridgeUserScript``) 네이티브가 든 기억도 함께 비워야 둘이 어긋나지 않는다.
    private var pushedToken: String?

    /// 지금 화면에 **커밋된** 문서가 있는가. 시작값 false는 첫 문서가 커밋되기 전(아직 아무
    /// 페이지도 없는 순간)을 뜻한다.
    ///
    /// 이 칸이 따로 있는 이유: `webView.url`은 내비게이션이 **시작될 때** 새 주소로 바뀌지만
    /// 그 순간 살아 있는 문서는 아직 앞 페이지다. 그때 밀면 토큰이 곧 사라질 문서에 들어가고
    /// (새 문서는 유저 스크립트가 다시 돌아 `""`로 시작한다) 네이티브는 "밀었다"고 기억해
    /// 정작 새 문서에 밀지 않는다. 커밋 전에는 아무 데도 밀지 않는 것이 맞다.
    private var hasCommittedDocument = false

    /// 지금 진행 중인 **메인 프레임** 로드의 신원. 없으면 기다리는 로드가 없다는 뜻이다.
    ///
    /// 안드로이드의 `request.isForMainFrame`을 대신하는 자리다 (``shouldReportMainFrameFailure``
    /// 주석 참고). 로드가 끝나면(``didFinish``) 비운다 — 그래야 "지금 기다리는 것"이라는 뜻이
    /// 유지되고, 로드가 끝난 뒤 새로 시작되는 내비게이션은 자기 신원을 새로 받는다.
    private var currentMainFrameNavigation: ObjectIdentifier?

    #if DEBUG
    /// 웹 화면을 대신 눌러 주는 구동기 (`-AutoFlowDrive 1`, KAN-108 §8). WebView 하나에 하나이고
    /// 문서가 바뀔 때마다 스크립트를 다시 심는다 — 전역이 새 문서에서 사라지기 때문이다.
    var autoDriver: WebAutoDriver?
    #endif

    private let model: WebLoadModel
    private let onRequestMicPermission: () -> Void
    private let onStartVoiceItem: (VoiceItemStart) -> Void
    private let onStartRetest: () -> Void
    private let onShareResult: (SharePayload) -> Void

    /// 브리지 메시지 수신기. `lazy`인 이유는 dispatcher의 origin 판정 클로저가 `self`(현재 URL과
    /// 최신 allowlist)를 읽어야 하기 때문이다 — 초기화 중에는 잡을 수 없다.
    lazy var messageHandler: BridgeMessageHandler = BridgeMessageHandler(
        dispatcher: BridgeDispatcher(
            // 실행 시점의 현재 URL로 재검증 — 메시지를 보낸 뒤 리다이렉트됐어도 안전하다 (§8).
            isCurrentUrlAllowed: { [weak self] in
                guard let self else { return false }
                return isAllowedWebUrl(self.webView?.url?.absoluteString, allowedOrigins: self.allowedOrigins)
            },
            onRequestMicPermission: { [weak self] in self?.onRequestMicPermission() },
            onStartVoiceItem: { [weak self] in self?.onStartVoiceItem($0) },
            onStartRetest: { [weak self] in self?.onStartRetest() },
            onShareResult: { [weak self] in self?.onShareResult($0) }
        )
    )

    init(
        allowedOrigins: Set<String>,
        model: WebLoadModel,
        onRequestMicPermission: @escaping () -> Void,
        onStartVoiceItem: @escaping (VoiceItemStart) -> Void,
        onStartRetest: @escaping () -> Void,
        onShareResult: @escaping (SharePayload) -> Void
    ) {
        self.allowedOrigins = allowedOrigins
        self.model = model
        self.onRequestMicPermission = onRequestMicPermission
        self.onStartVoiceItem = onStartVoiceItem
        self.onStartRetest = onStartRetest
        self.onShareResult = onShareResult
        super.init()
    }

    /// 세션 토큰을 지금 문서에 민다. **origin이 allowlist 안일 때만** —
    /// 안드로이드가 `onPageStarted`에서 `originAllowed`를 갱신하는 자리와 같은 판정이다.
    func pushSessionTokenIfNeeded() {
        guard hasCommittedDocument else { return }
        guard pushedToken != sessionToken else { return }
        guard let webView else { return }
        let current = webView.url?.absoluteString
        guard isAllowedWebUrl(current, allowedOrigins: allowedOrigins) else { return }
        pushedToken = sessionToken
        webView.evaluateJavaScript(BridgeUserScript.sessionTokenPushJs(sessionToken), completionHandler: nil)
        #if DEBUG
        // 토큰 값은 절대 찍지 않는다 — 밀어 넣었다는 사실과 어느 origin이었는지만 남긴다.
        smokeLog("TOKEN: pushed origin=\(webOrigin(current ?? "") ?? "?") empty=\(sessionToken.isEmpty)")
        #endif
    }

    // MARK: WKNavigationDelegate

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        let target = navigationAction.request.url?.absoluteString
        // 메인 프레임·서브프레임을 가리지 않는다 — allowlist가 곧 보안 경계라 iframe도 같은
        // 문을 지난다 (안드로이드도 `shouldOverrideUrlLoading`이 그 둘을 함께 받는다).
        switch navigationDecision(url: target, allowedOrigins: allowedOrigins) {
        case .allow:
            decisionHandler(.allow)
        case .cancel:
            #if DEBUG
            smokeLog("NAV: cancelled \(target ?? "(nil)")")
            #endif
            decisionHandler(.cancel)
        }
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationResponse: WKNavigationResponse,
        decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void
    ) {
        let status = (navigationResponse.response as? HTTPURLResponse)?.statusCode ?? 200
        if mainFrameHttpErrorDecision(status: status, isMainFrame: navigationResponse.isForMainFrame) {
            // 서버가 준 오류 본문을 그리게 두지 않는다 — 그 화면에는 우리 카피가 낄 자리가 없다.
            model.onMainFrameError()
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    /// 새 내비게이션이 시작됐다 (안드로이드 `onPageStarted` 자리). 아직 문서가 갈리지 않았으므로
    /// 커밋될 때까지는 아무 데도 토큰을 밀지 않는다 — fail-closed.
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        hasCommittedDocument = false
        pushedToken = nil
        // 이 로드가 지금부터 "기다리는 메인 프레임 로드"다. 앞 로드가 밀려났다면 그 신원은
        // 여기서 버려지고, 뒤늦게 도착할 앞 로드의 실패는 아래 판정이 걸러낸다.
        currentMainFrameNavigation = identity(navigation)
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        // 새 문서다 — JS 쪽 토큰은 유저 스크립트가 다시 돌면서 이미 ""로 초기화됐다.
        // 네이티브가 든 기억도 여기서 비워야 다음 push가 실제로 나간다.
        hasCommittedDocument = true
        pushedToken = nil
        pushSessionTokenIfNeeded()
        #if DEBUG
        smokeLog("NAV: committed \(webView.url?.absoluteString ?? "(nil)")")
        #endif
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        currentMainFrameNavigation = nil
        model.onPageFinished()
        #if DEBUG
        runSmokeHooksIfRequested(webView)
        #endif
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        reportFailure(navigation, error)
    }

    func webView(
        _ webView: WKWebView,
        didFailProvisionalNavigation navigation: WKNavigation!,
        withError error: Error
    ) {
        reportFailure(navigation, error)
    }

    /// 실패 통지 한 건을 상태 머신으로 넘길지 정한다. 판정은 순수 함수
    /// ``shouldReportMainFrameFailure(navigation:currentMainFrame:error:)``가 하고,
    /// 여기서는 WebKit 객체를 신원으로 바꿔 넘기기만 한다.
    private func reportFailure(_ navigation: WKNavigation!, _ error: Error) {
        let failing = identity(navigation)
        guard shouldReportMainFrameFailure(
            navigation: failing,
            currentMainFrame: currentMainFrameNavigation,
            error: error
        ) else { return }
        currentMainFrameNavigation = nil
        model.onMainFrameError()
    }

    /// `WKNavigation!`(암묵적 언래핑)을 비교 가능한 신원으로 바꾼다. 통지에 객체가 없으면 nil이다.
    private func identity(_ navigation: WKNavigation!) -> ObjectIdentifier? {
        (navigation as WKNavigation?).map(ObjectIdentifier.init)
    }
}

#if DEBUG
private let smokeLogger = Logger(subsystem: "com.accentury.app", category: "web")

/// 시뮬레이터 스모크용 한 줄. `xcrun simctl launch --console-pty`가 stdout을 그대로 보여주고,
/// 로그 스트림에서도 찾을 수 있게 둘 다에 남긴다.
func smokeLog(_ line: String) {
    print(line)
    smokeLogger.info("\(line, privacy: .public)")
}

private extension WebViewCoordinator {

    /// 실행 인자로 켜는 스모크 훅. 시뮬레이터에는 탭을 넣을 방법이 없어서
    /// (`xcrun simctl`에 좌표 입력이 없다) 화면을 눌러야 하는 경로를 JS로 대신 민다.
    /// 릴리스에는 통째로 없다.
    func runSmokeHooksIfRequested(_ webView: WKWebView) {
        let defaults = UserDefaults.standard

        // `-AutoStartSmoke 1` — 인트로의 [시작하기]를 눌러 브리지 requestMicPermission을 흘린다.
        if defaults.bool(forKey: "AutoStartSmoke") {
            /*
             * 버튼을 기다렸다 누른다. `didFinish`는 문서 로드가 끝난 시점이고 리액트가 첫
             * 렌더를 마친 시점이 아니라, 곧바로 찾으면 없다(`no-button`을 실측했다).
             * 폴링 자체가 웹 쪽 사정이므로 판정도 JS 안에 둔다 — 네이티브가 다시 부르면
             * 그 사이 화면이 바뀌었는지를 또 따져야 한다.
             */
            webView.evaluateJavaScript(
                """
                (function(){
                  var tries = 0;
                  var timer = setInterval(function(){
                    tries++;
                    var b = Array.prototype.find.call(
                      document.querySelectorAll("button"),
                      function(el){ return (el.textContent || "").indexOf("시작하기") >= 0; }
                    );
                    if (b) { clearInterval(timer); b.click(); return; }
                    if (tries > 50) { clearInterval(timer); }
                  }, 100);
                  return "armed";
                })()
                """
            ) { result, _ in
                smokeLog("SMOKE: autostart=\(result.map { "\($0)" } ?? "error")")
            }
        }

        // `-AutoNavSmoke https://example.com` — allowlist 밖으로 나가 보고 막히는지 본다.
        if let target = defaults.string(forKey: "AutoNavSmoke"), !target.isEmpty {
            let js = "window.location.href = \(BridgeUserScript.jsStringLiteral(target));"
            webView.evaluateJavaScript(js, completionHandler: nil)
        }

        /*
         * `-AutoFlowDrive 1` — 웹 화면을 문항 끝까지 대신 누른다 (§8 통합 스모크).
         *
         * 문서마다 다시 심는다. 인트로 → 테스트 진입은 같은 WebView의 **다른 문서**라 앞
         * 문서에 심은 전역이 남아 있지 않다 — 여기서 다시 부르지 않으면 정작 문항 화면에서
         * 구동기가 없다.
         */
        if WebAutoDriver.isEnabled {
            Task { @MainActor in
                if autoDriver == nil {
                    autoDriver = WebAutoDriver(
                        webView: webView,
                        // 인트로는 `-AutoStartSmoke`가 이미 맡고 있으면 그쪽에 넘긴다.
                        drivesIntro: !defaults.bool(forKey: "AutoStartSmoke")
                    )
                }
                autoDriver?.installIntoCurrentDocument()
            }
        }
    }
}
#endif

// MARK: - 네이티브 화면

/// 로드 완료까지 붙드는 로딩 화면 — 빈 화면·흰 플래시를 노출하지 않는다 (§10 Q5).
/// 아래 WebView를 완전히 가린다.
private struct LoadingScreen: View {
    var body: some View {
        ZStack {
            Papercut.cream.ignoresSafeArea()
            VStack(spacing: Papercut.space3) {
                Text("사투리 억양 테스트")
                    .font(.system(size: 20))
                    .foregroundColor(Papercut.ink)
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(Papercut.ink)
            }
        }
    }
}

/// 네이티브 오류 화면 (§6) — 비난 없는 카피 + [다시 시도]. 문구는 안드로이드 정본 그대로다.
private struct LoadFailureScreen: View {

    let onRetry: () -> Void

    var body: some View {
        ZStack {
            Papercut.cream.ignoresSafeArea()
            VStack(spacing: Papercut.space3) {
                Text("연결이 불안정해요")
                    .font(.system(size: 22))
                    .foregroundColor(Papercut.ink)
                Text("네트워크를 확인하고 다시 시도해 주세요")
                    .font(.system(size: 16))
                    .foregroundColor(Papercut.muted)
                    .multilineTextAlignment(.center)

                Button(action: onRetry) {
                    Text("다시 시도")
                        .font(.system(size: 18))
                        .foregroundColor(Papercut.cream)
                        .padding(.horizontal, Papercut.space6)
                        .frame(height: Papercut.controlHeightLarge)
                        .background(RoundedRectangle(cornerRadius: Papercut.radiusMD).fill(Papercut.ink))
                }
                .buttonStyle(.plain)
                .padding(.top, Papercut.space3)
            }
            .padding(Papercut.space6)
        }
    }
}
