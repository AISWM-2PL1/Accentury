import XCTest
@testable import AccenturyCore

/// 안드로이드 `AdRequestsTest`의 이식본 (KAN-196). `personalizationAllowed` 한 줄이 곧 동의의 법적 의미다.
final class AdRequestsTests: XCTestCase {

    func testOnlyGrantedIsPersonalized() {
        XCTAssertTrue(personalizationAllowed(.granted))
    }

    func testDeniedIsNonPersonalized() {
        XCTAssertFalse(personalizationAllowed(.denied))
    }

    func testNotYetChosenIsNonPersonalized() {
        // 시트 전에는 요청 자체가 없어야 하지만(AdsController), 어떤 경로로든 나간다면 npa다 —
        // "모르면 맞춤형"은 동의 없이 개인화하는 것이라 방침과 어긋난다 (§8.5).
        XCTAssertFalse(personalizationAllowed(.unknown))
    }
}
