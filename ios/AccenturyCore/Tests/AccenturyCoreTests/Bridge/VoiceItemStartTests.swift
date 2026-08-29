import XCTest
@testable import AccenturyCore

/// `parseVoiceItemStart`의 이식본 테스트.
///
/// 안드로이드에는 이 함수만 보는 테스트 파일이 없다 — 케이스가
/// `app/src/test/java/com/accentury/app/web/AccenturyBridgeTest.kt` 안에 브리지 호출과 섞여 있다.
/// 브리지(WKWebView `WKScriptMessageHandler`)는 §6 결선 몫이라, 여기서는 **파싱·검증 케이스만**
/// 떼어 그대로 옮긴다. origin 검증 케이스(allowlist 밖에서는 무시)는 브리지가 생길 때 함께 온다.
final class VoiceItemStartTests: XCTestCase {

    /// 계약을 채운 payload. 테스트마다 관심 있는 필드만 갈아끼운다.
    private func payload(
        itemId: String = "item_1",
        prompt: String = "마! 니 어데 가노?",
        itemNumber: Int = 1,
        totalItems: Int = 10,
        maxDurationMs: Int64 = 15_000,
        extra: String = ""
    ) -> String {
        "{\"itemId\":\"\(itemId)\",\"prompt\":\"\(prompt)\",\"itemNumber\":\(itemNumber),"
            + "\"totalItems\":\(totalItems),\"maxDurationMs\":\(maxDurationMs)\(extra)}"
    }

    func testContractPayloadParsesIntoItemContext() {
        XCTAssertEqual(
            VoiceItemStart(
                itemId: "item_1",
                prompt: "마! 니 어데 가노?",
                itemNumber: 1,
                totalItems: 10,
                maxDurationMs: 15_000
            ),
            parseVoiceItemStart(payload())
        )
    }

    func testNonJsonOrMissingFieldsAreIgnored() {
        XCTAssertNil(parseVoiceItemStart(""))
        XCTAssertNil(parseVoiceItemStart("{oops"))
        XCTAssertNil(parseVoiceItemStart("[]"))
        XCTAssertNil(parseVoiceItemStart("{\"itemId\":\"item_1\"}"))
    }

    func testValuesTheRecordingScreenCannotDrawAreIgnored() {
        XCTAssertNil(parseVoiceItemStart(payload(itemId: "")))
        XCTAssertNil(parseVoiceItemStart(payload(itemId: "   ")))
        XCTAssertNil(parseVoiceItemStart(payload(itemNumber: 0)))
        XCTAssertNil(parseVoiceItemStart(payload(totalItems: 0)))
        XCTAssertNil(parseVoiceItemStart(payload(maxDurationMs: 0)))
        XCTAssertNil(parseVoiceItemStart(payload(maxDurationMs: -1)))
        // 진행 표기가 "11/10"이 되는 조합. 정의를 읽는 쪽의 계산 착오이므로 화면을 띄우지 않는다.
        XCTAssertNil(parseVoiceItemStart(payload(itemNumber: 11, totalItems: 10)))
    }

    func testGuideF0ParsesValuesIncludingUnvoicedNulls() {
        let start = parseVoiceItemStart(
            payload(extra: ",\"guideF0\":{\"unit\":\"semitone\",\"frameIntervalMs\":10,\"values\":[0.5,null,-1.2]}")
        )

        XCTAssertEqual(
            GuideF0(unit: "semitone", frameIntervalMs: 10, values: [0.5, nil, -1.2]),
            start?.guideF0
        )
    }

    func testOlderWebPayloadWithoutGuideF0IsAccepted() {
        let start = parseVoiceItemStart(payload())

        XCTAssertEqual("item_1", start?.itemId)
        XCTAssertNil(start?.guideF0)
    }

    func testUnknownFieldsInsideGuideF0AreIgnoredEvenWithUnvoicedNulls() {
        // 밴드는 채점 층위라 네이티브 계약에 없다. 타입으로 들고 있으면 읽지도 않는 필드의
        // 형태(무성 프레임 null)가 payload 전체를 거부하게 만든다 — 그 회귀를 여기서 막는다.
        let start = parseVoiceItemStart(
            payload(
                extra: ",\"guideF0\":{\"unit\":\"semitone\",\"frameIntervalMs\":10,"
                    + "\"values\":[0.5],\"bandLow\":[null],\"bandHigh\":[1.0]}"
            )
        )

        XCTAssertEqual(GuideF0(unit: "semitone", frameIntervalMs: 10, values: [0.5]), start?.guideF0)
    }

    func testMalformedGuideF0DropsOnlyTheCurve() {
        // guideF0 내용은 웹 빌드가 아니라 서버가 발행한 정의에서 온다 — 정의 데이터 한 줄이
        // 문항 진행 전체를 막으면 안 되므로, 다른 필드와 달리 payload째 거부하지 않는다.
        let badShapes = [
            ",\"guideF0\":{\"unit\":\"semitone\",\"frameIntervalMs\":10,\"values\":[\"x\"]}", // 값 타입 불일치
            ",\"guideF0\":{\"frameIntervalMs\":10,\"values\":[0.5]}", // unit 누락
            ",\"guideF0\":{\"unit\":\"semitone\",\"frameIntervalMs\":10.5,\"values\":[0.5]}", // 정수 자리에 실수
            ",\"guideF0\":42", // 객체가 아님
        ]
        for extra in badShapes {
            let start = parseVoiceItemStart(payload(extra: extra))
            XCTAssertEqual("item_1", start?.itemId, "불량 guideF0에도 문항은 받아야 한다: \(extra)")
            XCTAssertNil(start?.guideF0, "불량 guideF0는 버려져야 한다: \(extra)")
        }
    }

    func testUnknownFieldsAreAcceptedBecauseAddingFieldsIsBackwardCompatible() {
        // 신버전 웹 + 구버전 앱 조합. 버전을 올리지 않는 변경이라 실제로 존재하는 조합이다.
        let start = parseVoiceItemStart(payload(extra: ",\"futureField\":\"whatever\""))

        XCTAssertEqual("item_1", start?.itemId)
    }

    func testQuotesAndUnicodeInPromptSurviveVerbatim() {
        let start = parseVoiceItemStart(
            "{\"itemId\":\"item_1\",\"prompt\":\"\\\"밥은\\\" 뭇나?\\n마!\",\"itemNumber\":2,"
                + "\"totalItems\":10,\"maxDurationMs\":15000}"
        )

        XCTAssertEqual("\"밥은\" 뭇나?\n마!", start?.prompt)
    }
}
