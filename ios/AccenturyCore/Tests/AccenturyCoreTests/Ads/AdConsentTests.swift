import XCTest
@testable import AccenturyCore

/// 안드로이드 `AdConsentTest`의 이식본 (KAN-196).
final class AdConsentTests: XCTestCase {

    /// 값 하나짜리 저장소. 프로덕션(UserDefaults)과 같은 계약 — 없으면 unknown.
    private final class FakeStore: AdConsentStore {
        private var saved: AdConsent?
        func read() -> AdConsent { saved ?? .unknown }
        func write(_ consent: AdConsent) { saved = consent }
    }

    func testTheThreeBridgeStringsEachReadAsAState() {
        XCTAssertEqual(.granted, AdConsent(bridgeValue: "granted"))
        XCTAssertEqual(.denied, AdConsent(bridgeValue: "denied"))
        XCTAssertEqual(.unknown, AdConsent(bridgeValue: "unknown"))
    }

    func testBridgeValuesAreTheContractVerbatim() {
        // 웹 래퍼(bridge.ts의 AD_CONSENT_VALUES)가 이 세 문자열만 계약 안으로 본다.
        XCTAssertEqual("granted", AdConsent.granted.bridgeValue)
        XCTAssertEqual("denied", AdConsent.denied.bridgeValue)
        XCTAssertEqual("unknown", AdConsent.unknown.bridgeValue)
    }

    func testStringsOutsideTheContractAreNil() {
        // 보정하지 않는다 — 대소문자·공백까지 계약이다. 보정을 시작하면 웹과 앱이 다른 계약을
        // 들고도 동작하는 상태가 생긴다.
        XCTAssertNil(AdConsent(bridgeValue: ""))
        XCTAssertNil(AdConsent(bridgeValue: "Granted"))
        XCTAssertNil(AdConsent(bridgeValue: " granted"))
        XCTAssertNil(AdConsent(bridgeValue: "true"))
        XCTAssertNil(AdConsent(bridgeValue: "null"))
    }

    func testNeverSavedReadsAsUnknown() {
        XCTAssertEqual(.unknown, FakeStore().read())
    }

    func testAWrittenValueReadsBack() {
        let store = FakeStore()
        store.write(.denied)
        XCTAssertEqual(.denied, store.read())
        store.write(.granted)
        XCTAssertEqual(.granted, store.read())
    }
}
