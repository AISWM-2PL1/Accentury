import AccenturyCore
import Foundation
import WebKit

// JSON에선 평범한 문자지만 JS 소스에선 줄 종결자인 두 글자. 소스에 그대로 적으면 보이지 않아
// 코드 리뷰에서 사라지므로 코드포인트로 둔다 (Core `WebDelivery.swift`와 같은 이유·같은 값).
private let jsLineSeparator = "\u{2028}"
private let jsParagraphSeparator = "\u{2029}"

/// 웹 → 네이티브 브리지의 **JS 쪽 절반** (webview-layer.md §5·§8).
///
/// ## 왜 안드로이드에는 없는 파일인가
///
/// 안드로이드는 `addJavascriptInterface(bridge, "AccenturyBridge")` 한 줄로 코틀린 객체를
/// 페이지에 그대로 심는다. 메서드가 값을 **동기로 돌려주는 것**도 그래서 공짜다 —
/// `getContractVersion()`·`getSessionToken()`이 JS 스레드에서 곧바로 코틀린을 부른다.
///
/// WKWebView에는 그 자리가 없다. `WKScriptMessageHandler`는 **단방향·비동기**라 JS로
/// 값을 되돌려줄 수 없고, `WKScriptMessageHandlerWithReply`는 Promise를 준다 —
/// 둘 다 `bridge.ts`가 요구하는 모양이 아니다. 웹 쪽 계약(`web/src/bridge/bridge.ts`)은
/// 이 세 가지를 전제한다:
///
/// 1. `getContractVersion()`이 **숫자를 즉시** 돌려준다
/// 2. `getSessionToken()`이 **문자열을 즉시** 돌려준다
/// 3. `isStandaloneWeb()`이 페이지 스크립트 실행 시점에 `window.AccenturyBridge === undefined`를
///    본다 — 즉 우리 주입이 페이지 스크립트보다 **먼저** 끝나 있어야 한다
///
/// 그래서 브리지 객체 자체를 JS로 적어 `WKUserScript`(`.atDocumentStart`)로 심는다.
/// 값을 돌려주는 두 메서드는 JS 안의 값을 읽고, 상태를 바꾸는 네 메서드는
/// `window.webkit.messageHandlers.accentury.postMessage`로 네이티브에 넘긴다.
/// **웹은 이 차이를 모른다** — `bridge.ts`는 한 글자도 바뀌지 않는다.
///
/// ## 보안: 안드로이드의 fail-closed `AtomicBoolean`과 같은 자리
///
/// 안드로이드는 메인 스레드가 `onPageStarted`마다 `originAllowed`를 갱신하고 JS 스레드는
/// 읽기만 한다(§8). 시작값 `false`라 첫 페이지가 뜨기 전에는 아무에게도 토큰이 나가지 않는다.
///
/// 여기서는 토큰이 **문서(document)에 매인 JS 변수**다. 그래서 같은 성질이 구조로 따라온다:
///
/// - **시작값이 빈 문자열이다.** 네이티브가 밀어 넣기 전에는 누가 물어도 `""`다
///   (웹 래퍼가 빈 값을 null로 정규화한다 — `bridge.ts` `getSessionToken`). 네이티브가 이 심보다
///   먼저 도착한 경우만 예외인데, 그때 값이 놓여 있는 자리가 ``pendingSlotName``이고 그 자리에
///   쓰는 조건도 setter를 부르는 조건과 같다(커밋된 origin이 allowlist 안).
/// - **메인 프레임 전환마다 자동으로 초기화된다.** 새 문서가 열리면 이 유저 스크립트가 다시
///   돌아 `token`이 다시 `""`가 된다. 안드로이드가 `onPageStarted`에서 플래그를 다시 쓰는 자리를,
///   여기서는 WebKit이 문서를 갈아 끼우는 것으로 대신한다.
/// - **밀어 넣는 것은 origin이 allowlist 안일 때뿐이다.** `didCommit`에서 커밋된 메인 프레임
///   URL을 보고 판정한다 (`WebViewHost`). allowlist 밖 문서에는 아무것도 밀어 넣지 않으므로
///   그 문서의 `getSessionToken()`은 영영 `""`다.
/// - **iframe은 이 스크립트를 못 받는다.** `forMainFrameOnly: true`라 서브프레임에는
///   `window.AccenturyBridge` 자체가 없다. 안드로이드에서 `onPageStarted`가 메인 프레임에만
///   오는 것과 같은 보장을, 이쪽은 주입 범위로 얻는다.
///
/// ``setterName``을 `Object.defineProperty`로 `writable:false, configurable:false`로 박는
/// 이유는 페이지 스크립트가 그 자리를 가로채 네이티브가 밀어 넣는 토큰을 훔쳐 가지 못하게
/// 하기 위해서다. 브리지 객체도 `Object.freeze`로 얼려 메서드 교체를 막는다.
enum BridgeUserScript {

    /// `WKScriptMessageHandler` 등록 이름. JS가 `window.webkit.messageHandlers.<이 이름>`으로 찾는다.
    static let messageHandlerName = "accentury"

    /// 네이티브가 토큰을 밀어 넣는 전역 함수 이름. 웹 계약(`bridge.ts`)에 없는 이름이라
    /// 앞에 `__`를 붙여 "웹이 부를 것이 아니다"를 표기해 둔다.
    static let setterName = "__accenturySetSessionToken"

    /// 네이티브가 setter보다 **먼저** 도착했을 때 토큰을 놓아 두는 자리.
    ///
    /// `WKUserScript(.atDocumentStart)`와 `evaluateJavaScript`는 **둘 사이의 순서가 보장되지
    /// 않는다**. 실기기에서 `didCommit` 시점의 push가 유저 스크립트보다 먼저 도는 것을 실측했고
    /// (KAN-108 실기기 결함), 그때 `window.__accenturySetSessionToken`이 아직 없어 호출이 조용히
    /// 던지고 토큰은 `""`로 남았다 — `completionHandler`가 nil이라 실패조차 보이지 않는다.
    ///
    /// 그래서 push를 "부르거나, 없으면 여기 두고 간다"로 바꾼다. 심이 뒤늦게 돌면 이 자리를 먼저
    /// 읽고 비운다.
    ///
    /// **보안 등가성은 그대로다.** 이 자리에 쓰는 것도 `didCommit`에서 **커밋된 origin이
    /// allowlist를 통과한 문서**뿐이다(`WebViewHost`) — setter를 부를 때와 한 글자도 다르지 않은
    /// 조건이다. 페이지가 이 이름에 무엇을 적어 넣어도 네이티브가 밀지 않은 토큰이 생기지는
    /// 않는다: 심은 문자열만 받아들이고, 페이지가 스스로 적은 값은 그 페이지가 이미 알던 값이다.
    static let pendingSlotName = "__accenturyPendingSessionToken"

    /// 페이지 스크립트보다 먼저 도는 주입 소스.
    ///
    /// 계약 버전을 손으로 적지 않고 ``AccenturyCore/bridgeContractVersion``에서 조립한다 —
    /// 상수와 주입 소스가 갈리면 웹의 스큐 게이트(§5)가 거짓말을 하게 된다.
    static let source: String = makeSource(contractVersion: bridgeContractVersion)

    static func makeSource(contractVersion: Int) -> String {
        """
        (function(){ if (window.AccenturyBridge) return;
          var token = (typeof window.\(pendingSlotName) === "string") ? window.\(pendingSlotName) : "";
          try { delete window.\(pendingSlotName); } catch (e) { window.\(pendingSlotName) = ""; }
          function post(method, payload){
            window.webkit.messageHandlers.\(messageHandlerName).postMessage(
              {method: method, payload: payload === undefined ? null : payload}
            );
          }
          Object.defineProperty(window, "\(setterName)", {
            value: function(t){ token = (typeof t === "string") ? t : ""; },
            writable: false, configurable: false
          });
          window.AccenturyBridge = Object.freeze({
            getContractVersion: function(){ return \(contractVersion); },
            getSessionToken: function(){ return token; },
            requestMicPermission: function(){ post("requestMicPermission"); },
            startVoiceItem: function(json){ post("startVoiceItem", String(json)); },
            startRetest: function(){ post("startRetest"); },
            shareResult: function(json){ post("shareResult", String(json)); }
          });
        })();
        """
    }

    /// `.atDocumentStart` · 메인 프레임 전용. 두 값 다 계약이라 호출자가 고르지 못하게 여기서 박는다.
    static func makeUserScript() -> WKUserScript {
        WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: true)
    }

    /// 네이티브 → JS 토큰 주입 한 조각.
    ///
    /// **setter가 없을 수도 있다는 전제 위에 있다.** `WKUserScript(.atDocumentStart)`가 이
    /// `evaluateJavaScript`보다 먼저 돈다는 보장이 WebKit에 없다 — 실기기에서 반대 순서를
    /// 실측했다(``pendingSlotName`` 주석). 그래서 "있으면 부르고, 없으면 놓고 간다"로 적는다.
    /// 심은 나중에 돌면서 그 자리를 먼저 읽는다.
    ///
    /// 토큰을 **JS 소스에 그대로 이어 붙이지 않고 문자열 리터럴로 인코딩하는** 이유는
    /// ``AccenturyCore/webDeliveryJs(method:payloadJson:)``와 같다: 주입한 코드가 파싱되는지가
    /// 데이터 내용에 좌우돼서는 안 된다. 토큰은 서버가 발급한 불투명 문자열이라 형식을 앱이
    /// 정하지 않는다 — 따옴표 하나로 구문이 깨지면 그 문서의 브리지가 통째로 죽는다.
    ///
    /// U+2028·U+2029를 한 번 더 거르는 것도 같은 이유다. JSON 문자열 안에서는 합법이지만
    /// JS 소스에서는 줄 종결자라, 리터럴 안에 그대로 남으면 구문이 깨진다.
    static func sessionTokenPushJs(_ token: String) -> String {
        let literal = jsStringLiteral(token)
        return "(function(){ var t = \(literal);"
            + " if (typeof window.\(setterName) === \"function\") { window.\(setterName)(t); }"
            + " else { window.\(pendingSlotName) = t; } })();"
    }

    /// 임의 문자열을 JS 소스에 넣어도 되는 리터럴로 만든다.
    ///
    /// 인코딩 자체는 Core의 ``AccenturyCore/jsonStringLiteral(_:)``이 한다 — 그 함수가
    /// 안드로이드 `kotlinx.serialization`과 **한 글자도 다르지 않은** 출력을 내도록 손으로
    /// 맞춰 둔 물건이라(Foundation의 `JSONEncoder`는 `/`를 `\/`로 바꾸고 `\b`·`\f`를 안 쓴다),
    /// 여기서 두 번째 구현을 만들면 두 플랫폼의 주입 JS가 갈린다.
    ///
    /// 여기서 더하는 것은 U+2028·U+2029 한 겹뿐이다. JSON 문자열 안에서는 합법이지만 JS
    /// 소스에서는 줄 종결자라 리터럴 안에 그대로 남으면 구문이 깨진다 — Core의
    /// ``AccenturyCore/webDeliveryJs(method:payloadJson:)``도 같은 자리에서 같은 두 줄을 건다.
    static func jsStringLiteral(_ value: String) -> String {
        jsonStringLiteral(value)
            .replacingOccurrences(of: jsLineSeparator, with: "\\u2028")
            .replacingOccurrences(of: jsParagraphSeparator, with: "\\u2029")
    }
}
