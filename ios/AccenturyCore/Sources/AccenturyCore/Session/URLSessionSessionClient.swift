import Foundation

// 서버 계약(API 명세서 §3.1 / KAN-9)에 묶인 값들. 계약이 바뀌면 여기만 고친다.
private let pathSessions = "v0/sessions"
/// 안드로이드가 `"ANDROID"`를 보내는 자리. 백엔드 `CreateSessionRequest.Platform`은
/// `{IOS, ANDROID, WEB}`이고 명세서도 iOS를 `client.platform: IOS`로 적어 두었다 —
/// 이 한 값만 정본과 다르게 옮기는 것이 맞다. 익명 집계가 플랫폼별 응시·완주를 가르는
/// 유일한 입력이라, 여기서 ANDROID를 그대로 베끼면 iOS 응시가 안드로이드로 집계된다.
private let platformIOS = "IOS"
private let jsonMediaType = "application/json"
private let headerAuthorization = "Authorization"
private let headerCorrelationId = "X-Correlation-Id"
private let headerRetryAfter = "Retry-After"
private let headerContentType = "Content-Type"
private let bearerPrefix = "Bearer "

private let statusRequestTimeout = 408
private let statusTooManyRequests = 429

/// 세션 생성 한 건의 절대 상한.
///
/// 업로드(60초)보다 훨씬 짧게 잡는다. 올릴 본문이 없어 저속망에서도 오래 걸릴 이유가 없고,
/// 그동안 사용자는 [시작하기]를 누른 채 아무것도 없는 준비 화면을 보고 있다 — 여기서 기다리는
/// 시간은 "진입 → 결과 3분" 예산에서 통째로 빠지는 시간이다. 넘기면 `URLError.timedOut`으로 끊어
/// 실패 화면의 [다시 시도]가 받게 한다.
public let sessionCreateCallTimeoutSeconds: TimeInterval = 15

/// 안드로이드 `OkHttpSessionClient.defaultClient()` 자리.
public func defaultSessionCreateSession() -> URLSession {
    let configuration = URLSessionConfiguration.default
    configuration.timeoutIntervalForRequest = sessionCreateCallTimeoutSeconds
    configuration.timeoutIntervalForResource = sessionCreateCallTimeoutSeconds
    return URLSession(configuration: configuration)
}

/// `POST /v0/sessions` 클라이언트 (KAN-34 결선, KAN-9 계약).
/// 안드로이드 `session/SessionClient.kt`의 `OkHttpSessionClient` 1:1 이식본이다.
public final class URLSessionSessionClient: SessionClient, Sendable {

    private let baseURL: URL
    private let session: URLSession

    public init(baseURL: String, session: URLSession = defaultSessionCreateSession()) {
        guard let url = URL(string: baseURL) else {
            preconditionFailure("세션 baseUrl을 URL로 읽지 못했다: \(baseURL)")
        }
        self.baseURL = url
        self.session = session
    }

    public func create(
        appVersion: String,
        previousToken: String?,
        campaignToken: String?
    ) async -> SessionResult {
        do {
            let request = try buildRequest(appVersion, previousToken, campaignToken)
            let (data, response) = try await session.data(for: request)
            let http = response as? HTTPURLResponse
            return Self.toResult(
                status: http?.statusCode ?? 0,
                body: data,
                retryAfterHeader: http?.value(forHTTPHeaderField: headerRetryAfter)
            )
        } catch {
            return .transportError(reason: (error as? URLError)?.localizedDescription ?? "\(error)")
        }
    }

    private func buildRequest(
        _ appVersion: String,
        _ previousToken: String?,
        _ campaignToken: String?
    ) throws -> URLRequest {
        var request = URLRequest(url: baseURL.appendingPathComponent(pathSessions))
        request.httpMethod = "POST"
        /*
         * 바디 전체가 선택이지만(§3.1) 클라이언트는 채워 보낸다 — 익명 집계가 플랫폼별 응시·완주를
         * 가르는 유일한 입력이다.
         *
         * campaignToken은 Universal Link가 준 링크 진입에만 실린다 (KAN-32). 앱이 세션을 직접
         * 만드는 구조라(KAN-34) 진입 URL의 `?c=`만으로는 서버 세션에 유입 경로가 남지 않는다 —
         * 웹이 세션을 만들 때 하던 일을 이 자리가 대신한다. 링크 진입이 아니면 키 자체를 빼고
         * 보낸다: `Encodable` 합성이 옵셔널을 `encodeIfPresent`로 내보내 nil 필드는 직렬화되지
         * 않고(웹 webSession.ts도 같은 방식이다), 서버 `@Pattern`은 없는 필드는 보지만 `null`로
         * 온 값에는 걸릴 수 있다.
         */
        request.httpBody = try JSONEncoder().encode(
            CreateSessionBody(
                campaignToken: campaignToken,
                client: ClientBody(platform: platformIOS, appVersion: appVersion)
            )
        )
        request.setValue(jsonMediaType, forHTTPHeaderField: headerContentType)
        request.setValue(UUID().uuidString, forHTTPHeaderField: headerCorrelationId)
        /*
         * 재응시라면 이전 토큰을 실어 이전 세션과 결과를 즉시 폐기시킨다 (KAN-107).
         *
         * 만료됐거나 서버가 모르는 토큰은 조용히 무시되고 응답이 최초 응시와 구분되지 않는다 —
         * 401도 404도 오지 않는다. 그래서 여기서 토큰의 생사를 미리 따지지 않는다: 따져 봐야
         * 알 수 없고, 알아도 할 일이 같다(새 세션을 받는다).
         */
        if let previousToken {
            request.setValue(bearerPrefix + previousToken, forHTTPHeaderField: headerAuthorization)
        }
        return request
    }

    static func toResult(status: Int, body: Data, retryAfterHeader: String?) -> SessionResult {
        if (200...299).contains(status) {
            // 계약상 201이지만 다른 2xx도 5필드가 온전하면 받아들인다.
            let created = (try? JSONDecoder().decode(CreatedBody.self, from: body))
                .flatMap { body -> CreatedBody? in
                    let usable = body.sessionId.nonBlank != nil
                        && body.sessionToken.nonBlank != nil
                        && body.testVersion.nonBlank != nil
                    return usable ? body : nil
                }
            if let created {
                return .created(
                    Session(
                        sessionId: created.sessionId,
                        sessionToken: created.sessionToken,
                        testVersion: created.testVersion,
                        scoreVersion: created.scoreVersion,
                        expiresAt: created.expiresAt
                    )
                )
            }
            // 서버에는 세션이 생겼는데 우리는 쓸 수 없다. 다시 부르면 새 세션이 생기므로
            // 재시도 가능이다 — 버려진 세션은 아무 데이터도 달리지 않은 채 30분 뒤 만료된다.
            return .rejected(
                code: nil,
                message: "성공 응답(\(status)) 본문에서 세션 값을 읽지 못함",
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
            // 서버는 429에 봉투의 retryAfterMs와 Retry-After 헤더(초)를 함께 보낸다
            // (GlobalExceptionHandler). 봉투를 못 읽는 응답에서도 대기 시간 안내를 살리려고
            // 헤더를 예비로 읽는다 — 헤더가 HTTP-date 꼴이면 숫자로 읽히지 않아 nil이 된다.
            retryAfterMs: envelope?.retryAfterMs ?? retryAfterHeader.flatMap(Int64.init).map { $0 * 1_000 }
        )
    }

    static func isRetryableStatus(_ status: Int) -> Bool {
        status >= 500 || status == statusRequestTimeout || status == statusTooManyRequests
    }
}

/// 요청 바디 (§3.1). 모든 필드가 선택이라 서버는 바디 자체가 없어도 세션을 만든다.
///
/// `campaignToken`이 nil이면 **키 자체가 빠진다** — 합성된 `encode(to:)`가 옵셔널을
/// `encodeIfPresent`로 내보내기 때문이고, 안드로이드의 kotlinx `encodeDefaults=false`와 같은
/// 결과다. `"campaignToken":null`로 나가면 서버 `@Pattern`에 걸릴 수 있어 그 차이가 중요하다.
struct CreateSessionBody: Encodable {
    let campaignToken: String?
    let client: ClientBody
}

struct ClientBody: Encodable {
    let platform: String
    let appVersion: String
}

/// 201 응답 5필드 (§3.1). 하나라도 빠지면 디코딩이 실패해 재시도 가능한 거절이 된다.
struct CreatedBody: Decodable {
    let sessionId: String
    let sessionToken: String
    let testVersion: String
    let scoreVersion: String
    let expiresAt: String
}
