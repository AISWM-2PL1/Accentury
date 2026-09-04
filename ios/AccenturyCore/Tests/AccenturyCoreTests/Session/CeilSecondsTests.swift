import XCTest
@testable import AccenturyCore

/// `SessionGateController.kt`의 `ceilSeconds` 이식본 점검.
///
/// 안드로이드에는 이 함수만 보는 테스트가 없다(게이트 테스트가 2100ms → 3초로 간접 확인한다).
/// 이쪽에서는 정수 나눗셈이 두 플랫폼에서 같은 값을 내는지 경계에서 한 번 못 박는다 —
/// 사용자에게 "N초 뒤에 다시" 라고 말하는 숫자다.
final class CeilSecondsTests: XCTestCase {

    func testRoundsUpToWholeSecondsLikeTheServersRetryAfter() {
        XCTAssertEqual(0, ceilSeconds(0))
        XCTAssertEqual(1, ceilSeconds(1))
        XCTAssertEqual(1, ceilSeconds(999))
        XCTAssertEqual(1, ceilSeconds(1_000))
        XCTAssertEqual(2, ceilSeconds(1_001))
        XCTAssertEqual(3, ceilSeconds(2_100))
        XCTAssertEqual(60, ceilSeconds(60_000))
    }
}
