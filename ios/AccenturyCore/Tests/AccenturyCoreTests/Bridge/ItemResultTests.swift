import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/bridge/ItemResultTest.kt`의 1:1 이식본.
final class ItemResultTests: XCTestCase {

    private let meta = ItemAttempt(
        itemId: "item_1",
        attemptId: "at-1",
        durationMs: 4_200,
        quality: .normal
    )

    /// 생성된 JSON을 키·값으로 훑어보기 위한 사전 변환.
    private func jsonObject(_ json: String) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
    }

    func testOnlyFinishedUploadBecomesFiveFieldResult() {
        let result = assembleItemResult(meta: meta, uploads: ["at-1": .done(analysisJobId: "aj_1")])

        XCTAssertEqual(
            ItemResult(
                itemId: "item_1",
                attemptId: "at-1",
                analysisJobId: "aj_1",
                durationMs: 4_200,
                qualityStatus: .normal
            ),
            result
        )
    }

    func testInFlightFailedOrUnknownKeyHasNothingToGiveYet() {
        XCTAssertNil(assembleItemResult(meta: meta, uploads: ["at-1": .inFlight]))
        XCTAssertNil(
            assembleItemResult(
                meta: meta,
                uploads: ["at-1": .failed(retryable: true, message: "timeout")]
            )
        )
        XCTAssertNil(assembleItemResult(meta: meta, uploads: ["at-2": .done(analysisJobId: "aj_2")]))
        XCTAssertNil(assembleItemResult(meta: meta, uploads: [:]))
    }

    func testSerializedKeysAreExactlyTheContractedFive() {
        let result = assembleItemResult(meta: meta, uploads: ["at-1": .done(analysisJobId: "aj_1")])!

        let keys = Set(jsonObject(result.toJson()).keys)

        XCTAssertEqual(
            ["itemId", "attemptId", "analysisJobId", "durationMs", "qualityStatus"],
            keys
        )
    }

    func testQualityStatusGoesOutAsEnumNameString() {
        let quiet = assembleItemResult(
            meta: ItemAttempt(
                itemId: meta.itemId,
                attemptId: meta.attemptId,
                durationMs: meta.durationMs,
                quality: .tooQuiet
            ),
            uploads: ["at-1": .done(analysisJobId: "aj_1")]
        )!

        let object = jsonObject(quiet.toJson())

        XCTAssertEqual("TOO_QUIET", object["qualityStatus"] as? String)
        // 안드로이드는 Long을 그대로 숫자로 내보낸다 — 문자열로 실리면 웹 파서가 거른다.
        XCTAssertTrue(quiet.toJson().contains("\"durationMs\":4200"))
    }
}
