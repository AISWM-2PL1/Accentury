import AccenturyCore
import Foundation
import WebKit

/// 웹 → 네이티브 브리지의 **네이티브 쪽 절반** (webview-layer.md §8).
/// 안드로이드 `AccenturyBridge.kt`의 이식본이고, JS 쪽 절반은 ``BridgeUserScript``다.
///
/// 최소 표면 원칙 — 화면 전환(KAN-100)·답안 제출 인증(KAN-13)·재응시(KAN-34)·결과 공유(KAN-30)·
/// 계측(KAN-33)까지 필요한 일곱 메서드만 둔다. 늘리기 전에 웹에서 해결 가능한지 먼저 볼 것.
/// 그중 값을 돌려주는 둘(`getContractVersion`·`getSessionToken`)은 여기로 오지 않는다 —
/// JS 안에서 끝난다 (``BridgeUserScript`` 참고).
///
/// ## `postToMain`이 없는 이유
///
/// 안드로이드의 `@JavascriptInterface` 메서드는 **JS 전용 스레드**에서 불려서, 상태를 바꾸는
/// 호출은 `View.post`로 메인 스레드에 넘긴 뒤 거기서 origin을 재검증한다. `WKScriptMessageHandler`는
/// 그 단계가 필요 없다 — WebKit이 **메인 스레드에서** 콜백을 부른다. 그래서 "메인으로 넘긴다"는
/// 조각이 사라지고, 그 조각이 지키던 것(**호출 시점이 아니라 처리 시점의 URL로 판정한다**)만 남는다.
/// 남은 쪽이 본질이라 여기서도 그대로 건다: JS가 메시지를 보낸 뒤 처리되기까지 페이지가
/// allowlist 밖으로 리다이렉트될 수 있고, 그때 실행되면 안 된다.
///
/// ## WebKit을 모르는 타입인 이유
///
/// ``BridgeDispatcher``는 `WebKit`을 import하지 않는다. 안드로이드가 `AccenturyBridgeTest`
/// 24건을 WebView 없이 돌리는 자리를, 이쪽은 이 분리로 얻는다 — 시뮬레이터 없이 origin 게이팅과
/// payload 검증을 직접 검증할 수 있다. WebKit 결선은 아래 ``BridgeMessageHandler``가 맡는다.
/// `@MainActor`가 위의 "postToMain이 없는 이유"를 주석이 아니라 타입으로 적어 둔 것이다 —
/// 이 타입의 모든 호출은 메인 스레드라는 전제 위에 있고, 그 전제가 깨지면 origin 재검증이
/// 읽는 값(현재 URL)도 함께 흔들린다.
@MainActor
struct BridgeDispatcher {

    /// 지금 로드된 URL이 allowlist 안인가. **처리 시점에** 묻는다.
    let isCurrentUrlAllowed: () -> Bool
    let onRequestMicPermission: () -> Void
    let onStartVoiceItem: (VoiceItemStart) -> Void
    let onStartRetest: () -> Void
    let onShareResult: (SharePayload) -> Void

    /// 웹이 센 계측 이벤트 (KAN-33). 이름과 파라미터는 여기 도착하기 전에 이미 GA4 규격으로
    /// 좁혀져 있다 — 받는 쪽(``EventSink``)은 이름을 손대지 않는다.
    let onLogEvent: (String, [String: EventParam]) -> Void

    /// 메시지 한 건을 처리한다. 조건에 맞지 않으면 **조용히** 아무 일도 하지 않는다.
    ///
    /// 조용한 이유는 안드로이드와 같다: 웹은 신뢰 경계 밖이라 오류를 되돌려 줄 상대가 아니고,
    /// 잘못된 컨텍스트로 화면을 띄우거나 남의 링크를 공유 시트에 싣는 것보다 아무 일도 안 하는
    /// 편이 안전하다.
    ///
    /// 검사 순서가 곧 신뢰 경계다 — **origin 먼저, 파싱은 그 뒤**. allowlist 밖 페이지가 보낸
    /// payload는 내용과 무관하게 처리할 값이 아니다.
    ///
    /// - Parameter payload: 주입 스크립트가 `String(json)`으로 문자열을 실어 보내지만, 페이지가
    ///   `window.webkit.messageHandlers.accentury.postMessage`를 직접 부르면 아무 타입이나 올 수
    ///   있다. 문자열이 아니면 무시한다 — 그 통로는 우리 브리지 객체를 우회하므로 여기가
    ///   유일한 검문소다.
    func handle(method: String, payload: Any?) {
        guard isCurrentUrlAllowed() else { return }

        switch method {
        case "requestMicPermission":
            onRequestMicPermission()

        case "startRetest":
            // 연타를 여기서 세지 않는다 — 진행 중이라는 사실의 주인은 요청을 건 상태 머신 하나여야
            // 한다 (`SessionGateController.retestInFlight`). 두 곳에 두면 어긋나는 순간 막으려던
            // 이중 요청이 정확히 그때 새어 나간다.
            onStartRetest()

        case "startVoiceItem":
            guard let json = payload as? String else { return }
            guard let start = parseVoiceItemStart(json) else {
                // 조용히 버리되 흔적은 남긴다 (KAN-33). allowlist를 통과한 페이지만 여기 오므로
                // 이 실패는 우리 웹과 우리 앱이 계약을 다르게 알고 있다는 뜻이다 (``CrashReports``).
                CrashReports.recordBridgeParseFailure("startVoiceItem")
                return
            }
            onStartVoiceItem(start)

        case "shareResult":
            guard let json = payload as? String else { return }
            guard let share = parseSharePayload(json) else {
                CrashReports.recordBridgeParseFailure("shareResult")
                return
            }
            onShareResult(share)

        case "logEvent":
            /*
             * 여기서 거르는 것은 안전이 아니라 **집계 축의 위생**이다. 규격 밖 이름이 한 번
             * 흘러가면 GA4에 지울 수 없는 축이 생기고(이벤트·파라미터 정의는 사후 삭제가 안 된다),
             * 그 축은 사람이 다시 읽어야 하는 대시보드가 된다 (`AccenturyCore` `EventParams`).
             *
             * 이벤트 하나를 잃는 것은 감수한다 — 웹은 오류를 돌려줄 상대가 아니고, 계측 때문에
             * 응시를 멈출 이유는 더더욱 없다. 대신 버렸다는 사실만 비치명 이벤트로 남긴다.
             *
             * 봉투가 이 메서드만 객체인 이유는 ``BridgeUserScript`` 주석에 있다.
             */
            guard let body = payload as? [String: Any],
                  let name = body["name"] as? String,
                  let paramsJson = body["params"] as? String
            else { return }
            guard isAnalyticsName(name), let params = parseEventParams(paramsJson) else {
                CrashReports.recordBridgeParseFailure("logEvent")
                return
            }
            onLogEvent(name, params)

        default:
            // 모르는 메서드. 신버전 웹이 구버전 앱에 보낸 호출일 수도 있고(메서드 추가는
            // 하위호환이라 계약 버전이 오르지 않는다, §5) 임의 페이지의 장난일 수도 있다.
            // 어느 쪽이든 할 일은 같다.
            return
        }
    }
}

/// ``BridgeDispatcher``를 WebKit에 붙이는 얇은 껍데기. 판단은 하나도 하지 않는다.
@MainActor
final class BridgeMessageHandler: NSObject, WKScriptMessageHandler {

    private let dispatcher: BridgeDispatcher

    init(dispatcher: BridgeDispatcher) {
        self.dispatcher = dispatcher
    }

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        guard message.name == BridgeUserScript.messageHandlerName else { return }
        // 봉투가 우리 모양이 아니면 버린다 — 주입 스크립트를 우회한 직접 호출이다.
        guard let body = message.body as? [String: Any],
              let method = body["method"] as? String
        else { return }
        dispatcher.handle(method: method, payload: body["payload"])
    }
}

/// `WKUserContentController`가 핸들러를 **강하게** 붙잡는 것을 끊는 중계자.
///
/// 이게 없으면 순환이 닫힌다: WKWebView → configuration → userContentController → 핸들러 →
/// (핸들러가 든 클로저가 화면·모델을 붙잡고) → WKWebView. 화면이 사라져도 WebView가 살아남아
/// 남의 세션 토큰을 든 채로 남는다.
///
/// 진짜 핸들러의 소유권은 호출자(``WebViewHost``의 Coordinator)에 두고, 컨트롤러에는 이 중계자만
/// 등록한다. 중계자가 대상을 놓친 뒤 도착한 메시지는 그냥 버려진다 — 그때는 받아 줄 화면이 없다.
final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {

    private weak var target: WKScriptMessageHandler?

    init(target: WKScriptMessageHandler) {
        self.target = target
    }

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        target?.userContentController(userContentController, didReceive: message)
    }
}
