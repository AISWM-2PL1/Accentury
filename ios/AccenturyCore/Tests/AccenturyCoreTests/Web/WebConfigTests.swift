import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/web/WebConfigTest.kt`의 1:1 이식본.
/// 케이스 순서·주장 내용이 안드로이드와 같아야 두 플랫폼이 같은 URL을 만든다는 걸 보증할 수 있다.
final class WebConfigTests: XCTestCase {

    // MARK: - buildWebUrl: 스큐 협상 파라미터 (§5)

    /// 로드 URL에 브리지 계약 버전과 앱 버전을 실어 보낸다.
    func testBuildWebUrlCarriesBridgeContractVersionAndAppVersion() {
        XCTAssertEqual(
            "https://web.example.com?bridge=\(bridgeContractVersion)&app=1.0",
            buildWebUrl(base: "https://web.example.com", appVersionName: "1.0")
        )
    }

    /// 기존 쿼리가 있으면 덮지 않고 뒤에 잇는다.
    func testAppendsToExistingQueryInsteadOfOverwriting() {
        XCTAssertEqual(
            "https://web.example.com?env=dev&bridge=\(bridgeContractVersion)&app=1.0",
            buildWebUrl(base: "https://web.example.com?env=dev", appVersionName: "1.0")
        )
    }

    /// 앱 버전 문자열은 URL 인코딩을 거친다.
    func testAppVersionStringIsUrlEncoded() {
        let url = buildWebUrl(base: "https://web.example.com", appVersionName: "1.0 beta")
        XCTAssertFalse(url.contains(" "))
        XCTAssertTrue(url.contains("app=1.0"))
    }

    // MARK: - buildWebUrl: 테스트 진입 URL 조립 (KAN-100)

    /// 테스트 진입 URL은 스큐 파라미터에 screen test testVersion sessionId를 잇는다.
    func testTestEntryUrlAppendsScreenTestVersionAndSessionId() {
        XCTAssertEqual(
            "https://web.example.com?bridge=\(bridgeContractVersion)&app=1.0"
                + "&screen=test&testVersion=gn-2026.08.1&sessionId=dev-session",
            buildWebUrl(
                base: "https://web.example.com",
                appVersionName: "1.0",
                testEntry: TestEntry(testVersion: "gn-2026.08.1", sessionId: "dev-session")
            )
        )
    }

    /// testEntry가 없으면 인트로 URL과 완전히 같다.
    func testNilTestEntryProducesTheSameUrlAsIntro() {
        XCTAssertEqual(
            buildWebUrl(base: "https://web.example.com", appVersionName: "1.0"),
            buildWebUrl(base: "https://web.example.com", appVersionName: "1.0", testEntry: nil)
        )
    }

    /// 세션 값에 든 구분자는 인코딩돼 쿼리 구조를 깨뜨리지 않는다.
    func testSeparatorsInsideSessionValuesAreEncoded() {
        let url = buildWebUrl(
            base: "https://web.example.com",
            appVersionName: "1.0",
            // 서버가 발급하는 값이라 형식을 앱이 보증하지 않는다 — 파라미터를 덧붙이는 꼴이 되면 안 된다.
            testEntry: TestEntry(testVersion: "gn 2026&x=1", sessionId: "s/1?2")
        )
        XCTAssertTrue(url.contains("&testVersion=gn+2026%26x%3D1&"))
        XCTAssertTrue(url.hasSuffix("&sessionId=s%2F1%3F2"))
    }

    /// 비ASCII 값은 Java `URLEncoder.encode`처럼 UTF-8 바이트 단위 대문자 %HH로 나간다 —
    /// 양 플랫폼이 같은 URL을 만들어야 웹의 스큐 판정·세션 식별이 갈리지 않는다 (Codex 지적으로 추가).
    func testNonAsciiValuesAreUtf8PercentEncodedLikeJava() {
        let url = buildWebUrl(base: "https://web.example.com", appVersionName: "1.0-한글 β😀")
        XCTAssertTrue(url.hasSuffix("&app=1.0-%ED%95%9C%EA%B8%80+%CE%B2%F0%9F%98%80"), url)
    }

    /// 기존 쿼리가 있는 base에도 테스트 진입 파라미터를 잇는다.
    func testAppendsTestEntryParametersToBaseWithExistingQuery() {
        XCTAssertEqual(
            "https://web.example.com?env=dev&bridge=\(bridgeContractVersion)&app=1.0"
                + "&screen=test&testVersion=v1&sessionId=s1",
            buildWebUrl(
                base: "https://web.example.com?env=dev",
                appVersionName: "1.0",
                testEntry: TestEntry(testVersion: "v1", sessionId: "s1")
            )
        )
    }

    // MARK: - webOrigin: allowlist 비교 입력 정규화 (§7)

    /// http https URL에서 origin을 뽑는다.
    func testExtractsOriginFromHttpAndHttpsUrls() {
        XCTAssertEqual("https://web.example.com", webOrigin("https://web.example.com/intro?x=1"))
        XCTAssertEqual("http://10.0.2.2:5173", webOrigin("http://10.0.2.2:5173/"))
    }

    /// 기본 포트는 표기 유무와 무관하게 같은 origin이다.
    func testDefaultPortsYieldTheSameOriginWhetherWrittenOrNot() {
        XCTAssertEqual(webOrigin("https://web.example.com"), webOrigin("https://web.example.com:443"))
        XCTAssertEqual(webOrigin("http://web.example.com"), webOrigin("http://web.example.com:80"))
    }

    /// http 계열이 아닌 스킴은 origin이 없다 - javascript file about.
    func testNonHttpSchemesHaveNoOrigin() {
        XCTAssertNil(webOrigin("javascript:alert(1)"))
        XCTAssertNil(webOrigin("file:///etc/passwd"))
        XCTAssertNil(webOrigin("about:blank"))
    }

    /// 파싱 불가능한 문자열은 origin이 없다.
    func testUnparsableStringsHaveNoOrigin() {
        XCTAssertNil(webOrigin("not a url"))
        XCTAssertNil(webOrigin(""))
    }

    // MARK: - isAllowedWebUrl: 보안 경계 (§7)

    /// allowlist 안의 origin만 허용한다.
    func testOnlyOriginsInsideTheAllowlistPass() {
        let allowed: Set<String> = ["https://web.example.com"]
        XCTAssertTrue(isAllowedWebUrl("https://web.example.com/intro", allowedOrigins: allowed))
        XCTAssertFalse(isAllowedWebUrl("https://evil.example.com/intro", allowedOrigins: allowed))
        // 스킴 다운그레이드 거부
        XCTAssertFalse(isAllowedWebUrl("http://web.example.com/intro", allowedOrigins: allowed))
        // 다른 포트 거부
        XCTAssertFalse(isAllowedWebUrl("https://web.example.com:8443/intro", allowedOrigins: allowed))
    }

    /// nil이나 origin이 없는 URL은 항상 거부한다.
    func testNilOrOriginlessUrlsAreAlwaysRejected() {
        let allowed: Set<String> = ["https://web.example.com"]
        XCTAssertFalse(isAllowedWebUrl(nil, allowedOrigins: allowed))
        XCTAssertFalse(isAllowedWebUrl("javascript:alert(1)", allowedOrigins: allowed))
    }

    /// 호스트 대소문자는 origin 비교에 영향을 주지 않는다.
    func testHostCaseDoesNotAffectOriginComparison() {
        XCTAssertTrue(
            isAllowedWebUrl("https://WEB.Example.com/intro", allowedOrigins: ["https://web.example.com"])
        )
    }
}
