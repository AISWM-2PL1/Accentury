import Foundation

/// 원격 웹 로드 상태 (webview-layer.md §6).
///
/// 안드로이드는 이 타입을 Compose 화면 파일(`WebViewHost.kt`)에 두지만, 순수한 값이라
/// 이쪽에서는 상태 머신과 함께 Core에 둔다 — 호스트(WKWebView)는 §6 결선에서 이 값을 읽기만 한다.
public enum WebLoadState: Equatable, Sendable {
    case loading
    case ready
    case failed
}

/// 원격 웹 로드 상태 머신 (webview-layer.md §6). WebView 콜백·타이머·재시도가 여기로 모인다.
///
/// 호스트에서 분리한 이유: 콜백 도착 순서가 뒤엉키는 경계 조건(오류 후 로드 완료, Ready 후 늦은
/// 타임아웃 등)이 실패 UX의 정확성을 좌우하는데, WKWebView에 붙어 있으면 시뮬레이터 없이
/// 검증할 수 없다.
///
/// **타이머는 여기 없다.** 안드로이드도 마찬가지로 8초(`loadTimeout`) 대기는 화면 쪽
/// (`LaunchedEffect { delay(timeoutMs) }`)이 걸고, 이 클래스는 발화된 결과만 ``onTimeout()``으로
/// 받는다. 시간을 주입하지 않아도 테스트가 결정적인 이유가 이것이다 — iOS 호스트도 같은 자리에
/// `Task.sleep`을 두고 이 메서드를 부른다.
///
/// 호출은 전부 메인 스레드에서 온다(WebKit 콜백·SwiftUI). 그래서 상태에 동기화를 두지 않는다.
/// 앱 계층의 `ObservableObject` 래퍼가 호출 직후 값을 다시 읽어 `@Published`로 옮긴다
/// (`MicPermissionController`와 같은 방식).
public final class WebLoadController {

    public private(set) var state: WebLoadState = .loading

    /// 재시도 횟수이자 WebView 재생성 키 — 값이 바뀌면 호스트가 WebView를 처음부터 새로 만든다.
    public private(set) var attempt: Int = 0

    public init() {}

    /// 오류 페이지도 로드 완료 콜백을 쏘기 때문에, 오류 콜백이 먼저 찍은 ``WebLoadState/failed``를
    /// 여기서 덮어쓰면 안 된다 — ``WebLoadState/loading``일 때만 ready로 간다.
    public func onPageFinished() {
        if state == .loading { state = .ready }
    }

    /// 메인 프레임 오류(네트워크·HTTP)는 로드 단계든 로드 후 내비게이션이든 실패 화면으로 보낸다.
    public func onMainFrameError() {
        state = .failed
    }

    /// 자체 타임아웃 (§6). 로드가 이미 끝났으면(성공이든 실패든) 늦게 도착한 타이머는 무시한다.
    public func onTimeout() {
        if state == .loading { state = .failed }
    }

    /// 같은 WebView에서 다른 URL 로드를 시작했다 (인트로 → 테스트 진입, KAN-100).
    /// 앞 페이지가 ready였다고 다음 페이지를 로드 완료로 볼 수는 없다 — 다시 loading으로 내려야
    /// 로딩 화면이 전환 중의 앞 페이지를 덮고, 타임아웃도 새 로드를 대상으로 다시 걸린다.
    public func onNavigationStarted() {
        state = .loading
    }

    /// [다시 시도] — attempt를 올려 WebView를 새로 만들고 처음부터 로드한다.
    public func retry() {
        attempt += 1
        state = .loading
    }
}
