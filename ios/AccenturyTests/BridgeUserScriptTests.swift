import AccenturyCore
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
    /// (안드로이드 `AtomicBoolean(false)` 시작값과 같은 자리).
    func testTokenStartsEmpty() {
        XCTAssertTrue(source.contains(#"var token = "";"#))
        XCTAssertTrue(source.contains("getSessionToken: function(){ return token; }"))
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

    func testTokenPushCallsTheSetterWithAJsonLiteral() {
        XCTAssertEqual(
            #"window.__accenturySetSessionToken("st_abc123");"#,
            BridgeUserScript.sessionTokenPushJs("st_abc123")
        )
    }

    /// 토큰은 서버가 발급한 불투명 문자열이라 형식을 앱이 정하지 않는다 — 따옴표 하나로
    /// 구문이 깨지면 그 문서의 브리지가 통째로 죽는다.
    func testTokenPushEscapesQuotesAndBackslashes() {
        XCTAssertEqual(
            #"window.__accenturySetSessionToken("a\"b\\c");"#,
            BridgeUserScript.sessionTokenPushJs("a\"b\\c")
        )
    }

    /// U+2028·U+2029는 JSON 문자열 안에서는 합법이지만 JS 소스에서는 줄 종결자다.
    /// 그대로 남으면 주입한 코드가 데이터 내용 때문에 파싱되지 않는다.
    func testTokenPushEscapesJsLineTerminators() {
        let token = "a\u{2028}b\u{2029}c"
        let js = BridgeUserScript.sessionTokenPushJs(token)
        XCTAssertEqual("window.__accenturySetSessionToken(\"a\\u2028b\\u2029c\");", js)
        // 리터럴 안에 진짜 줄 종결자가 남지 않았다.
        XCTAssertFalse(js.unicodeScalars.contains(where: { $0 == "\u{2028}" || $0 == "\u{2029}" }))
    }

    /// 제어문자는 Core의 인코더가 kotlinx와 같은 표기로 접는다 — 두 플랫폼의 주입 JS가
    /// 한 글자도 달라지지 않아야 한다는 규칙이 여기까지 이어진다.
    func testTokenPushUsesTheCoreEncoderForControlCharacters() {
        let js = BridgeUserScript.sessionTokenPushJs("a\nb\tc\u{01}")
        XCTAssertEqual("window.__accenturySetSessionToken(\"a\\nb\\tc\\u0001\");", js)
    }

    func testEmptyTokenIsAValidPush() {
        // 세션이 아직 없을 때도 밀 수 있어야 한다 — 웹 래퍼가 빈 값을 null로 정규화한다.
        XCTAssertEqual(#"window.__accenturySetSessionToken("");"#, BridgeUserScript.sessionTokenPushJs(""))
    }
}
