import Foundation

/// 세션 생성 한 번의 결과. 판정(무엇을 보여줄지)은 ``SessionGateController``가 한다.
public enum SessionResult: Equatable, Sendable {

    case created(Session)

    /// 서버가 응답은 했지만 세션을 주지 않았다. 필드는 공통 오류 봉투(§2.4) 그대로다.
    ///
    /// - `retryAfterMs`: 429가 알려주는 대기 시간 (§2.5). 그 외에는 nil이다.
    case rejected(code: String?, message: String?, retryable: Bool, retryAfterMs: Int64?)

    /// 응답이 아예 오지 않은 전송 실패. 의미상 항상 재시도 가능.
    case transportError(reason: String)
}

/// `POST /v0/sessions` 클라이언트 (KAN-34 결선, KAN-9 계약).
///
/// `previousToken`이 이 프로토콜에 있는 이유: 재응시도 같은 호출이다 (KAN-107, §3.1). 이전 세션의
/// 토큰을 함께 보내면 서버가 그 세션과 결과를 즉시 폐기하고 새 세션을 발급한다. 최초 응시와
/// 재응시가 다른 메서드로 갈리면 헤더 하나 차이인 두 경로가 따로 늙으므로 파라미터로 둔다.
///
/// 실제 URLSession 구현은 §6 결선 몫이다 — 여기 있는 것은 게이트 상태 머신이 의존하는 경계뿐이라,
/// 네트워크 없이 가짜 클라이언트로 테스트가 돈다.
public protocol SessionClient: Sendable {
    /// - Parameters:
    ///   - appVersion: 익명 집계용 앱 버전 (서버 상한 32자)
    ///   - previousToken: 재응시일 때 폐기할 이전 세션의 토큰. 최초 응시는 nil
    ///   - campaignToken: Universal Link로 들어온 공유 유입 계측 코드 (KAN-32).
    ///     링크 진입이 아니면 nil
    func create(appVersion: String, previousToken: String?, campaignToken: String?) async -> SessionResult
}

public extension SessionClient {
    /// 안드로이드의 기본 인자(`previousToken: String? = null`, `campaignToken: String? = null`)
    /// 자리. 프로토콜 요구사항에는 기본값을 적을 수 없어 확장으로 둔다.
    func create(appVersion: String) async -> SessionResult {
        await create(appVersion: appVersion, previousToken: nil, campaignToken: nil)
    }

    /// 안드로이드의 기본 인자(`campaignToken: String? = null`) 자리.
    func create(appVersion: String, previousToken: String?) async -> SessionResult {
        await create(appVersion: appVersion, previousToken: previousToken, campaignToken: nil)
    }
}
