import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/web/AppLinkTest.kt`의 1:1 이식본.
/// 케이스 순서·주장 내용이 안드로이드와 같아야 두 플랫폼이 같은 공유 링크를 같게 읽는다는 걸
/// 보증할 수 있다 (KAN-32).
final class AppLinkTests: XCTestCase {

    private let allowed: Set<String> = ["https://accentury.app"]

    // MARK: - 진입으로 인정하는 링크 (KAN-32)

    /// 공유 링크는 계측 코드를 실은 진입이 된다.
    func testShareLinkBecomesAnEntryCarryingTheCampaignToken() {
        XCTAssertEqual(
            AppLinkEntry(campaignToken: "kko_share"),
            parseAppLink("https://accentury.app/t?c=kko_share", allowedOrigins: allowed)
        )
    }

    /// 끝에 슬래시가 붙은 경로도 같은 진입이다.
    func testTrailingSlashPathIsTheSameEntry() {
        XCTAssertEqual(
            AppLinkEntry(campaignToken: "kko_share"),
            parseAppLink("https://accentury.app/t/?c=kko_share", allowedOrigins: allowed)
        )
    }

    /// 계측 코드의 퍼센트 인코딩은 풀어서 읽는다.
    func testPercentEncodedCampaignTokenIsDecoded() {
        XCTAssertEqual(
            AppLinkEntry(campaignToken: "kko_share"),
            parseAppLink("https://accentury.app/t?c=kko%5Fshare", allowedOrigins: allowed)
        )
    }

    // MARK: - origin 검사 (§7의 보안 경계를 App Link 입구에도)

    /// allowlist 밖의 호스트는 진입이 아니다.
    func testHostOutsideTheAllowlistIsNotAnEntry() {
        XCTAssertNil(parseAppLink("https://evil.example.com/t?c=kko_share", allowedOrigins: allowed))
    }

    /// 스킴이 다르면 같은 호스트여도 진입이 아니다.
    func testSchemeMismatchIsNotAnEntryEvenOnTheSameHost() {
        XCTAssertNil(parseAppLink("http://accentury.app/t?c=kko_share", allowedOrigins: allowed))
    }

    /// origin이 없는 URL과 nil은 진입이 아니다.
    func testOriginlessUrlsAndNilAreNotEntries() {
        XCTAssertNil(parseAppLink("javascript:alert(1)", allowedOrigins: allowed))
        XCTAssertNil(parseAppLink("not a url", allowedOrigins: allowed))
        XCTAssertNil(parseAppLink("", allowedOrigins: allowed))
        XCTAssertNil(parseAppLink(nil, allowedOrigins: allowed))
    }

    // MARK: - 경로 검사: `/t`만 진입점이다

    /// 루트와 다른 경로는 진입이 아니다.
    func testRootAndOtherPathsAreNotEntries() {
        XCTAssertNil(parseAppLink("https://accentury.app/?c=kko_share", allowedOrigins: allowed))
        XCTAssertNil(parseAppLink("https://accentury.app/privacy?c=kko_share", allowedOrigins: allowed))
    }

    /// t 아래의 하위 경로는 진입이 아니다.
    func testSubPathsUnderTAreNotEntries() {
        XCTAssertNil(parseAppLink("https://accentury.app/t/x?c=kko_share", allowedOrigins: allowed))
    }

    /// 경로의 대소문자는 다른 경로다.
    func testPathCaseMakesItADifferentPath() {
        XCTAssertNil(parseAppLink("https://accentury.app/T?c=kko_share", allowedOrigins: allowed))
    }

    // MARK: - 계측 코드가 없거나 계약에 어긋날 때: 진입은 살리고 코드만 버린다

    /// 계측 코드가 없어도 진입은 성립한다.
    func testEntryStandsWithoutACampaignToken() {
        XCTAssertEqual(
            AppLinkEntry(campaignToken: nil),
            parseAppLink("https://accentury.app/t", allowedOrigins: allowed)
        )
        XCTAssertEqual(
            AppLinkEntry(campaignToken: nil),
            parseAppLink("https://accentury.app/t?x=1", allowedOrigins: allowed)
        )
    }

    /// 서버 계약에 어긋나는 코드는 버리고 진입만 남긴다.
    func testTokensViolatingTheServerContractAreDroppedButTheEntryRemains() {
        let dropped = AppLinkEntry(campaignToken: nil)
        // 공백 — 인코딩돼 도착해도 검사식은 통과하지 못한다.
        XCTAssertEqual(dropped, parseAppLink("https://accentury.app/t?c=kko%20share", allowedOrigins: allowed))
        // 65자 (상한 64자 초과)
        XCTAssertEqual(
            dropped,
            parseAppLink("https://accentury.app/t?c=\(String(repeating: "a", count: 65))", allowedOrigins: allowed)
        )
        // 한글 (`%ED%95%9C%EA%B8%80`)
        XCTAssertEqual(
            dropped,
            parseAppLink("https://accentury.app/t?c=%ED%95%9C%EA%B8%80", allowedOrigins: allowed)
        )
        // 빈 값
        XCTAssertEqual(dropped, parseAppLink("https://accentury.app/t?c=", allowedOrigins: allowed))
    }

    /// plus는 공백으로 풀지 않으므로 계측 코드가 되지 못한다.
    func testPlusIsNotDecodedAsSpaceSoItCannotBeACampaignToken() {
        // 안드로이드의 수동 퍼센트 디코딩과 같은 해석이다 — 두 플랫폼이 같은 링크를 같게 읽는지를
        // 이 한 줄이 붙들고 있다. form-urlencoded 규칙(`+` → 공백)으로 풀면 여기서 갈린다.
        XCTAssertEqual(
            AppLinkEntry(campaignToken: nil),
            parseAppLink("https://accentury.app/t?c=a+b", allowedOrigins: allowed)
        )
    }

    /// 상한인 64자는 통과한다.
    func testTheSixtyFourCharacterUpperBoundPasses() {
        let token = String(repeating: "a", count: 64)
        XCTAssertEqual(
            AppLinkEntry(campaignToken: token),
            parseAppLink("https://accentury.app/t?c=\(token)", allowedOrigins: allowed)
        )
    }

    /// 끝에 개행이 붙은 코드는 통과하지 못한다 — 안드로이드 `Regex.matches()`와 같은 판정.
    func testATrailingNewlineDoesNotSlipThroughTheAnchoredPattern() {
        XCTAssertEqual(
            AppLinkEntry(campaignToken: nil),
            parseAppLink("https://accentury.app/t?c=kko_share%0A", allowedOrigins: allowed)
        )
    }

    // MARK: - AC: 링크가 개인 결과 또는 세션 토큰을 포함하지 않는다

    /// 지어낸 세션과 화면 파라미터는 링크에 실려도 읽지 않는다.
    func testCraftedSessionAndScreenParametersAreNeverRead() {
        // AppLinkEntry의 필드는 계측 코드 하나뿐이라, 이 동등 비교가 곧 "나머지는 어디에도 안 실렸다"는 주장이다.
        XCTAssertEqual(
            AppLinkEntry(campaignToken: "kko_share"),
            parseAppLink(
                "https://accentury.app/t?c=kko_share&sessionId=abc&screen=result"
                    + "&testVersion=gn-2026.08.1&bridge=99&app=9.9",
                allowedOrigins: allowed
            )
        )
    }

    /// 계측 코드가 여러 번 오면 첫 값만 읽는다.
    func testOnlyTheFirstCampaignTokenIsRead() {
        XCTAssertEqual(
            AppLinkEntry(campaignToken: "first"),
            parseAppLink("https://accentury.app/t?c=first&c=second", allowedOrigins: allowed)
        )
    }

    /// 인코딩된 구분자가 파라미터 경계를 만들지 못한다.
    func testEncodedSeparatorsDoNotCreateParameterBoundaries() {
        // `%26`을 먼저 통째로 디코딩하면 `c=a`와 `b`로 갈려 `a`가 코드로 통과한다 — 그걸 막는 케이스다.
        XCTAssertEqual(
            AppLinkEntry(campaignToken: nil),
            parseAppLink("https://accentury.app/t?c=a%26b", allowedOrigins: allowed)
        )
    }

    // MARK: - 진입으로 인정하는 origin 목록 (KAN-32 3단계)

    /// 디버그 웹 origin이 App Link 진입 목록에 더해진다.
    func testDebugWebOriginIsAddedToTheAppLinkOrigins() {
        // 시뮬레이터에서 `-AppLinkURL`로 링크 진입을 그대로 밟아 보려면 HTTPS가 아닌 이 origin이
        // 목록에 있어야 한다 — AASA가 서기 전에는 그 인자 말고 링크 진입 경로가 없다.
        let origins = appLinkOrigins(webUrl: "http://localhost:5173")

        XCTAssertTrue(origins.isSuperset(of: appLinkOrigins))
        XCTAssertTrue(origins.contains("http://localhost:5173"))
    }

    /// 릴리스 웹 origin은 이미 목록 안이라 아무것도 늘리지 않는다.
    func testReleaseWebOriginAddsNothingBecauseItIsAlreadyListed() {
        XCTAssertEqual(appLinkOrigins, appLinkOrigins(webUrl: "https://accentury.app"))
    }

    // MARK: - entitlements와 코드가 같은 것을 가리키는지 (KAN-32 3단계)

    /// Universal Link는 entitlements(OS가 링크를 앱에 넘길지 정한다)와
    /// ``parseAppLink(_:allowedOrigins:)``(앱이 그 링크를 진입으로 인정할지 정한다)가 **둘 다**
    /// 맞아야 성립한다. 한쪽만 고쳐도 컴파일은 통과하고 단위 테스트도 조용한데, 링크만 조용히
    /// 죽는다 — 그 어긋남을 잡으라고 entitlements를 직접 읽는 테스트다.
    ///
    /// 안드로이드 `AppLinkTest`가 AndroidManifest를 파싱해 하는 일과 같다. 다만 대조 대상이
    /// 호스트뿐이다 — iOS의 associated domains에는 scheme·path·category가 없고, 경로 판정은
    /// 전부 ``parseAppLink(_:allowedOrigins:)`` 몫이다.
    func testEntitlementsAssociatedDomainsMatchTheAppLinkOrigins() throws {
        let path = Self.entitlementsPath()
        guard let data = FileManager.default.contents(atPath: path) else {
            // 건너뛰지 않는다 — 이 파일을 못 읽으면 검사 자체가 없던 일이 되고, 그게 정확히
            // 이 테스트가 막으려는 상태다.
            return XCTFail("Accentury.entitlements를 읽지 못했다: \(path)")
        }
        let plist = try PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any]
        let domains = try XCTUnwrap(
            plist?["com.apple.developer.associated-domains"] as? [String],
            "com.apple.developer.associated-domains가 없다: \(path)"
        )

        XCTAssertEqual(
            Set(appLinkOrigins.map { $0.replacingOccurrences(of: "https://", with: "") }),
            Set(domains.map { $0.replacingOccurrences(of: "applinks:", with: "") })
        )
    }

    /// 이 테스트 파일에서 `ios/` 디렉터리까지 거슬러 올라가 entitlements를 찾는다.
    /// 번들 리소스가 아니라 **레포의 그 파일**을 읽어야 한다 — 빌드에 실린 사본이 아니라
    /// 사람이 고치는 원본과 코드를 대조하는 것이 이 검사의 요점이다.
    private static func entitlementsPath() -> String {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while directory.path != "/" {
            if directory.lastPathComponent == "ios" {
                return directory.appendingPathComponent("Accentury/Accentury.entitlements").path
            }
            directory = directory.deletingLastPathComponent()
        }
        return "(#filePath=\(#filePath)에서 ios/ 디렉터리를 찾지 못했다)"
    }
}
