import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/bridge/RetestFailedDeliveryTest.kt`의 1:1 이식본.
final class RetestFailedDeliveryTests: XCTestCase {

    /// payload 리터럴을 감싸는 고정 문구. 생성기와 이 파일이 함께 움직이는 지점이다.
    private let callPrefix = "return false;f("
    private let callSuffix = ");return true;})()"

    private func failed(
        _ reason: SessionFailureReason,
        code: String? = nil,
        retryAfterMs: Int64? = nil
    ) -> RetestOutcome.Failure {
        RetestOutcome.Failure(reason: reason, code: code, retryAfterMs: retryAfterMs)
    }

    /// 생성된 JS에서 문자열 리터럴만 떼어 원래 JSON 텍스트로 되돌린다 (ItemResultDeliveryTests와 같은 방식).
    private func decodePayload(_ js: String) -> [String: Any] {
        guard let prefixRange = js.range(of: callPrefix),
              let suffixRange = js.range(of: callSuffix, options: .backwards)
        else {
            XCTFail("고정 문구를 찾지 못했다: \(js)")
            return [:]
        }
        let literal = String(js[prefixRange.upperBound..<suffixRange.lowerBound])
        guard let unwrapped = (try? JSONSerialization.jsonObject(with: Data("[\(literal)]".utf8))) as? [String],
              let json = unwrapped.first,
              let object = try? JSONSerialization.jsonObject(
                  with: Data(json.utf8),
                  options: [.fragmentsAllowed]
              ) as? [String: Any]
        else {
            XCTFail("payload를 되돌리지 못했다: \(literal)")
            return [:]
        }
        return object
    }

    // MARK: - payload 조립 (RetestFailure.swift)

    func testRateLimitedCarriesWaitTimeThroughSoWebCanWriteTheNotice() {
        let payload = retestFailurePayload(
            failed(.rateLimited, code: "RATE_LIMITED", retryAfterMs: 5_000)
        )

        XCTAssertEqual("RATE_LIMITED", payload.code)
        XCTAssertEqual(5_000, payload.retryAfterMs)
        XCTAssertTrue(payload.retryable)
    }

    func testOnlyServerPinnedNonRetryableRejectionIsNotRetryable() {
        XCTAssertFalse(retestFailurePayload(failed(.unsupported)).retryable)
        XCTAssertTrue(retestFailurePayload(failed(.network)).retryable)
        XCTAssertTrue(retestFailurePayload(failed(.server)).retryable)
        XCTAssertTrue(retestFailurePayload(failed(.rateLimited)).retryable)
    }

    func testCodeIsNilWhenEnvelopeCouldNotBeRead() {
        XCTAssertNil(retestFailurePayload(failed(.network)).code)
    }

    func testEveryReasonHasItsOwnNonEmptyMessage() {
        let messages = SessionFailureReason.allCases.map { retestFailurePayload(failed($0)).message }

        XCTAssertTrue(messages.allSatisfy { !$0.trimmingCharacters(in: .whitespaces).isEmpty }, "\(messages)")
        XCTAssertEqual(SessionFailureReason.allCases.count, Set(messages).count)
    }

    // MARK: - 주입 JS (RetestFailedDelivery.swift)

    func testReturnsFalseWithoutCallingWhenReceiverIsMissing() {
        let js = retestFailedDeliveryJs(retestFailurePayload(failed(.network)))

        XCTAssertTrue(js.contains("var f=window.AccenturyWeb&&window.AccenturyWeb.onRetestFailed;"), js)
        XCTAssertTrue(js.contains("if(!f)return false;"), js)
    }

    func testGoesToRetestSlotNotResultSlot() {
        let js = retestFailedDeliveryJs(retestFailurePayload(failed(.network)))

        // 잘못된 슬롯으로 가면 진행 화면이 실패 회신을 문항 결과로 읽으려 든다.
        XCTAssertFalse(js.contains("onItemResult"), js)
    }

    func testPayloadRidesAsStringLiteralNotObject() {
        let js = retestFailedDeliveryJs(
            retestFailurePayload(failed(.rateLimited, code: "RATE_LIMITED", retryAfterMs: 5_000))
        )

        let object = decodePayload(js)
        XCTAssertEqual("RATE_LIMITED", object["code"] as? String)
        XCTAssertEqual(5_000, object["retryAfterMs"] as? Int)
        XCTAssertEqual(true, object["retryable"] as? Bool)
        XCTAssertFalse((object["message"] as? String ?? "").isEmpty)
    }

    func testMissingValuesRideAsNullRatherThanBeingOmitted() {
        let js = retestFailedDeliveryJs(retestFailurePayload(failed(.network)))

        // 웹 파서가 네 필드를 계약으로 읽는다 — 키가 빠지면 계약이 달라진다.
        let object = decodePayload(js)
        XCTAssertTrue(object.keys.contains("code"))
        XCTAssertTrue(object.keys.contains("retryAfterMs"))
        XCTAssertTrue(object["code"] is NSNull)
        XCTAssertTrue(object["retryAfterMs"] is NSNull)
    }

    func testServerTextWithQuotesOrJsLineTerminatorsDoesNotBreakInjection() {
        // code는 서버가 준 값을 그대로 싣는 자리라, 값 제약이 없는 유일한 통로다.
        let nasty = "RATE\"LIMITED\\1\n2\u{2028}3\u{2029}4"
        let js = retestFailedDeliveryJs(
            retestFailurePayload(failed(.rateLimited, code: nasty))
        )

        XCTAssertNil(js.range(of: "\u{2028}"))
        XCTAssertNil(js.range(of: "\u{2029}"))
        XCTAssertEqual(nasty, decodePayload(js)["code"] as? String)
    }
}
