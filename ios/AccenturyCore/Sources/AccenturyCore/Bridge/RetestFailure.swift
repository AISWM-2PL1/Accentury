import Foundation

/// 재응시 실패를 결과 화면(웹)에 회신하는 계약 (KAN-34 2단계, KAN-107).
///
/// 공통 오류 봉투(§2.3)의 부분집합이다. 봉투를 통째로 넘기지 않는 이유: `correlationId`처럼 웹이
/// 그릴 수 없는 값은 화면에 쓰이지도 않으면서 계약 표면만 넓힌다.
///
/// **``code``는 서버가 준 값 그대로이고, 앱이 지어내지 않는다.** 봉투를 못 읽은 응답에서는 nil이다 —
/// 네이티브의 4갈래 판정(망/서버/요청 제한/재시도 불가)을 코드처럼 실어 보내면 웹이 그것을 서버
/// 코드와 구분할 수 없게 된다. 웹이 화면을 가르는 데 필요한 것은 ``retryable``과 ``retryAfterMs``
/// 둘이고, 그 판정은 이미 여기서 끝나 있다. 갈래별 안내 문구는 ``message``가 들고 간다.
///
/// - `message`: 사용자에게 그대로 보여줄 수 있는 문구. 웹이 갈래별 문구를 따로 들면 같은
///   판정에 두 벌의 카피가 생겨 앱과 웹이 다른 말을 하게 된다.
/// - `retryable`: 다시 눌러 볼 값어치가 있는가. 서버가 재시도 불가로 못박은 거절만 false다.
/// - `retryAfterMs`: 429가 알려준 대기 시간 (§2.5). 그 외에는 nil.
public struct RetestFailure: Equatable, Sendable, Encodable {
    public let code: String?
    public let message: String
    public let retryable: Bool
    public let retryAfterMs: Int64?

    public init(code: String?, message: String, retryable: Bool, retryAfterMs: Int64?) {
        self.code = code
        self.message = message
        self.retryable = retryable
        self.retryAfterMs = retryAfterMs
    }

    private enum CodingKeys: String, CodingKey {
        case code, message, retryable, retryAfterMs
    }

    /// 없는 값은 **필드를 빼는 것이 아니라 null로** 싣는다 — 웹 파서가 네 필드를 계약으로 읽는다.
    /// (스위프트의 자동 생성 인코딩은 옵셔널이 nil이면 키를 생략하므로 손으로 적는다.)
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(code, forKey: .code)
        try container.encode(message, forKey: .message)
        try container.encode(retryable, forKey: .retryable)
        try container.encode(retryAfterMs, forKey: .retryAfterMs)
    }

    /// 브리지가 JS로 넘길 payload.
    public func toJson() -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        guard let data = try? encoder.encode(self), let json = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return json
    }
}

/// 재응시 실패 갈래를 웹에 회신할 payload로 접는다.
///
/// 문구가 세션 게이트의 실패 화면과 다른 이유: 사용자가 보고 있는 화면이 다르다. 게이트는 "테스트를
/// 시작하지 못했다"이지만 여기서는 이미 한 번 응시를 끝내고 결과를 보는 중이라, 무엇이 안 됐는지가
/// "다시 시작"이어야 말이 통한다.
public func retestFailurePayload(_ failed: RetestOutcome.Failure) -> RetestFailure {
    RetestFailure(
        code: failed.code,
        message: {
            switch failed.reason {
            case .rateLimited: return "접속이 몰리고 있어요 · 잠시 뒤에 다시 시도해 주세요"
            case .network: return "연결이 불안정해요 · 네트워크를 확인하고 다시 시도해 주세요"
            case .server: return "다시 시작하지 못했어요 · 잠시 뒤에 다시 시도해 주세요"
            case .unsupported: return "지금은 다시 시작할 수 없어요 · 앱을 최신 버전으로 업데이트해 주세요"
            }
        }(),
        // 갈래가 곧 답이다 — 서버가 재시도 불가로 못박은 거절만 다시 눌러도 소용없다.
        retryable: failed.reason != .unsupported,
        retryAfterMs: failed.retryAfterMs
    )
}

/// 웹 계약의 코드 문자열 (`bridge.ts`의 `RetestFailure` 주석과 같은 값). 안드로이드
/// `bridge/RetestFailure.kt`의 `CODE_AD_DISMISSED`와 같은 값이다 (KAN-196).
public let codeAdDismissed = "AD_DISMISSED"

/// 보상형 광고를 끝까지 보지 않고 닫았을 때의 재응시 실패 (KAN-196, webview-bridge.md §8.2).
///
/// 서버 실패와 같은 봉투(``RetestFailure``)로 나간다 — 웹은 `onRetestFailed` 하나로 받고 `message`를
/// 그대로 그리며 코드로 문구를 고르지 않는다. 그래서 **문구 정본은 네이티브**이고, 두 플랫폼이
/// 같은 문장이어야 한다 (안드로이드 `adDismissedRetestFailure()`와 한 글자도 다르지 않다).
///
/// `retryable: true` — 다시 누르면 새 광고가 뜬다 (`RewardedRetestGate`가 닫힘 뒤 다음 요청을 받는다).
/// `retryAfterMs: nil` — 서버 제한이 아니라 사용자 행동이라 대기 시간이 없다.
public func adDismissedRetestFailure() -> RetestFailure {
    RetestFailure(
        code: codeAdDismissed,
        message: "광고를 끝까지 보시면 다시 테스트할 수 있어요",
        retryable: true,
        retryAfterMs: nil
    )
}
