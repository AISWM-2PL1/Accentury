import XCTest
@testable import AccenturyCore

/// 계측 파라미터 좁히기 (KAN-33). 안드로이드 `EventParamsTest`와 같은 규칙을 검증한다 —
/// 두 플랫폼이 같은 GA4 속성에 쌓이므로 한쪽만 관대하면 그 축이 조용히 갈린다.
final class EventParamsTests: XCTestCase {

    // MARK: 이름 규칙

    func testAcceptsOurSchemaNames() {
        for name in [
            "referral_opened", "item_shown", "recording_retake", "analysis_wait_duration",
            "tier_assigned", "share_clicked", "share_launched", "duration_ms", "overall_bucket",
        ] {
            XCTAssertTrue(isAnalyticsName(name), name)
        }
    }

    /// 대문자를 막는 것이 이 판정의 요점이다. GA4는 이름의 대소문자를 구분하므로 `Item_Shown`이
    /// 그대로 흘러가면 `item_shown`과 별개의 축으로 쌓여 두 지표가 조용히 갈린다.
    func testRejectsNamesOutsideTheLowercaseSnakeCaseRule() {
        for name in ["", "Item_Shown", "1item", "_item", "item shown", "item-shown", "아이템"] {
            XCTAssertFalse(isAnalyticsName(name), name)
        }
    }

    func testRejectsNamesLongerThanForty() {
        XCTAssertTrue(isAnalyticsName(String(repeating: "a", count: 40)))
        XCTAssertFalse(isAnalyticsName(String(repeating: "a", count: 41)))
    }

    /// SDK가 자기 몫으로 예약한 접두사. 우리가 보내면 SDK 단에서 거부된다.
    func testRejectsReservedPrefixes() {
        for name in ["firebase_x", "google_x", "ga_x"] {
            XCTAssertFalse(isAnalyticsName(name), name)
        }
    }

    // MARK: 값의 타입

    /// **이 티켓의 AC가 걸린 자리다.** 숫자를 문자열로 뭉개면 GA4에서 측정항목이 아니라 차원이
    /// 되어 대기 시간의 평균·P95를 낼 수 없다.
    func testKeepsIntegersAndDecimalsApart() {
        let params = parseEventParams(#"{"duration_ms":12345,"item_seq":3,"ratio":0.5,"tier_code":"A"}"#)
        XCTAssertEqual(
            [
                "duration_ms": .count(12_345),
                "item_seq": .count(3),
                "ratio": .amount(0.5),
                "tier_code": .text("A"),
            ],
            params
        )
    }

    /// 불리언이 정수 1로 실리면 축이 통째로 뒤바뀐다 — `JSONSerialization`이 `true`를 `NSNumber`로
    /// 주기 때문에 판정 순서가 곧 규칙이다.
    func testBooleansBecomeTextNotOne() {
        XCTAssertEqual(["flag": .text("true")], parseEventParams(#"{"flag":true}"#))
        XCTAssertEqual(["flag": .text("false")], parseEventParams(#"{"flag":false}"#))
    }

    /// null은 값 없이 지나간다. 빈 문자열을 넣으면 그 자리가 값 하나로 세어져, 유입 없는 실행과
    /// 빈 캠페인이 GA4에서 섞인다 (웹 스키마의 `campaign`이 null일 수 있다).
    func testNullsAreDroppedRatherThanTurnedIntoEmptyStrings() {
        XCTAssertEqual(["item_seq": .count(1)], parseEventParams(#"{"campaign":null,"item_seq":1}"#))
    }

    /// 중첩은 실을 자리가 없다 — Firebase의 이벤트 파라미터는 평평하다.
    func testNestedValuesAreDropped() {
        XCTAssertEqual(["item_seq": .count(1)], parseEventParams(#"{"a":{"b":1},"c":[1,2],"item_seq":1}"#))
    }

    /// 100자를 넘는 값은 자르지 않고 버린다. 잘린 값은 원래 값과 다른 집계 축이 되는데, 그 사실이
    /// 대시보드에는 드러나지 않는다.
    func testOverlongValuesAreDroppedNotTruncated() {
        let long = String(repeating: "x", count: 101)
        XCTAssertEqual([:], parseEventParams(#"{"tier_code":"\#(long)"}"#))
        XCTAssertEqual(
            ["tier_code": .text(String(repeating: "x", count: 100))],
            parseEventParams(#"{"tier_code":"\#(String(repeating: "x", count: 100))"}"#)
        )
    }

    /// 값 하나가 규격 밖이어도 나머지는 실어 보낸다 — 파라미터 하나 때문에 사건 자체를 잃으면
    /// 퍼널 카운트가 줄고, 그 손실은 대시보드에서 "일어나지 않은 일"과 구분되지 않는다.
    func testBadNamesAreSkippedButTheEventSurvives() {
        XCTAssertEqual(["reason": .text("USER")], parseEventParams(#"{"Channel":"kakao","reason":"USER"}"#))
    }

    /// GA4 상한(25개)에서 멈춘다. 무엇이 남을지가 실행마다 달라지면 안 돼서 이름순으로 자른다 —
    /// 안드로이드는 문서 순서를 지키지만 Swift 딕셔너리에는 그 순서가 없다.
    func testStopsAtTheGa4ParameterLimit() {
        let pairs = (0..<30).map { "\"p\(String(format: "%02d", $0))\":\($0)" }.joined(separator: ",")
        let params = parseEventParams("{\(pairs)}")
        XCTAssertEqual(25, params?.count)
        XCTAssertNotNil(params?["p00"])
        XCTAssertNil(params?["p25"])
    }

    // MARK: 이벤트째 버리는 경우

    func testMalformedOrNonObjectJsonReturnsNil() {
        for json in ["", "{oops", "[]", "\"x\"", "1", "null"] {
            XCTAssertNil(parseEventParams(json), json)
        }
    }

    func testAnEmptyObjectIsAValidEventWithNoParameters() {
        XCTAssertEqual([:], parseEventParams("{}"))
    }

    // MARK: 공유 통로 표기

    /// 값 표기가 안드로이드 `channelParam`과 한 글자도 다르면 안 된다 — 집계 축이라 한 번 쌓이면
    /// 이름을 바꿀 수 없다.
    func testChannelParamMatchesTheAgreedSpelling() {
        XCTAssertEqual("kakao", channelParam(.kakao))
        XCTAssertEqual("system_sheet", channelParam(.systemSheet))
    }
}
