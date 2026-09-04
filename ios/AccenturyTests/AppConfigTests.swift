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

    // MARK: 카카오 공유 배선 (KAN-180)

    /// 키는 **없어도 정상**이다 — 레포에 두지 않으므로 갓 클론한 기계의 기본 상태가 nil이다.
    /// 그래서 여기서 볼 수 있는 건 "있다면 쓸 수 있는 값인가" 하나뿐이다: 빌드 변수가 치환되지
    /// 않고 `$(...)`로 남는 사고는 값이 있는 기계에서도 조용히 지나가므로 이 자리에서 막는다.
    func testKakaoNativeAppKeyIsEitherAbsentOrUsable() {
        guard let key = AppConfig.kakaoNativeAppKey else { return }
        XCTAssertFalse(key.contains("$("), "빌드 변수가 치환되지 않고 그대로 남았다: \(key)")
        XCTAssertFalse(key.isEmpty)
    }

    /// 카톡이 공유를 마치고 돌아올 스킴은 카카오가 정한 `kakao{네이티브 앱 키}`여야 한다.
    /// 두 값이 각각 다른 자리(xcconfig, plist)에서 오므로 한쪽만 고쳐도 컴파일은 조용하다 —
    /// 그러면 공유는 나가는데 카톡에서 앱으로 되돌아오지 못한다.
    func testKakaoUrlSchemeIsDerivedFromTheNativeAppKey() {
        let types = Bundle.main.infoDictionary?["CFBundleURLTypes"] as? [[String: Any]]
        let schemes = types?.compactMap { $0["CFBundleURLSchemes"] as? [String] }.flatMap { $0 } ?? []
        XCTAssertEqual(
            ["kakao" + (AppConfig.kakaoNativeAppKey ?? "")],
            schemes,
            "URL scheme이 kakao+앱키 형식이 아니다 - Info-*.plist와 Base.xcconfig를 확인하라"
        )
    }

    /// 조회 스킴이 빠지면 `canOpenURL`이 카톡 설치 여부에 무조건 false를 돌려주고, 그 위에 선
    /// `isKakaoTalkSharingAvailable`도 따라 false가 된다 — 카카오 경로가 조용히 죽고 늘 시트로만
    /// 간다. 빌드도 실행도 멀쩡해서 실기기에서 눈으로 보기 전까지 드러나지 않는 종류의 사고다.
    func testKakaoQuerySchemesAreDeclared() {
        let schemes = Bundle.main.infoDictionary?["LSApplicationQueriesSchemes"] as? [String] ?? []
        XCTAssertTrue(schemes.contains("kakaolink"), "kakaolink가 없다: \(schemes)")
        XCTAssertTrue(schemes.contains("kakaokompassauth"), "kakaokompassauth가 없다: \(schemes)")
    }
}

