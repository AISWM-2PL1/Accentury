import XCTest
@testable import Accentury

/// 앱 계층 테스트. AccenturyCore(순수 Swift, `swift test`로 CLI에서 도는 쪽)와 달리
/// 여기는 호스트 앱 번들이 있어야 의미가 있다 — 검증 대상이 "빌드 설정이 실제 번들까지
/// 흘러왔는가"이기 때문이다. xcconfig → Info.plist → infoDictionary 사슬이 끊기면 여기서 걸린다.
final class AppConfigTests: XCTestCase {

    func testWebUrlIsResolvedFromTheAppBundle() {
        let value = AppConfig.value(for: "WEB_URL", in: Bundle.main.infoDictionary)
        XCTAssertNotNil(value, "Info.plist의 WEB_URL이 비어 있다 - xcconfig 치환이 안 됐다")
        XCTAssertTrue(value!.hasPrefix("http"), "WEB_URL이 http(s)로 시작하지 않는다: \(value!)")
        XCTAssertFalse(value!.contains("$("), "빌드 변수가 치환되지 않고 그대로 남았다: \(value!)")
    }

    func testApiBaseUrlIsResolvedFromTheAppBundle() {
        let value = AppConfig.value(for: "API_BASE_URL", in: Bundle.main.infoDictionary)
        XCTAssertNotNil(value)
        XCTAssertTrue(value!.hasPrefix("http"))
        XCTAssertFalse(value!.contains("$("))
    }

    /// 마이크 문구가 빠지면 권한 요청 순간 앱이 죽는다. 문구 존재는 빌드가 아니라 테스트가 지킨다.
    func testMicrophoneUsageDescriptionIsPresent() {
        let value = Bundle.main.infoDictionary?["NSMicrophoneUsageDescription"] as? String
        XCTAssertNotNil(value)
        XCTAssertFalse(value!.isEmpty)
    }

    /// 끝의 `/`는 떼고 넘긴다 — 뒤에 쿼리를 잇는 쪽이 구분자를 직접 붙이기 때문이다.
    func testTrailingSlashesAreTrimmed() {
        XCTAssertEqual(
            "https://accentury.app",
            AppConfig.value(for: "K", in: ["K": "https://accentury.app//"])
        )
    }

    /// 값이 없거나 공백뿐이면 nil이다 — 이 nil이 AppConfig의 즉시 중단으로 이어진다.
    func testBlankOrMissingValuesAreNil() {
        XCTAssertNil(AppConfig.value(for: "K", in: ["K": "   "]))
        XCTAssertNil(AppConfig.value(for: "K", in: [:]))
        XCTAssertNil(AppConfig.value(for: "K", in: nil))
    }
}
