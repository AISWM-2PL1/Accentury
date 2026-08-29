import XCTest
@testable import AccenturyCore

/// 안드로이드 `upload/OkHttpUploadClientTest.kt`의 1:1 이식본 (9개).
/// `MockWebServer` 자리에 ``MockURLProtocol``이 들어간 것 말고는 검증 대상이 같다.
final class URLSessionUploadClientTests: XCTestCase {

    private let request = UploadRequest(
        attemptId: "attempt-abc",
        itemId: "item-42",
        wavBytes: Data((0..<64).map { UInt8($0 & 0xFF) }),
        durationMs: 3_210,
        clientQuality: ClientQuality(rms: 0.11, peak: 0.83, silenceRatio: 0.12, clipped: false)
    )

    override func setUp() {
        super.setUp()
        MockURLProtocol.reset()
    }

    override func tearDown() {
        MockURLProtocol.reset()
        super.tearDown()
    }

    private func client() -> URLSessionUploadClient {
        URLSessionUploadClient(baseURL: "https://api.test/", session: MockURLProtocol.makeSession())
    }

    private func accepted() {
        MockURLProtocol.respond(status: 202, body: #"{"analysisJobId":"aj_123"}"#)
    }

    /// multipart 본문에서 파트를 갈라 온다. boundary는 요청마다 달라서 헤더에서 읽는다.
    private func partsOfLastRequest() throws -> [MultipartPart] {
        let recorded = try XCTUnwrap(MockURLProtocol.lastRequest())
        let contentType = try XCTUnwrap(recorded.header("Content-Type"))
        let boundary = try XCTUnwrap(MultipartParser.boundary(fromContentType: contentType))
        return try MultipartParser.parse(body: recorded.body, boundary: boundary)
    }

    func test202_응답이면_analysisJobId를_파싱해_Accepted를_반환한다() async throws {
        accepted()

        let result = await client().upload(request, sessionId: "sess-1", sessionToken: "token-1")

        XCTAssertEqual(.accepted(analysisJobId: "aj_123"), result)
    }

    func testitemId는_경로에_실리고_IdempotencyKey_헤더는_attemptId와_같다() async throws {
        accepted()

        _ = await client().upload(request, sessionId: "sess-1", sessionToken: "token-1")

        let recorded = try XCTUnwrap(MockURLProtocol.lastRequest())
        XCTAssertEqual("POST", recorded.method)
        XCTAssertEqual("/v0/sessions/sess-1/voice-items/item-42/recording", recorded.url?.path)
        XCTAssertEqual("Bearer token-1", recorded.header("Authorization"))
        XCTAssertEqual("attempt-abc", recorded.header("Idempotency-Key"))
        XCTAssertFalse((recorded.header("X-Correlation-Id") ?? "").isEmpty)
        XCTAssertTrue((recorded.header("Content-Type") ?? "").hasPrefix("multipart/form-data"))
    }

    func test본문은_audio와_meta_두_파트뿐이고_평면_파트는_없다() async throws {
        accepted()

        _ = await client().upload(request, sessionId: "sess-1", sessionToken: "token-1")

        let parts = try partsOfLastRequest()
        XCTAssertEqual(2, parts.count)

        let audio = try XCTUnwrap(parts.first { $0.name == "audio" })
        XCTAssertEqual("recording.wav", audio.filename)
        XCTAssertEqual("audio/wav", audio.contentType)
        XCTAssertEqual(request.wavBytes, audio.body)

        let meta = try XCTUnwrap(parts.first { $0.name == "meta" })
        // meta 파트에는 파일명이 없다 — 안드로이드 `addFormDataPart(PART_META, null, ...)`과 같다.
        XCTAssertNil(meta.filename)
        XCTAssertEqual("application/json", meta.contentType)

        // KAN-88 티켓이 잘못 지시했던 평면 파트들. 정본에는 없어야 한다.
        let names = parts.compactMap(\.name)
        XCTAssertFalse(names.contains("itemId"))
        XCTAssertFalse(names.contains("idempotencyKey"))
        XCTAssertFalse(names.contains("recordedDurationMs"))
    }

    func testmeta_파트에_durationMs와_clientQuality_4필드가_실린다() async throws {
        accepted()

        _ = await client().upload(request, sessionId: "sess-1", sessionToken: "token-1")

        let meta = try XCTUnwrap(try partsOfLastRequest().first { $0.name == "meta" })
        let json = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: meta.body) as? [String: Any]
        )
        XCTAssertEqual(3_210, json["durationMs"] as? Int)

        let quality = try XCTUnwrap(json["clientQuality"] as? [String: Any])
        XCTAssertEqual(0.11, try XCTUnwrap(quality["rms"] as? Double), accuracy: 1e-9)
        XCTAssertEqual(0.83, try XCTUnwrap(quality["peak"] as? Double), accuracy: 1e-9)
        XCTAssertEqual(0.12, try XCTUnwrap(quality["silenceRatio"] as? Double), accuracy: 1e-9)
        XCTAssertEqual(false, quality["clipped"] as? Bool)
        XCTAssertEqual(4, quality.count)
    }

    func test오류_봉투를_그대로_Rejected_필드에_매핑한다() async {
        MockURLProtocol.respond(
            status: 413,
            body: #"{"code":"AUDIO_TOO_LARGE","message":"파일이 너무 큽니다","retryable":false,"retryAfterMs":null,"correlationId":"corr-1"}"#
        )

        let result = await client().upload(request, sessionId: "sess-1", sessionToken: "token-1")

        XCTAssertEqual(
            .rejected(code: "AUDIO_TOO_LARGE", message: "파일이 너무 큽니다", retryable: false, retryAfterMs: nil),
            result
        )
    }

    func test봉투_없는_500은_상태_코드_기준으로_재시도_가능한_Rejected가_된다() async throws {
        MockURLProtocol.respond(status: 500, body: "<html>Bad Gateway</html>")

        let result = await client().upload(request, sessionId: "sess-1", sessionToken: "token-1")

        guard case let .rejected(code, message, retryable, _) = result else {
            return XCTFail("Rejected가 아님: \(result)")
        }
        XCTAssertNil(code)
        XCTAssertTrue(retryable)
        XCTAssertTrue(try XCTUnwrap(message).contains("500"))
    }

    func test봉투_없는_400은_재시도_불가로_판단한다() async {
        MockURLProtocol.respond(status: 400, body: "not json")

        let result = await client().upload(request, sessionId: "sess-1", sessionToken: "token-1")

        guard case let .rejected(_, _, retryable, _) = result else {
            return XCTFail("Rejected가 아님: \(result)")
        }
        XCTAssertFalse(retryable)
    }

    func test202인데_본문이_깨졌으면_재시도_가능한_Rejected로_방어한다() async {
        MockURLProtocol.respond(status: 202, body: "{}")

        let result = await client().upload(request, sessionId: "sess-1", sessionToken: "token-1")

        guard case let .rejected(_, _, retryable, _) = result else {
            return XCTFail("Rejected가 아님: \(result)")
        }
        XCTAssertTrue(retryable)
    }

    /// 봉투와 상태 코드가 재시도 여부를 어떻게 가르는지 한 표로 못 박는다
    /// (`docs/wiki/upload-client.md` 방어적 파싱 ③).
    ///
    /// `retryable`은 봉투에서 **기본값 없는 필수 필드**라, 문법상 멀쩡한 JSON이어도 그 키가
    /// 빠지면 디코딩이 통째로 실패해 상태 코드 폴백을 탄다 — code·message까지 함께 폴백 값이
    /// 되는 것이 그 설계의 결과다.
    func test봉투와_상태_코드에_따른_재시도_판정표() async {
        struct Row {
            let label: String
            let status: Int
            let body: String
            let retryable: Bool
        }
        let rows = [
            Row(label: "봉투 없는 408", status: 408, body: "gateway timeout", retryable: true),
            Row(label: "봉투 없는 429", status: 429, body: "<html>rate limited</html>", retryable: true),
            Row(label: "봉투 없는 404", status: 404, body: "not found", retryable: false),
            Row(
                label: "retryable 빠진 봉투 + 500",
                status: 500,
                body: #"{"code":"INTERNAL_ERROR","message":"서버 오류","correlationId":"corr-9"}"#,
                retryable: true
            ),
            Row(
                label: "retryable 빠진 봉투 + 400",
                status: 400,
                body: #"{"code":"VALIDATION_FAILED","message":"잘못된 요청","correlationId":"corr-9"}"#,
                retryable: false
            ),
        ]

        for row in rows {
            MockURLProtocol.reset()
            MockURLProtocol.respond(status: row.status, body: row.body)

            let result = await client().upload(request, sessionId: "sess-1", sessionToken: "token-1")

            guard case let .rejected(code, message, retryable, _) = result else {
                XCTFail("\(row.label): Rejected가 아님: \(result)")
                continue
            }
            XCTAssertEqual(row.retryable, retryable, row.label)
            // 봉투를 못 읽었으므로 서버가 준 코드·문구는 남지 않는다.
            XCTAssertNil(code, row.label)
            XCTAssertEqual("오류 봉투 없는 응답(\(row.status))", message, row.label)
        }
    }

    /// WAV는 임의 바이트열이라 boundary와 같은 시퀀스가 우연히 들어갈 수 있다.
    /// 실제 클라이언트가 만든 본문에서도 파트가 둘로, 오디오 바이트가 한 바이트도 안 틀리게 읽혀야 한다.
    func test오디오_바이트에_구분자_시퀀스가_섞여도_요청_본문은_두_파트다() async throws {
        let boundary = "Boundary-FIXED-42"
        var wavBytes = Data([0xFF, 0x00])
        wavBytes.append(Data("--\(boundary)".utf8))       // CRLF 없이 맨몸으로
        wavBytes.append(Data([0x10]))
        wavBytes.append(Data("\r\n--\(boundary)".utf8))   // CRLF는 있는데 뒤가 CRLF가 아니다
        wavBytes.append(Data([0x00, 0x7F]))

        let adversarial = UploadRequest(
            attemptId: request.attemptId,
            itemId: request.itemId,
            wavBytes: wavBytes,
            durationMs: request.durationMs,
            clientQuality: request.clientQuality
        )
        let client = URLSessionUploadClient(
            baseURL: "https://api.test/",
            session: MockURLProtocol.makeSession(),
            makeBoundary: { boundary }
        )
        accepted()

        _ = await client.upload(adversarial, sessionId: "sess-1", sessionToken: "token-1")

        let parts = try partsOfLastRequest()
        XCTAssertEqual(2, parts.count)
        let audio = try XCTUnwrap(parts.first { $0.name == "audio" })
        XCTAssertEqual(wavBytes.count, audio.body.count)
        XCTAssertEqual(wavBytes, audio.body)
    }

    /// 업로드 한 건의 상한 (KAN-146). 기본 세션을 안 거치고 만들면 `timeoutIntervalForResource`가
    /// 7일이라 "제출 중…" 화면이 저속망에서 몇 분씩 붙들린다 — 값이 조용히 풀리지 않게 못 박는다.
    func test기본_세션은_요청_10초_전체_60초_상한을_쓴다() {
        let configuration = defaultUploadSession().configuration

        XCTAssertEqual(10, configuration.timeoutIntervalForRequest)
        XCTAssertEqual(60, configuration.timeoutIntervalForResource)
        XCTAssertEqual(uploadRequestTimeoutSeconds, configuration.timeoutIntervalForRequest)
        XCTAssertEqual(uploadCallTimeoutSeconds, configuration.timeoutIntervalForResource)
    }

    /*
     * 실제 오류가 무엇으로 올라오는지까지 확인한다 (KAN-147 2단계). 분류표만 단위 테스트하면
     * 네트워크 스택이 다른 코드를 주는 순간 사용자는 엉뚱한 안내를 받는데 아무도 모른다.
     */
    func test서버_연결이_끊기면_서버_쪽_문제로_분류한_TransportError를_반환한다() async {
        MockURLProtocol.fail(with: URLError(.cannotConnectToHost))

        let result = await client().upload(request, sessionId: "sess-1", sessionToken: "token-1")

        guard case let .transportError(failure, reason) = result else {
            return XCTFail("TransportError가 아님: \(result)")
        }
        XCTAssertEqual(.serverUnreachable, failure)
        XCTAssertFalse(reason.isEmpty)
    }
}
