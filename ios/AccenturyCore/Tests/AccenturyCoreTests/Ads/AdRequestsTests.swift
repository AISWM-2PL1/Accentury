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

    // MARK: shouldRequestAds — "요청이 나가도 되는가". 게이트의 preload()와 허브의 프리로드가 같은 줄을 쓴다 (P1-1).

    func testGrantedRequests() {
        XCTAssertTrue(shouldRequestAds(.granted))
    }

    func testDeniedStillRequestsNonPersonalized() {
        // 거부는 "맞춤형을 하지 말라"이지 "광고를 내지 말라"가 아니다. 요청은 npa로 나간다.
        XCTAssertTrue(shouldRequestAds(.denied))
    }

    func testNotYetChosenDoesNotRequest() {
        // 시트가 뜨기 전이다. npa를 붙여도 "묻기 전에 광고 서버와 통신했다"가 된다 (§8.5).
        XCTAssertFalse(shouldRequestAds(.unknown))
    }

    // MARK: shouldRequestTracking — ATT 프롬프트 순서 (ads-admob.md §7.5, P2-6)

    func testGrantedAndNotYetAskedRequestsTracking() {
        // 시트에서 허용을 고른 직후 — 프롬프트가 "왜 지금 묻는지"를 가지는 유일한 자리다.
        XCTAssertTrue(shouldRequestTracking(consent: .granted, status: .notDetermined))
    }

    func testGrantedButAlreadyAnsweredDoesNotAskAgain() {
        // 이미 답이 있으면 iOS가 다시 띄우지 않는다 — 허용·거부·제한 셋 다.
        XCTAssertFalse(shouldRequestTracking(consent: .granted, status: .authorized))
        XCTAssertFalse(shouldRequestTracking(consent: .granted, status: .denied))
        XCTAssertFalse(shouldRequestTracking(consent: .granted, status: .restricted))
    }

    func testDeniedNeverAsks() {
        // 추적 자체가 없는데 추적 허용을 묻지 않는다. npa 요청은 IDFA가 필요 없다.
        XCTAssertFalse(shouldRequestTracking(consent: .denied, status: .notDetermined))
    }

    func testNotYetChosenNeverAsks() {
        // 시트가 먼저다 — 시트 전에 ATT가 뜨면 프롬프트에 맥락이 없다.
        XCTAssertFalse(shouldRequestTracking(consent: .unknown, status: .notDetermined))
    }
}
