import AccenturyCore
import JavaScriptCore
import XCTest
@testable import Accentury

/// 주입 JS는 안드로이드에 대응물이 없는 iOS 고유 코드다 (``BridgeUserScript`` 주석).
/// 그만큼 검증도 여기서 처음 서는데, 이 스크립트가 곧 **웹이 보는 브리지 그 자체**라
/// 계약(`web/src/bridge/bridge.ts`)과 어긋나면 앱 안에서 웹이 통째로 멈춘다.
final class BridgeUserScriptTests: XCTestCase {

    private let source = BridgeUserScript.source

    /// 계약 버전을 손으로 적지 않는다 — 상수와 주입 소스가 갈리면 웹의 스큐 게이트(§5)가
    /// 거짓말을 한다. 리터럴 `1`을 기대값으로 쓰지 않는 것이 이 테스트의 요점이다.
    func testContractVersionComesFromTheCoreConstant() {
        XCTAssertTrue(
            source.contains("return \(bridgeContractVersion);"),
            "주입 소스가 Core의 bridgeContractVersion을 싣지 않았다"
        )
        // 상수가 올랐는데 소스가 옛 값을 들고 있는 경우를 잡는다.
        let bumped = BridgeUserScript.makeSource(contractVersion: bridgeContractVersion + 1)
        XCTAssertNotEqual(source, bumped)
        XCTAssertTrue(bumped.contains("return \(bridgeContractVersion + 1);"))
    }

    /// `bridge.ts`가 `typeof bridge?.foo === 'function'`으로 찾는 여섯 이름. 하나라도 빠지면
    /// 웹 래퍼가 false로 내려가 그 경로가 조용히 죽는다.
    func testAllSixContractMethodsAreDefined() {
        for method in [
            "getContractVersion",
            "getSessionToken",
            "requestMicPermission",
            "startVoiceItem",
            "startRetest",
            "shareResult",
        ] {
            XCTAssertTrue(source.contains("\(method):"), "브리지 객체에 \(method)이(가) 없다")
        }
    }

    /// fail-closed: 네이티브가 밀어 넣기 전에는 누가 물어도 빈 문자열이다
    /// (안드로이드 `AtomicBoolean(false)` 시작값과 같은 자리). 예외는 네이티브가 이 심보다
    /// **먼저** 도착해 대기 자리에 두고 간 값 하나뿐이고, 그 값도 문자열일 때만 받는다.
    func testTokenStartsEmptyUnlessNativePushedEarly() {
        XCTAssertTrue(source.contains(#"typeof window.\#(BridgeUserScript.pendingSlotName) === "string""#))
        XCTAssertTrue(source.contains(#": "";"#))
        XCTAssertTrue(source.contains("getSessionToken: function(){ return token; }"))
    }

    /// 대기 자리는 읽는 즉시 비운다 — 심이 돈 뒤에도 남아 있으면 페이지가 그 자리에서
    /// 토큰을 다시 읽어 갈 수 있다.
    func testThePendingSlotIsClearedByTheShim() {
        XCTAssertTrue(source.contains("delete window.\(BridgeUserScript.pendingSlotName);"))
    }

    /// 토큰을 밀어 넣는 자리는 페이지가 가로챌 수 없어야 한다.
    func testTheSetterIsNonWritableAndNonConfigurable() {
        XCTAssertTrue(source.contains(#"Object.defineProperty(window, "\#(BridgeUserScript.setterName)""#))
        XCTAssertTrue(source.contains("writable: false, configurable: false"))
        // 브리지 객체 자체도 얼려 메서드 교체를 막는다.
        XCTAssertTrue(source.contains("Object.freeze("))
    }

    /// 문자열이 아닌 값을 setter에 넣어도 토큰 자리가 오염되지 않는다.
    func testTheSetterCoercesNonStringsToEmpty() {
        XCTAssertTrue(source.contains(#"token = (typeof t === "string") ? t : "";"#))
    }

    /// `isStandaloneWeb()`은 `window.AccenturyBridge === undefined`를 페이지 스크립트 시점에
    /// 본다 (§12.1) — 우리 주입이 그보다 먼저여야 하고, iframe에는 가지 않아야 한다.
    func testInjectionRunsAtDocumentStartOnTheMainFrameOnly() {
        let script = BridgeUserScript.makeUserScript()
        XCTAssertEqual(.atDocumentStart, script.injectionTime)
        XCTAssertTrue(script.isForMainFrameOnly)
        XCTAssertEqual(source, script.source)
    }

    /// 이미 브리지가 있으면 아무 일도 하지 않는다 — 유저 스크립트가 두 번 등록되는 경로에서
    /// 토큰 변수가 새 것으로 갈려 이미 밀어 넣은 값이 사라지면 안 된다.
    func testInjectionIsIdempotentByGuard() {
        XCTAssertTrue(source.contains("if (window.AccenturyBridge) return;"))
    }

    /// 상태를 바꾸는 네 메서드만 네이티브로 넘어간다. 값을 돌려주는 둘은 JS 안에서 끝난다.
    func testOnlyStateChangingMethodsPostToNative() {
        XCTAssertTrue(source.contains("window.webkit.messageHandlers.\(BridgeUserScript.messageHandlerName).postMessage"))
        for method in ["requestMicPermission", "startRetest"] {
            XCTAssertTrue(source.contains(#"post("\#(method)")"#))
        }
        for method in ["startVoiceItem", "shareResult"] {
            XCTAssertTrue(source.contains(#"post("\#(method)", String(json))"#))
        }
    }

    // MARK: 토큰 주입 JS

    /// 주입 JS가 토큰을 실어 나르는 모양. setter가 이미 있으면 부르고, 없으면 대기 자리에 둔다 —
    /// `.atDocumentStart` 유저 스크립트가 `evaluateJavaScript`보다 먼저 돈다는 보장이 없어서다.
    private func expectedPush(_ literal: String) -> String {
        "(function(){ var t = \(literal);"
            + " if (typeof window.__accenturySetSessionToken === \"function\")"
            + " { window.__accenturySetSessionToken(t); }"
            + " else { window.__accenturyPendingSessionToken = t; } })();"
    }

    func testTokenPushCarriesAJsonLiteral() {
        XCTAssertEqual(expectedPush(#""st_abc123""#), BridgeUserScript.sessionTokenPushJs("st_abc123"))
    }

    /// 토큰은 서버가 발급한 불투명 문자열이라 형식을 앱이 정하지 않는다 — 따옴표 하나로
    /// 구문이 깨지면 그 문서의 브리지가 통째로 죽는다.
    func testTokenPushEscapesQuotesAndBackslashes() {
        XCTAssertEqual(
            expectedPush(#""a\"b\\c""#),
            BridgeUserScript.sessionTokenPushJs("a\"b\\c")
        )
    }

    /// U+2028·U+2029는 JSON 문자열 안에서는 합법이지만 JS 소스에서는 줄 종결자다.
    /// 그대로 남으면 주입한 코드가 데이터 내용 때문에 파싱되지 않는다.
    func testTokenPushEscapesJsLineTerminators() {
        let js = BridgeUserScript.sessionTokenPushJs("a\u{2028}b\u{2029}c")
        XCTAssertEqual(expectedPush("\"a\\u2028b\\u2029c\""), js)
        // 리터럴 안에 진짜 줄 종결자가 남지 않았다.
        XCTAssertFalse(js.unicodeScalars.contains(where: { $0 == "\u{2028}" || $0 == "\u{2029}" }))
    }

    /// 제어문자는 Core의 인코더가 kotlinx와 같은 표기로 접는다 — 두 플랫폼의 주입 JS가
    /// 한 글자도 달라지지 않아야 한다는 규칙이 여기까지 이어진다.
    func testTokenPushUsesTheCoreEncoderForControlCharacters() {
        XCTAssertEqual(
            expectedPush("\"a\\nb\\tc\\u0001\""),
            BridgeUserScript.sessionTokenPushJs("a\nb\tc\u{01}")
        )
    }

    func testEmptyTokenIsAValidPush() {
        // 세션이 아직 없을 때도 밀 수 있어야 한다 — 웹 래퍼가 빈 값을 null로 정규화한다.
        XCTAssertEqual(expectedPush(#""""#), BridgeUserScript.sessionTokenPushJs(""))
    }

    // MARK: 주입 JS를 실제로 실행해 본다 (JavaScriptCore)

    /// 위의 테스트들이 보는 것은 **문자열의 모양**이다. 실기기 결함은 모양이 아니라 **실행
    /// 순서**에서 났다 — push가 먼저 돌아 `window.__accenturySetSessionToken`이 없었고, 호출이
    /// 조용히 던져 토큰이 `""`로 남았다(`completionHandler`가 nil이라 실패가 보이지도 않았다).
    /// 그래서 여기서는 JS를 실제로 돌려 두 순서 모두에서 토큰이 도착하는지 본다.
    ///
    /// WKWebView가 아니라 `JSContext`인 이유는 이 스크립트가 WebKit에 기대는 것이 없기 때문이다 —
    /// `window`는 우리가 만들어 주면 되고, `webkit.messageHandlers`는 호출되지 않는 함수 몸 안에만 있다.
    private func makeContext(file: StaticString = #filePath, line: UInt = #line) -> JSContext {
        let context = JSContext()!
        context.exceptionHandler = { _, exception in
            XCTFail("JS 예외: \(exception?.toString() ?? "?")", file: file, line: line)
        }
        context.evaluateScript("var window = {};")
        return context
    }

    private func token(in context: JSContext) -> String? {
        context.evaluateScript("window.AccenturyBridge.getSessionToken()")?.toString()
    }

    /// 정상 순서: 심이 먼저, push가 나중. setter가 있으니 그대로 불린다.
    func testPushReachesTheDocumentWhenTheShimRanFirst() {
        let context = makeContext()
        context.evaluateScript(BridgeUserScript.source)
        XCTAssertEqual("", token(in: context))

        context.evaluateScript(BridgeUserScript.sessionTokenPushJs("st_after"))
        XCTAssertEqual("st_after", token(in: context))
        // 정상 순서에서는 대기 자리를 쓰지 않는다.
        XCTAssertTrue(
            context.evaluateScript("typeof window.__accenturyPendingSessionToken === 'undefined'").toBool()
        )
    }

    /// 실기기에서 실측한 순서: push가 먼저, 심이 나중. setter가 없으니 대기 자리에 두고,
    /// 뒤늦게 도는 심이 그 값을 시작값으로 든다. 이 테스트가 곧 결함의 회귀 방지다.
    func testPushSurvivesWhenItRunsBeforeTheShim() {
        let context = makeContext()
        context.evaluateScript(BridgeUserScript.sessionTokenPushJs("st_before"))
        // setter가 없어도 던지지 않고 값을 남긴다.
        XCTAssertEqual("st_before", context.evaluateScript("window.__accenturyPendingSessionToken")?.toString())

        context.evaluateScript(BridgeUserScript.source)
        XCTAssertEqual("st_before", token(in: context))
        // 읽은 자리는 비운다 — 심이 돈 뒤에 남아 있을 이유가 없다.
        XCTAssertTrue(
            context.evaluateScript("typeof window.__accenturyPendingSessionToken === 'undefined'").toBool()
        )
    }

    /// 두 번 밀어도 마지막 값이 남는다. `didCommit`과 `didFinish`가 같은 문서에 각각 한 번씩
    /// 미는 구조라(``shouldPushToken``의 `forced`) 멱등성이 전제다.
    func testPushingTwiceIsIdempotent() {
        let context = makeContext()
        context.evaluateScript(BridgeUserScript.sessionTokenPushJs("st_x"))
        context.evaluateScript(BridgeUserScript.source)
        context.evaluateScript(BridgeUserScript.sessionTokenPushJs("st_x"))
        XCTAssertEqual("st_x", token(in: context))
    }

    /// 페이지가 대기 자리에 문자열이 아닌 것을 심어 둬도 토큰 자리가 오염되지 않는다.
    /// setter의 `typeof t === "string"` 검사와 같은 자리다.
    func testANonStringPendingValueIsIgnored() {
        let context = makeContext()
        context.evaluateScript("window.__accenturyPendingSessionToken = { toString: function(){ return 'x'; } };")
        context.evaluateScript(BridgeUserScript.source)
        XCTAssertEqual("", token(in: context))
    }

    /// 따옴표·역슬래시·줄 종결자가 든 토큰도 **실행**을 견딘다 — 리터럴 인코딩이 모양만
    /// 맞추고 파싱에서 깨지는 경우를 여기서 잡는다.
    func testAwkwardTokensSurviveExecution() {
        for awkward in ["a\"b\\c", "a\u{2028}b\u{2029}c", "a\nb\tc\u{01}", ""] {
            let context = makeContext()
            context.evaluateScript(BridgeUserScript.source)
            context.evaluateScript(BridgeUserScript.sessionTokenPushJs(awkward))
            XCTAssertEqual(awkward, token(in: context))
        }
    }
}
