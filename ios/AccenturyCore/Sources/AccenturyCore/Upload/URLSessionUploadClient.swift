import Foundation

// 서버 계약(API 명세서 §3.3 / KAN-23)에 묶인 값들. 계약이 바뀌면 여기만 고친다.
private let pathSessions = "v0/sessions"
private let pathVoiceItems = "voice-items"
private let pathRecording = "recording"
private let partAudio = "audio"
private let partMeta = "meta"
private let audioFileName = "recording.wav"
private let audioMediaType = "audio/wav"
private let metaMediaType = "application/json"
private let headerAuthorization = "Authorization"
private let headerIdempotencyKey = "Idempotency-Key"
private let headerCorrelationId = "X-Correlation-Id"
private let headerContentType = "Content-Type"
private let bearerPrefix = "Bearer "

private let statusRequestTimeout = 408
private let statusTooManyRequests = 429

/// 업로드 한 건의 절대 상한 (KAN-146).
///
/// OkHttp 기본값이 connect·read·write 각 10초인 것처럼 `URLSession`도 기본
/// `timeoutIntervalForRequest`가 60초인데, 그건 **데이터가 멈춘** 구간에만 걸린다. 느리지만
/// 조금씩 진행하는 링크에서는 발화하지 않고, 호출 전체를 덮는 `timeoutIntervalForResource`의
/// 기본값은 7일이라 사실상 천장이 없다. 그동안 녹음 화면은 "제출 중…"으로 붙들려 있고
/// 그 화면에는 누를 수 있는 것이 없다 — 320KB WAV를 저속망에서 올리면 수 분을 그 상태로 보낸다.
///
/// 60초를 넘기면 `URLError.timedOut`으로 끊어 기존 실패 경로(TransportError → Failed)를 타게
/// 한다. 그러면 화면이 곧바로 웹으로 돌아가고 업로드 상태 바의 [재시도]가 복구를 받는다.
/// 값은 백엔드의 분석 실행 상한(accentury.analysis.processing-timeout=60s)과 같은 자리수로 맞췄다.
public let uploadCallTimeoutSeconds: TimeInterval = 60

/// 데이터가 아예 멈춘 구간의 상한. 안드로이드가 기대던 OkHttp 기본값(10초)과 같은 자리다.
public let uploadRequestTimeoutSeconds: TimeInterval = 10

/// 안드로이드 `OkHttpUploadClient.defaultClient()` 자리.
///
/// `URLSession`은 커넥션 풀·HTTP/2·리다이렉트를 OkHttpClient와 같은 방식으로 들고 있으므로
/// 앱에서 하나만 만들어 공유한다 (그래서 생성자 주입).
public func defaultUploadSession() -> URLSession {
    let configuration = URLSessionConfiguration.default
    configuration.timeoutIntervalForRequest = uploadRequestTimeoutSeconds
    configuration.timeoutIntervalForResource = uploadCallTimeoutSeconds
    return URLSession(configuration: configuration)
}

/// `POST /v0/sessions/{sid}/voice-items/{itemId}/recording` 클라이언트.
/// 안드로이드 `OkHttpUploadClient`의 1:1 이식본이다 (KAN-88, 계약은 명세서 §3.3).
///
/// 세션을 생성자로 받는 이유가 테스트다 — 테스트는 `MockURLProtocol`을 심은 ephemeral 세션을
/// 끼워 서버 없이 계약(경로·헤더·파트)을 통째로 검증한다. 안드로이드가 MockWebServer에
/// 진짜 클라이언트를 겨눈 것과 같은 자리이고, 다른 점은 소켓조차 열지 않는다는 것뿐이다.
public final class URLSessionUploadClient: UploadClient, Sendable {

    private let baseURL: URL
    private let session: URLSession
    /// 요청마다 새로 만드는 값이라 주입 가능하게 둔다 — 테스트가 경계(멀티파트 파싱)를
    /// 검증할 때 boundary 값에 의존하지 않게 하려고 기본은 UUID다.
    private let makeBoundary: @Sendable () -> String

    public init(
        baseURL: String,
        session: URLSession = defaultUploadSession(),
        makeBoundary: @escaping @Sendable () -> String = { "Boundary-\(UUID().uuidString)" }
    ) {
        guard let url = URL(string: baseURL) else {
            preconditionFailure("업로드 baseUrl을 URL로 읽지 못했다: \(baseURL)")
        }
        self.baseURL = url
        self.session = session
        self.makeBoundary = makeBoundary
    }

    public func upload(
        _ request: UploadRequest,
        sessionId: String,
        sessionToken: String
    ) async -> UploadResult {
        do {
            let (data, response) = try await session.data(for: buildRequest(request, sessionId, sessionToken))
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            return Self.toResult(status: status, body: data)
        } catch {
            // 취소도 여기로 온다(`URLError.cancelled`). 안드로이드는 CancellationException을
            // 호출부로 던지지만, 그 결과를 받는 ``UploadManager``가 폐기·교체된 시도의 결과를
            // 세대 토큰으로 이미 버리므로 상태에 닿지 않는다 — 갈래를 하나 더 만들지 않는다.
            return .transportError(
                failure: TransportFailure.from(error),
                reason: (error as? URLError)?.localizedDescription ?? "\(error)"
            )
        }
    }

    private func buildRequest(
        _ request: UploadRequest,
        _ sessionId: String,
        _ sessionToken: String
    ) throws -> URLRequest {
        let url = baseURL
            .appendingPathComponent(pathSessions)
            .appendingPathComponent(sessionId)
            .appendingPathComponent(pathVoiceItems)
            .appendingPathComponent(request.itemId)
            .appendingPathComponent(pathRecording)

        let boundary = makeBoundary()
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.httpBody = try Self.multipartBody(request, boundary: boundary)
        urlRequest.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: headerContentType)
        urlRequest.setValue(bearerPrefix + sessionToken, forHTTPHeaderField: headerAuthorization)
        // 비용이 발생하는 POST라 중복 접수를 막는다 (§2.2). 재시도해도 같은 attemptId를 쓴다.
        urlRequest.setValue(request.attemptId, forHTTPHeaderField: headerIdempotencyKey)
        urlRequest.setValue(UUID().uuidString, forHTTPHeaderField: headerCorrelationId)
        return urlRequest
    }

    /// 파트는 정확히 둘이다 — `audio`와 `meta`. 하나라도 빠지면 서버가 400을 준다(§3.3).
    ///
    /// KAN-88 티켓이 한때 지시했던 평면 파트(`itemId`·`idempotencyKey`·`recordedDurationMs`)는
    /// 정본에 없다. 경로와 헤더가 이미 같은 값을 나르므로 여기서 다시 보내지 않는다.
    static func multipartBody(_ request: UploadRequest, boundary: String) throws -> Data {
        let meta = try JSONEncoder().encode(
            MetaPart(durationMs: request.durationMs, clientQuality: request.clientQuality)
        )

        var body = Data()
        func append(_ text: String) { body.append(Data(text.utf8)) }

        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"\(partAudio)\"; filename=\"\(audioFileName)\"\r\n")
        append("Content-Type: \(audioMediaType)\r\n\r\n")
        body.append(request.wavBytes)
        append("\r\n--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"\(partMeta)\"\r\n")
        append("Content-Type: \(metaMediaType)\r\n\r\n")
        body.append(meta)
        append("\r\n--\(boundary)--\r\n")
        return body
    }

    /// 방어적 파싱. 세 규칙 모두 안드로이드와 같다 (`docs/wiki/upload-client.md`).
    static func toResult(status: Int, body: Data) -> UploadResult {
        if (200...299).contains(status) {
            // 계약상 202지만 다른 2xx도 analysisJobId만 있으면 받아들인다.
            let jobId = (try? JSONDecoder().decode(AcceptedBody.self, from: body))?.analysisJobId
                .nonBlank
            if let jobId {
                return .accepted(analysisJobId: jobId)
            }
            // 업로드는 접수됐지만 폴링할 ID가 없다. 멱등 키가 중복 접수를 막아주므로 재시도로 회수 가능.
            return .rejected(
                code: nil,
                message: "성공 응답(\(status)) 본문에서 analysisJobId를 읽지 못함",
                retryable: true,
                retryAfterMs: nil
            )
        }
        let envelope = try? JSONDecoder().decode(ErrorEnvelope.self, from: body)
        return .rejected(
            code: envelope?.code,
            message: envelope?.message ?? "오류 봉투 없는 응답(\(status))",
            // 봉투가 없으면 재시도 여부를 서버가 알려주지 않으므로 상태 코드로 판단한다.
            retryable: envelope?.retryable ?? isRetryableStatus(status),
            retryAfterMs: envelope?.retryAfterMs
        )
    }

    static func isRetryableStatus(_ status: Int) -> Bool {
        status >= 500 || status == statusRequestTimeout || status == statusTooManyRequests
    }
}

/// multipart의 meta 파트 본문(§3.3). audio와 함께 둘 다 필수다.
struct MetaPart: Codable {
    let durationMs: Int64
    let clientQuality: ClientQuality
}

struct AcceptedBody: Decodable {
    let analysisJobId: String
}

