import XCTest
@testable import AccenturyCore

/// 프로덕션 저장소 (KAN-196). 안드로이드는 SharedPreferences라 계측기 없이 못 돌려 가짜로만
/// 검증했지만, `UserDefaults`는 Foundation이라 격리된 suite로 실제 구현까지 돌린다 — 키 이름과
/// "깨진 값은 unknown" 규칙이 곧 두 플랫폼 저장 형식의 계약이다 (ads-admob.md §7).
final class UserDefaultsAdConsentStoreTests: XCTestCase {

    private var suiteName = ""
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "AdConsentStoreTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    func testTheKeyMatchesTheAndroidFileAndKey() {
        // 안드로이드 SharedPreferences 파일 `ad_consent`, 키 `state`를 점으로 이은 이름이다.
        XCTAssertEqual("ad_consent.state", UserDefaultsAdConsentStore.key)
    }

    func testNeverSavedReadsAsUnknown() {
        XCTAssertEqual(.unknown, UserDefaultsAdConsentStore(defaults: defaults).read())
    }

    func testWritesTheBridgeStringVerbatim() {
        let store = UserDefaultsAdConsentStore(defaults: defaults)
        store.write(.denied)
        XCTAssertEqual("denied", defaults.string(forKey: UserDefaultsAdConsentStore.key))
        XCTAssertEqual(.denied, store.read())
        store.write(.granted)
        XCTAssertEqual("granted", defaults.string(forKey: UserDefaultsAdConsentStore.key))
        XCTAssertEqual(.granted, store.read())
    }

    /// 깨진 값은 저장이 없었던 것으로 본다 — 시트를 한 번 더 묻는 편이 모르는 값을 "허용"으로
    /// 읽는 것보다 안전하다.
    func testABrokenValueReadsAsUnknown() {
        let store = UserDefaultsAdConsentStore(defaults: defaults)
        for broken: Any in ["yes", "GRANTED", "", 1, true] {
            defaults.set(broken, forKey: UserDefaultsAdConsentStore.key)
            XCTAssertEqual(.unknown, store.read(), "\(broken)")
        }
    }
}
