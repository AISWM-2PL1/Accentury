import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/bridge/SharePayloadTest.kt`의 1:1 이식본.
final class SharePayloadTests: XCTestCase {

    /// 계약을 채운 payload. 테스트마다 관심 있는 필드만 갈아끼운다.
    private func payload(
        imageUrl: String = "https://cdn.accentury.app/share/grade-a.png",
        text: String = "내 사투리 등급은 '경상도 원어민'!",
        webTestUrl: String = "https://accentury.app/?utm_source=kakao",
        extra: String = ""
    ) -> String {
        "{\"imageUrl\":\"\(imageUrl)\",\"text\":\"\(text)\",\"webTestUrl\":\"\(webTestUrl)\"\(extra)}"
    }

    func testContractPayloadParsesValuesThrough() {
        XCTAssertEqual(
            SharePayload(
                imageUrl: "https://cdn.accentury.app/share/grade-a.png",
                text: "내 사투리 등급은 '경상도 원어민'!",
                webTestUrl: "https://accentury.app/?utm_source=kakao"
            ),
            parseSharePayload(payload())
        )
    }

    func testUnknownFieldsAreAcceptedBecauseAddingFieldsIsBackwardCompatible() {
        // 신버전 웹 + 구버전 앱 조합. 버전을 올리지 않는 변경이라 실제로 존재하는 조합이다 (§5).
        // 점수 같은 값이 나중에 붙어도 네이티브는 계약에 있는 셋만 읽는다.
        let parsed = parseSharePayload(payload(extra: ",\"score\":87,\"sessionId\":\"s_1\""))

        XCTAssertEqual("https://accentury.app/?utm_source=kakao", parsed?.webTestUrl)
    }

    func testNonJsonOrMissingFieldsAreIgnored() {
        XCTAssertNil(parseSharePayload(""))
        XCTAssertNil(parseSharePayload("{oops"))
        XCTAssertNil(parseSharePayload("[]"))
        XCTAssertNil(parseSharePayload("{\"imageUrl\":\"https://a/b.png\"}"))
    }

    func testEmptyValuesCannotMakeACardSoTheyAreIgnored() {
        XCTAssertNil(parseSharePayload(payload(text: "")))
        XCTAssertNil(parseSharePayload(payload(text: "   ")))
        XCTAssertNil(parseSharePayload(payload(imageUrl: "")))
        XCTAssertNil(parseSharePayload(payload(webTestUrl: "")))
    }

    func testNonHttpsLinksAreRejected() {
        // 이 값들은 화면에 그려지고 마는 게 아니라 남의 대화방까지 간다. 스킴을 열어 두면
        // 우리 앱이 임의 동작을 여는 링크의 배달부가 된다.
        XCTAssertNil(parseSharePayload(payload(webTestUrl: "javascript:alert(1)")))
        XCTAssertNil(parseSharePayload(payload(imageUrl: "javascript:alert(1)")))
        XCTAssertNil(parseSharePayload(payload(webTestUrl: "intent://evil#Intent;end")))
        XCTAssertNil(parseSharePayload(payload(imageUrl: "file:///data/data/com.accentury.app/x.png")))
        // http도 거부다 — 카카오가 이미지로 받지 않고, 평문 링크를 퍼뜨릴 이유도 없다.
        XCTAssertNil(parseSharePayload(payload(webTestUrl: "http://accentury.app/")))
        XCTAssertNil(parseSharePayload(payload(imageUrl: "http://cdn.accentury.app/a.png")))
        // 스킴은 앞에 있어야 한다 — 문자열 어딘가에 https가 섞인 값은 통과하지 못한다.
        XCTAssertNil(parseSharePayload(payload(webTestUrl: " https://accentury.app/")))
        // 스킴은 정확히 소문자 https다. 값을 정규화하지 않고 그대로 내보내므로 받은 그대로가 유효해야 한다.
        XCTAssertNil(parseSharePayload(payload(webTestUrl: "HTTPS://accentury.app/t")))
        XCTAssertNil(parseSharePayload(payload(imageUrl: "Https://cdn.accentury.app/a.png")))
    }

    func testLinksThatStartWithHttpsButHaveNowhereToPointAreRejected() {
        // 접두사만 맞고 host가 없는 값들. 카드에 실려도 어디로도 가지 못하고, 카카오·공유 시트가
        // 이런 값을 어떻게 다루는지는 받는 쪽 구현에 달려 있다.
        XCTAssertNil(parseSharePayload(payload(webTestUrl: "https://")))
        XCTAssertNil(parseSharePayload(payload(imageUrl: "https://")))
        XCTAssertNil(parseSharePayload(payload(webTestUrl: "https:///t")))
        XCTAssertNil(parseSharePayload(payload(imageUrl: "https:///a.png")))
        // 공백이 섞인 값은 URL이 아니다.
        XCTAssertNil(parseSharePayload(payload(webTestUrl: "https://accentury.app/t 1")))
        XCTAssertNil(parseSharePayload(payload(imageUrl: "https://cdn accentury.app/a.png")))
    }

    /// 눈에 보이지 않는 제어문자가 섞인 링크도 거부한다 (Codex 지적).
    ///
    /// `java.net.URI`는 DEL(U+007F)과 C1 제어문자(U+0080–U+009F)에도 예외를 던져 안드로이드는 이미
    /// 거부하고 있었다. 스위프트 `URLComponents`는 이런 값을 통과시키므로 명시적으로 막는다 —
    /// 카드에 실려 나가는 링크에 화면에 보이지 않는 문자가 끼면, 받는 쪽 구현이 그것을 어떻게
    /// 다루는지가 우리 손을 떠난다.
    func testUrlsWithInvisibleControlCharactersAreRejected() {
        XCTAssertNil(parseSharePayload(payload(webTestUrl: "https://accentury.app/t\u{7F}1")))
        XCTAssertNil(parseSharePayload(payload(imageUrl: "https://cdn.accentury.app/a\u{7F}.png")))
        // U+0085(NEL)은 C1 제어문자이자 개행이다.
        XCTAssertNil(parseSharePayload(payload(webTestUrl: "https://accentury.app/t\u{85}1")))
        XCTAssertNil(parseSharePayload(payload(imageUrl: "https://cdn.accentury.app/a\u{85}.png")))
    }

    func testNormalUrlWithCampaignParametersPassesThrough() {
        XCTAssertEqual(
            "https://accentury.app/t?c=kko_share",
            parseSharePayload(payload(webTestUrl: "https://accentury.app/t?c=kko_share"))?.webTestUrl
        )
    }

    func testTextOverKakaoTemplateLimitIsIgnored() {
        XCTAssertEqual(
            200,
            parseSharePayload(payload(text: String(repeating: "가", count: 200)))?.text.utf16.count
        )
        XCTAssertNil(parseSharePayload(payload(text: String(repeating: "가", count: 201))))
    }
}
