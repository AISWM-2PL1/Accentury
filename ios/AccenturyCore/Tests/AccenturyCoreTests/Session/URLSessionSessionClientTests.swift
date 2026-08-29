import XCTest
@testable import AccenturyCore

/// 안드로이드 `session/OkHttpSessionClientTest.kt`의 1:1 이식본 (12개).
final class URLSessionSessionClientTests: XCTestCase {

    /// 계약대로 5필드를 모두 담은 201 본문 (§3.1).
    private let createdBody = """
    {"sessionId":"s_abc","sessionToken":"st_xyz","testVersion":"gn-2026.08.1",
     "scoreVersion":"sv-1","expiresAt":"2026-08-24T10:30:00Z"}
    """

    override func setUp() {
        super.setUp()
        MockURLProtocol.reset()
    }

    override func tearDown() {
        MockURLProtocol.reset()
        super.tearDown()
    }

    private func client() -> URLSessionSessionClient {
        URLSessionSessionClient(baseURL: "https://api.test/", session: MockURLProtocol.makeSession())
    }

    func test201_응답의_5필드를_그대로_Session으로_담는다() async {
        MockURLProtocol.respond(status: 201, body: createdBody)

        let result = await client().create(appVersion: "1.0")

        XCTAssertEqual(
            .created(
                Session(
                    sessionId: "s_abc",
                    sessionToken: "st_xyz",
                    testVersion: "gn-2026.08.1",
                    scoreVersion: "sv-1",
                    expiresAt: "2026-08-24T10:30:00Z"
                )
            ),
            result
        )
    }

    /// 안드로이드는 `platform`이 `"ANDROID"`다. iOS는 백엔드 enum(`{IOS, ANDROID, WEB}`)과
    /// 명세서에 맞춰 `"IOS"`를 보낸다 — 정본과 일부러 다른 유일한 값이다.
    func testPOST_v0_sessions로_나가고_바디에_platform과_appVersion이_실린다() async throws {
        MockURLProtocol.respond(status: 201, body: createdBody)

        _ = await client().create(appVersion: "1.2.3")

        let recorded = try XCTUnwrap(MockURLProtocol.lastRequest())
        XCTAssertEqual("POST", recorded.method)
        XCTAssertEqual("/v0/sessions", recorded.url?.path)
        XCTAssertTrue((recorded.header("Content-Type") ?? "").hasPrefix("application/json"))
        XCTAssertFalse((recorded.header("X-Correlation-Id") ?? "").isEmpty)

        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: recorded.body) as? [String: Any])
        let client = try XCTUnwrap(body["client"] as? [String: Any])
        XCTAssertEqual("IOS", client["platform"] as? String)
        XCTAssertEqual("1.2.3", client["appVersion"] as? String)
        // 앱 최초 응시에는 유입 코드가 없다 — 없는 값을 빈 문자열로 만들어 보내지 않는다.
        XCTAssertNil(body["campaignToken"])
    }

    func test최초_응시에는_Authorization_헤더를_붙이지_않는다() async throws {
        MockURLProtocol.respond(status: 201, body: createdBody)

        _ = await client().create(appVersion: "1.0")

        XCTAssertNil(try XCTUnwrap(MockURLProtocol.lastRequest()).header("Authorization"))
    }

    func test이전_토큰을_주면_Bearer로_실어_보낸다_재응시_폐기_경로_KAN107() async throws {
        MockURLProtocol.respond(status: 201, body: createdBody)

        _ = await client().create(appVersion: "1.0", previousToken: "st_old")

        XCTAssertEqual("Bearer st_old", try XCTUnwrap(MockURLProtocol.lastRequest()).header("Authorization"))
    }

    func test429_봉투의_retryAfterMs를_결과에_싣는다() async {
        MockURLProtocol.respond(
            status: 429,
            body: #"{"code":"RATE_LIMITED","message":"요청이 너무 많습니다.","retryable":true,"retryAfterMs":2100,"correlationId":"corr-1"}"#,
            headers: ["Retry-After": "3"]
        )

        let result = await client().create(appVersion: "1.0")

        XCTAssertEqual(
            .rejected(code: "RATE_LIMITED", message: "요청이 너무 많습니다.", retryable: true, retryAfterMs: 2_100),
            result
        )
    }

    func test봉투를_못_읽는_429는_RetryAfter_헤더를_밀리초로_환산해_쓴다() async {
        MockURLProtocol.respond(status: 429, body: "<html>nope</html>", headers: ["Retry-After": "5"])

        let result = await client().create(appVersion: "1.0")

        guard case let .rejected(code, _, retryable, retryAfterMs) = result else {
            return XCTFail("Rejected가 아님: \(result)")
        }
        XCTAssertNil(code)
        XCTAssertTrue(retryable)
        XCTAssertEqual(5_000, retryAfterMs)
    }

    func testHTTPdate_꼴_RetryAfter는_숫자로_읽히지_않아_대기_시간_없이_남는다() async {
        MockURLProtocol.respond(
            status: 429,
            body: "nope",
            headers: ["Retry-After": "Wed, 21 Oct 2026 07:28:00 GMT"]
        )

        let result = await client().create(appVersion: "1.0")

        guard case let .rejected(_, _, _, retryAfterMs) = result else {
            return XCTFail("Rejected가 아님: \(result)")
        }
        XCTAssertNil(retryAfterMs)
    }

    func test400_VALIDATION_FAILED는_재시도_불가_거절로_온다() async {
        MockURLProtocol.respond(
            status: 400,
            body: #"{"code":"VALIDATION_FAILED","message":"영숫자와 ._- 조합 최대 64자만 허용됩니다","retryable":false,"correlationId":"corr-2"}"#
        )

        let result = await client().create(appVersion: "1.0")

        guard case let .rejected(code, _, retryable, _) = result else {
            return XCTFail("Rejected가 아님: \(result)")
        }
        XCTAssertEqual("VALIDATION_FAILED", code)
        XCTAssertFalse(retryable)
    }

    func test봉투_없는_500은_상태_코드_기준으로_재시도_가능한_거절이_된다() async throws {
        MockURLProtocol.respond(status: 500, body: "<html>Bad Gateway</html>")

        let result = await client().create(appVersion: "1.0")

        guard case let .rejected(_, message, retryable, _) = result else {
            return XCTFail("Rejected가 아님: \(result)")
        }
        XCTAssertTrue(retryable)
        XCTAssertTrue(try XCTUnwrap(message).contains("500"))
    }

    func test2xx인데_필드가_빠졌으면_재시도_가능한_거절로_방어한다() async {
        // sessionToken은 이 응답에서 한 번만 오는 값이라 없으면 세션 자체를 쓸 수 없다.
        MockURLProtocol.respond(
            status: 201,
            body: #"{"sessionId":"s_abc","testVersion":"gn-2026.08.1","scoreVersion":"sv-1","expiresAt":"z"}"#
        )

        let result = await client().create(appVersion: "1.0")

        guard case let .rejected(_, _, retryable, _) = result else {
            return XCTFail("Rejected가 아님: \(result)")
        }
        XCTAssertTrue(retryable)
    }

    func test2xx인데_sessionId가_빈_문자열이면_받아들이지_않는다() async {
        MockURLProtocol.respond(
            status: 201,
            body: #"{"sessionId":"","sessionToken":"st_xyz","testVersion":"v","scoreVersion":"s","expiresAt":"z"}"#
        )

        let result = await client().create(appVersion: "1.0")

        guard case .rejected = result else {
            return XCTFail("Rejected가 아님: \(result)")
        }
    }

    /// 세션 생성은 올릴 본문이 없어 오래 걸릴 이유가 없고, 그동안 사용자는 빈 준비 화면을 본다.
    /// 업로드(60초)와 달리 15초로 묶어 두는 값이 풀리지 않게 못 박는다.
    func test기본_세션은_요청과_전체_모두_15초_상한을_쓴다() {
        let configuration = defaultSessionCreateSession().configuration

        XCTAssertEqual(15, configuration.timeoutIntervalForRequest)
        XCTAssertEqual(15, configuration.timeoutIntervalForResource)
        XCTAssertEqual(sessionCreateCallTimeoutSeconds, configuration.timeoutIntervalForRequest)
        XCTAssertEqual(sessionCreateCallTimeoutSeconds, configuration.timeoutIntervalForResource)
    }

    func test서버_연결이_끊기면_TransportError를_반환한다() async {
        MockURLProtocol.fail(with: URLError(.cannotConnectToHost))

        let result = await client().create(appVersion: "1.0")

        guard case let .transportError(reason) = result else {
            return XCTFail("TransportError가 아님: \(result)")
        }
        XCTAssertFalse(reason.isEmpty)
    }
}
