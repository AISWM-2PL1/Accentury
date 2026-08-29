import Foundation

/// 봉투를 읽을 수 있을 때의 요청 제한 코드 (§2.5).
private let codeRateLimited = "RATE_LIMITED"

/// 서버가 준 밀리초를 사용자에게 읽어 줄 초로 올림한다 — 서버가 Retry-After를 만드는 규칙과 같다.
func ceilSeconds(_ millis: Int64) -> Int64 { (millis + 999) / 1_000 }

/// 시작 게이트의 세션 관문 상태 (KAN-34).
public enum SessionGateState: Equatable, Sendable {

    /// 세션 생성 요청이 나가 있다. 화면은 준비 중 표시를 세운다.
    case creating

    /// 세션을 받지 못했다. 사용자는 여기서 ``SessionGateController/restart()``로만 앞으로 갈 수 있다.
    ///
    /// - `retryAfterSeconds`: 429가 알려준 대기 시간(초). 그 외에는 nil
    case failed(reason: SessionFailureReason, retryAfterSeconds: Int64?)

    /// 세션을 확보했다. 이 값이 곧 테스트 진입이다.
    case ready(Session)
}

/// 재응시 한 건의 결말 (KAN-34 2단계, KAN-107).
///
/// 세션 생성 응답을 그대로 웹에 흘려보내지 않고 여기서 한 번 접는 이유는 ``SessionGateState``와
/// 같다 — 결과 화면이 물어보는 것은 "무슨 상태 코드였나"가 아니라 "새 응시로 넘어갔나, 아니면
/// 이 화면에 남아 무엇을 안내해야 하나"다.
public enum RetestOutcome: Equatable, Sendable {

    /// 새 세션으로 교체됐다. 서버는 이전 세션과 결과를 이미 폐기했다 (KAN-107).
    case replaced(Session)

    /// 세션을 받지 못했다.
    case failed(Failure)

    /// **이전 세션은 그대로 살아 있다** — 서버도 지우지 않았고(폐기는 새 세션 발급과 한 몸이다)
    /// 결과 화면이 아직 그 세션으로 결과를 조회한다.
    ///
    /// 중첩 구조체인 이유는 이 값이 ``retestFailurePayload(_:)``의 인자 타입이기 때문이다 —
    /// 안드로이드가 `RetestOutcome.Failed`를 그대로 받는 자리와 같다.
    ///
    /// - `code`: 서버 오류 봉투의 코드 (§2.4). 봉투를 못 읽었으면 nil
    /// - `retryAfterMs`: 429가 알려준 대기 시간 (§2.5). 그 외에는 nil
    public struct Failure: Equatable, Sendable {
        public let reason: SessionFailureReason
        public let code: String?
        public let retryAfterMs: Int64?

        public init(reason: SessionFailureReason, code: String?, retryAfterMs: Int64?) {
            self.reason = reason
            self.code = code
            self.retryAfterMs = retryAfterMs
        }
    }

    /// 안드로이드 `RetestOutcome.Failed(reason, code, retryAfterMs)` 호출 자리를 그대로 옮기는 편의 생성자.
    public static func failed(
        reason: SessionFailureReason,
        code: String?,
        retryAfterMs: Int64?
    ) -> RetestOutcome {
        .failed(Failure(reason: reason, code: code, retryAfterMs: retryAfterMs))
    }
}

/// 실패 화면의 문구와 버튼을 가르는 갈래.
///
/// 상태 코드를 그대로 들고 다니는 대신 갈래로 접는 이유: 화면이 물어보는 것은 "무슨 코드였나"가
/// 아니라 "사용자가 무엇을 할 수 있나"다. 기다리면 되는가(``rateLimited``), 망을 확인하면 되는가
/// (``network``), 다시 눌러 보면 되는가(``server``), 다시 눌러도 소용없는가(``unsupported``).
public enum SessionFailureReason: String, Sendable, CaseIterable {
    /// 429 — 같은 IP에서 세션 생성이 몰렸다 (§2.5). 기다리면 풀린다.
    case rateLimited

    /// 응답이 아예 오지 않았다 — 망 문제이거나 서버가 닿지 않는다.
    case network

    /// 서버가 거절했지만 재시도로 풀릴 수 있다 (5xx·408, 봉투가 retryable=true로 준 거절).
    case server

    /// 서버가 재시도 불가로 못박았다 (`VALIDATION_FAILED` 등) — 앱과 서버 계약이 어긋난 경우다.
    case unsupported
}

/// 세션 확보 상태 머신 (KAN-34). 마이크 권한 게이트(KAN-98)를 지난 뒤, 테스트 진입 URL을 열기 전에 선다.
///
/// 화면에서 분리한 이유는 ``WebLoadController``·`MicPermissionController`와 같다 — 오류 응답을
/// 어떤 복구 경로로 접는지가 시작 UX의 정확성을 좌우하는데, 네트워크·SwiftUI에 붙어 있으면
/// 시뮬레이터 없이 검증할 수 없다.
///
/// ``session``이 이 앱에서 "테스트에 들어갔는가"의 정본이다. 진입 URL·업로드·브리지 토큰이 전부
/// 세션에서 나오므로, 진입 여부와 세션을 따로 들면 둘이 어긋난 상태(진입했는데 세션이 없다)를
/// 표현할 수 있게 된다.
///
/// 호출은 전부 메인 스레드에서 온다(브리지 콜백이 메인으로 넘어오고 나머지는 SwiftUI다).
/// 그래서 상태에 동기화를 두지 않는다.
public final class SessionGateController {

    public private(set) var state: SessionGateState

    /// 생성 시도 횟수이자 요청 재실행 키 — 값이 바뀌면 화면이 세션 생성을 처음부터 다시 건다
    /// (``WebLoadController/attempt``와 같은 역할).
    public private(set) var attempt: Int = 0

    /// 확보된 세션. nil이면 아직 테스트에 들어갈 수 없다.
    public var session: Session? {
        if case .ready(let session) = state { return session }
        return nil
    }

    /// 다음 세션 생성 요청에 실을 폐기 대상 토큰 (KAN-107, KAN-34 2단계).
    ///
    /// 서버 쪽 세션 폐기는 **새 세션 생성 요청에 이전 토큰을 실어야만** 일어난다. 그래서 세션을
    /// 버리는 자리(``restart()``)는 지울 수 없고, 버린 토큰을 여기 적어 두었다가 다음 생성이 함께
    /// 실어 보낸다. 재응시(즉시 재생성)와 인트로 복귀 후 재시작(나중 재생성)이 이 한 경로를 같이 탄다.
    ///
    /// 화면 재생성·프로세스 복원에는 싣지 않는다 (``saved()`` 참조). 이 값이 하는 일은 **이미
    /// 버려진** 익명 세션의 수명을 30분 만료에서 즉시로 당기는 것뿐이라, 잃어버려도 손해가 만료
    /// 대기 하나다. 반면 저장하려면 저장 형식이 두 칸짜리 봉투가 돼야 한다 — 값어치에 비해 큰
    /// 대가다. 재응시 경로는 애초에 이 값을 거치지 않는다(토큰을 손에 든 채 곧바로 요청한다).
    public private(set) var pendingPreviousToken: String?

    /// 재응시 요청이 나가 있다 — 중복 호출을 무시하는 근거다
    /// (KAN-107: 더블탭 고아 세션은 클라이언트 책임).
    ///
    /// 이 플래그가 브리지가 아니라 여기 있는 이유: 진행 중이라는 사실의 주인은 요청을 건 상태 머신
    /// 하나여야 한다. 브리지에 따로 두면 두 플래그가 어긋나는 상태를 표현할 수 있게 되고, 어긋나면
    /// 막으려던 이중 요청이 정확히 그때 새어 나간다.
    public private(set) var retestInFlight: Bool = false

    private init(state: SessionGateState) {
        self.state = state
    }

    public convenience init() {
        self.init(state: .creating)
    }

    /// 세션 생성 응답 한 건을 반영한다.
    public func onResult(_ result: SessionResult) {
        if case .created = result {
            // 이 응답이 곧 폐기 완료 통지다 — 서버가 새 세션을 발급했다는 것은 실어 보낸 이전
            // 세션을 지웠다는 뜻이다 (§3.1). 실패면 그대로 둬서 다음 시도가 다시 싣게 한다.
            pendingPreviousToken = nil
        }
        state = stateOf(result)
    }

    /// 재응시를 시작한다 (KAN-34 2단계). 돌려주는 값이 곧 세션 생성에 실을 이전 토큰이고,
    /// **nil이면 지금 요청을 걸면 안 된다**는 뜻이다.
    ///
    /// 걸지 않는 두 경우가 같은 nil로 접히는 이유는 호출자가 할 일이 같기 때문이다 — 아무것도
    /// 하지 않는다. (1) 이미 요청이 나가 있다: 웹의 버튼 비활성이 늦거나 더블탭이 뚫린 경우로,
    /// 두 번째 요청이 나가면 첫 요청이 만든 세션이 곧바로 고아가 된다. (2) 버릴 세션이 없다:
    /// 재응시는 결과 화면에서만 오므로 정상 흐름에서는 오지 않는 조합이다.
    public func beginRetest() -> String? {
        if retestInFlight { return nil }
        guard let token = session?.sessionToken else { return nil }
        retestInFlight = true
        return token
    }

    /// 재응시 요청 한 건의 결과를 반영한다.
    ///
    /// 실패에서 ``state``를 건드리지 않는 것이 이 메서드의 요점이다. 지금 화면에 떠 있는 것은 세션
    /// 게이트가 아니라 **결과 화면(웹)**이고 그 화면은 여전히 이전 세션으로 결과를 조회한다 —
    /// 여기서 상태를 실패로 바꾸면 서버에 멀쩡히 살아 있는 세션을 앱만 버리게 된다.
    public func onRetestResult(_ result: SessionResult) -> RetestOutcome {
        retestInFlight = false
        if case .created(let session) = result {
            pendingPreviousToken = nil
            state = .ready(session)
            return .replaced(session)
        }
        guard case .failed(let reason, _) = stateOf(result) else {
            // stateOf는 Created가 아닌 응답을 늘 failed로 접는다 — 위 분기가 Created를 이미 걸렀다.
            preconditionFailure("생성 실패 응답이 실패 상태로 접히지 않았다")
        }
        var code: String?
        var retryAfterMs: Int64?
        if case .rejected(let rejectedCode, _, _, let rejectedRetryAfterMs) = result {
            code = rejectedCode
            retryAfterMs = rejectedRetryAfterMs
        }
        return .failed(reason: reason, code: code, retryAfterMs: retryAfterMs)
    }

    /// 응답 한 건을 화면 상태로 접는다. 최초 생성(``onResult(_:)``)과 재응시
    /// (``onRetestResult(_:)``)가 같은 규칙을 써야 하므로 판정을 한 곳에 둔다 — 갈래가 둘로 늘면
    /// 429 안내가 한쪽에서만 뜨는 식으로 어긋난다.
    private func stateOf(_ result: SessionResult) -> SessionGateState {
        switch result {
        case .created(let session):
            return .ready(session)

        case .transportError:
            return .failed(reason: .network, retryAfterSeconds: nil)

        case .rejected(let code, _, let retryable, let retryAfterMs):
            let reason: SessionFailureReason
            // 봉투를 읽었으면 코드가 정본이고, 못 읽었으면 대기 시간의 존재가 대신 말해 준다
            // — retryAfterMs를 실어 보내는 거절은 요청 제한뿐이다 (§2.5).
            if code == codeRateLimited || retryAfterMs != nil {
                reason = .rateLimited
            } else if !retryable {
                reason = .unsupported
            } else {
                reason = .server
            }
            return .failed(reason: reason, retryAfterSeconds: retryAfterMs.map(ceilSeconds))
        }
    }

    /// 새 세션 생성을 처음부터 다시 건다.
    ///
    /// 실패 화면의 [다시 시도]와 [처음으로] 인트로 복귀가 같은 자리를 쓴다 — 둘 다 "지금 들고
    /// 있는 세션(또는 실패)을 버리고 다음 시작은 새 세션으로 한다"는 같은 뜻이기 때문이다.
    /// 확보돼 있던 세션도 여기서 버려진다: 끝난 응시를 다음 시작이 이어받으면 안 된다.
    ///
    /// ``attempt``를 올리는 것이 실제로 요청을 다시 내보내는 신호다. 상태만 ``SessionGateState/creating``으로
    /// 되돌리면 이미 한 번 돈 이펙트가 다시 돌 이유가 없어 화면이 준비 중인 채로 멈춘다.
    ///
    /// 버리는 세션의 토큰은 ``pendingPreviousToken``에 남긴다 (KAN-34 2단계). 앱에서 버렸다고
    /// 서버에서 사라지는 것이 아니라, 다음 생성 요청이 그 토큰을 실어야 서버도 지운다 (KAN-107) —
    /// 그래야 복귀 후 재시작도 재응시와 같은 폐기 경로를 탄다. 실패 화면의 [다시 시도]처럼 버릴
    /// 세션이 없는 자리에서는 앞서 적어 둔 값을 지우지 않는다: 아직 실어 보내지 못한 폐기가
    /// 그대로 남아 있다.
    public func restart() {
        if let session { pendingPreviousToken = session.sessionToken }
        attempt += 1
        state = .creating
    }

    // MARK: - 저장·복원

    /// 화면 재생성·프로세스 복원을 넘기는 저장값. 안드로이드 `rememberSaveable`의 `Saver` 자리다.
    ///
    /// 저장하는 것은 확보된 세션뿐이다. 세션은 응답에서 한 번만 노출되는 토큰을 들고 있어
    /// (``Session``) 복원에 증발하면 진행 중인 응시가 통째로 죽는다.
    ///
    /// 생성 중·실패·재응시 진행 중(``retestInFlight``)·``pendingPreviousToken``은 저장하지 않는다.
    /// 앞의 셋은 살려 봐야 그 요청이 프로세스와 함께 사라졌고, 저장된 실패를 그대로 세우면 이미
    /// 풀린 429를 계속 보여준다. 복원 뒤에는 생성 중부터 다시 시작해 화면이 곧바로 요청을 건다.
    /// 그 대가로 세션 생성 중에 화면이 재생성되면 요청이 한 번 더 나가 아무 데이터도 달리지 않은
    /// 세션이 서버에 하나 남는다 — 익명이고 30분 뒤 만료되므로 감수한다.
    ///
    /// **재응시 요청 중에 복원되면** 그 요청은 화면과 함께 취소되고 복원된 컨트롤러는 이전 세션을
    /// 든 채 ``retestInFlight``가 풀린 상태로 돌아온다. 사용자는 [다시 테스트하기]를 다시 누를 수
    /// 있고, 그때 실리는 이전 토큰이 서버에서 이미 폐기됐더라도 조용히 무시되므로 새 세션을 받는다.
    public func saved() -> String {
        guard let session else { return "" }
        guard let data = try? JSONEncoder().encode(session),
              let json = String(data: data, encoding: .utf8)
        else { return "" }
        return json
    }

    /// 저장값이 깨져 있으면(구버전 형식 등) 저장이 없었던 것으로 본다 — 새 세션을 만드는 편이
    /// 반쯤 읽힌 세션으로 업로드를 거절당하는 것보다 낫다.
    public static func restored(from saved: String) -> SessionGateController {
        if saved.isEmpty { return SessionGateController() }
        guard let data = saved.data(using: .utf8),
              let session = try? JSONDecoder().decode(Session.self, from: data)
        else { return SessionGateController() }
        return SessionGateController(state: .ready(session))
    }
}
