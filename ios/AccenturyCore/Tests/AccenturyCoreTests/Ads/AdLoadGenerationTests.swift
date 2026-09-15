import XCTest
@testable import AccenturyCore

/// 안드로이드 `AdLoadGenerationTest`의 이식본 (KAN-196 리뷰 P1-2).
final class AdLoadGenerationTests: XCTestCase {

    func testACurrentTokenPasses() {
        let generation = AdLoadGeneration()
        let token = generation.begin()
        XCTAssertTrue(generation.isCurrent(token))
    }

    func testAfterInvalidationTheOldTokenIsRejected() {
        // 동의가 바뀌어 discard()가 불린 뒤 도착하는 옛 조건의 로드 결과가 여기다 — 성공·실패 둘 다 버린다.
        let generation = AdLoadGeneration()
        let token = generation.begin()
        generation.invalidate()
        XCTAssertFalse(generation.isCurrent(token))
    }

    func testALoadBegunAfterInvalidationPasses() {
        // 새 조건으로 다시 나간 요청은 새 세대다. 옛 토큰은 여전히 거부된다.
        let generation = AdLoadGeneration()
        let stale = generation.begin()
        generation.invalidate()
        let fresh = generation.begin()
        XCTAssertTrue(generation.isCurrent(fresh))
        XCTAssertFalse(generation.isCurrent(stale))
    }
}
