import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/bridge/ItemResultDeliveryTest.kt`의 1:1 이식본.
/// 주입 JS는 웹이 그대로 평가하는 문자열이라, 두 플랫폼이 한 글자도 다르지 않아야 한다.
final class ItemResultDeliveryTests: XCTestCase {

    /// payload 리터럴을 감싸는 고정 문구. 생성기와 이 파일이 함께 움직이는 지점이다.
    private let callPrefix = "return false;f("
    private let callSuffix = ");return true;})()"

    private let lineSeparator = "\u{2028}"
    private let paragraphSeparator = "\u{2029}"

    private func resultWith(_ analysisJobId: String) -> ItemResult {
        ItemResult(
            itemId: "item_1",
            attemptId: "at-1",
            analysisJobId: analysisJobId,
            durationMs: 4_200,
            qualityStatus: .normal
        )
    }

    /// 생성된 JS에서 문자열 리터럴만 떼어 원래 JSON 텍스트로 되돌린다.
    /// 웹이 하는 일(리터럴 해석 → JSON.parse)을 흉내 내는 것이 왕복 검증의 요점이다.
    ///
    /// 고정 문구를 경계로 삼는다 — 괄호 세기로는 안 된다. 감싸는 즉시실행 함수에도, payload 값
    /// 자체에도 괄호가 들어갈 수 있다.
    private func decodePayload(_ js: String) -> String {
        guard let prefixRange = js.range(of: callPrefix),
              let suffixRange = js.range(of: callSuffix, options: .backwards)
        else {
            XCTFail("고정 문구를 찾지 못했다: \(js)")
            return ""
        }
        let literal = String(js[prefixRange.upperBound..<suffixRange.lowerBound])
        // 최상위 문자열 리터럴을 배열 한 겹에 넣어 JSON 파서로 되돌린다.
        let decoded = (try? JSONSerialization.jsonObject(with: Data("[\(literal)]".utf8))) as? [String]
        XCTAssertNotNil(decoded, "문자열 리터럴이 아니다: \(literal)")
        return decoded?.first ?? ""
    }

    /// 왕복 후 값을 꺼내 본다. analysisJobId를 통로로 쓴다 — 값 제약이 없는 문자열 필드다.
    private func roundTrip(_ analysisJobId: String) -> String {
        let decoded = decodePayload(itemResultDeliveryJs(resultWith(analysisJobId)))
        let object = (try? JSONSerialization.jsonObject(with: Data(decoded.utf8))) as? [String: Any]
        return object?["analysisJobId"] as? String ?? ""
    }

    /*
     * 수신자가 없어도 무해해야 하고(웹의 수신 지점 설치와 결과 도착은 순서가 보장되지 않는다),
     * 넘겼는지 여부가 돌려주는 값으로 구분돼야 한다 (KAN-146). 호출자가 이 값으로 녹음 화면을 놓을
     * 때를 정하므로, 못 넘긴 것을 넘긴 것으로 읽으면 화면이 앞 문항의 대기 화면 위로 걷힌다.
     */
    func testReturnsFalseWithoutCallingWhenReceiverIsMissing() {
        let js = itemResultDeliveryJs(resultWith("aj_1"))

        XCTAssertTrue(js.contains("var f=window.AccenturyWeb&&window.AccenturyWeb.onItemResult;"), js)
        XCTAssertTrue(js.contains("if(!f)return false;"), js)
    }

    func testReturnsTrueOnlyWhenDelivered() {
        let js = itemResultDeliveryJs(resultWith("aj_1"))

        // 호출과 true 반환이 한 덩어리다 — 호출 없이 true가 나가는 경로가 없어야 한다.
        XCTAssertTrue(js.hasSuffix(callSuffix), js)
        XCTAssertEqual(1, js.components(separatedBy: "return true").count - 1)
    }

    func testPayloadRidesAsStringLiteralNotObject() {
        let js = itemResultDeliveryJs(resultWith("aj_1"))

        // 리터럴을 되돌린 결과가 곧 계약 JSON이어야 한다.
        let object = (try? JSONSerialization.jsonObject(with: Data(decodePayload(js).utf8))) as? [String: Any]
        XCTAssertEqual("aj_1", object?["analysisJobId"] as? String)
        XCTAssertEqual("NORMAL", object?["qualityStatus"] as? String)
        XCTAssertEqual(4_200, object?["durationMs"] as? Int)
    }

    func testQuotesBackslashesAndNewlinesRoundTripUnchanged() {
        // 리터럴을 조기 종료시키거나 주입으로 이어질 수 있는 문자들.
        let nasty = "aj\"1\\2\n3\t4'5"

        XCTAssertEqual(nasty, roundTrip(nasty))
    }

    func testJsLineTerminatorsNeverAppearRawInTheSource() {
        // U+2028·U+2029는 JSON에선 합법이라 직렬화기가 손대지 않는다. JS 소스로 나가는 이 자리에서만
        // 문제가 되므로, 생성된 코드에는 이스케이프된 형태로만 있어야 한다.
        let js = itemResultDeliveryJs(resultWith("aj\(lineSeparator)1\(paragraphSeparator)2"))

        XCTAssertNil(js.range(of: lineSeparator))
        XCTAssertNil(js.range(of: paragraphSeparator))
        XCTAssertTrue(js.contains("\\u2028"), js)
        XCTAssertTrue(js.contains("\\u2029"), js)
    }

    func testEscapedLineTerminatorsRestoreToOriginalCharactersAsValues() {
        let separators = "aj\(lineSeparator)1\(paragraphSeparator)2"

        XCTAssertEqual(separators, roundTrip(separators))
    }

    // MARK: - 두 플랫폼의 리터럴이 같은지 (스위프트 쪽 추가)

    /// 안드로이드는 kotlinx의 문자열 직렬화를 재사용하고 이쪽은 손으로 적었다. 그 표가 어긋나면
    /// 같은 값이 다른 리터럴로 나가므로, kotlinx `ESCAPE_STRINGS`의 규칙을 그대로 못 박는다.
    func testStringLiteralMatchesKotlinxEscapeTable() {
        XCTAssertEqual("\"a\\\"b\\\\c\"", jsonStringLiteral("a\"b\\c"))
        XCTAssertEqual("\"\\b\\t\\n\\f\\r\"", jsonStringLiteral("\u{08}\u{09}\u{0A}\u{0C}\u{0D}"))
        // 단축 표기가 없는 제어문자는 소문자 4자리 \u00xx다.
        XCTAssertEqual("\"\\u0000\\u001b\\u001f\"", jsonStringLiteral("\u{00}\u{1B}\u{1F}"))
        // kotlinx는 `/`도 0x7F도 건드리지 않는다 — Foundation 기본값(`\/`)과 갈리는 지점이다.
        XCTAssertEqual("\"a/b\u{7F}\"", jsonStringLiteral("a/b\u{7F}"))
        // 한글·이모지는 그대로 통과한다(안드로이드도 이스케이프하지 않는다).
        XCTAssertEqual("\"마! 🎤\"", jsonStringLiteral("마! 🎤"))
    }

    /// 결과 payload에 URL이 실려도 `/`가 이스케이프되지 않는다 — 웹이 받는 문자열이 갈리면 안 된다.
    func testPayloadJsonDoesNotEscapeSlashes() {
        XCTAssertTrue(itemResultDeliveryJs(resultWith("aj/1")).contains("aj/1"))
    }
}
