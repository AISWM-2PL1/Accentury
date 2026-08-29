import Foundation

/// 응답이 오지 않은 전송 실패를, 사용자에게 서로 다른 말을 해줄 수 있는 만큼만 갈라놓은 것.
/// 안드로이드 `app/src/main/java/com/accentury/app/net/TransportFailure.kt`의 1:1 이식본이다.
///
/// 한계를 먼저 적어둔다: 응답이 없는 실패라 서버가 무슨 일을 겪었는지는 알 방법이 없다. 여기서
/// 가르는 것은 정확한 원인이 아니라 **누구 쪽에서 끊겼는지** 정도다 - 기기 쪽(``offline``), 서버
/// 쪽(``serverUnreachable``), 어느 쪽인지 말할 수 없는 지연(``timeout``)과 나머지(``unknown``).
/// 예외 종류가 주는 힌트가 거기까지고, 그 이상을 문구로 단정하면 틀린 안내가 된다.
///
/// `NWPathMonitor`(안드로이드의 `ConnectivityManager` 자리)는 일부러 쓰지 않는다. 안 그래도 알 수
/// 있는 구분을 얻자고 네트워크 클라이언트에 시스템 감시자를 끌어들이면 시뮬레이터 없는
/// `swift test`에서 이 판정을 돌릴 수 없게 된다. 연결이 살아 있는데 요청이 못 나가는
/// 경우(캡티브 포털 등)도 있어서, 시스템에 물어본 답이 더 정확하다는 보장도 없다.
public enum TransportFailure: Sendable, Equatable {

    /// 이름조차 못 찾았다. 기기가 망에 못 붙어 있을 때 나오는 전형적인 모습이다.
    case offline

    /// 주소는 찾았는데 연결을 거절당했다. 망은 살아 있고 서버 쪽이 안 받는 상태다.
    case serverUnreachable

    /// 붙긴 했는데 정해둔 시간 안에 끝나지 않았다. 느린 망인지 느린 서버인지는 가릴 수 없다.
    case timeout

    /// 위 어디에도 안 들어가는 전송 실패. 원인을 짐작해 말하지 않는다.
    case unknown
}

public extension TransportFailure {

    /// 전송 중 터진 오류를 갈래로 옮긴다.
    ///
    /// 안드로이드는 `IOException`의 하위 타입(`UnknownHostException`·`ConnectException`·
    /// `InterruptedIOException`)으로 갈랐다. `URLSession`은 예외 계층 대신 `URLError.Code`
    /// 하나로 원인을 주므로 **상속 관계 대신 코드 집합**으로 같은 갈래를 만든다:
    ///
    /// | 안드로이드 | iOS (`URLError.Code`) |
    /// |---|---|
    /// | `UnknownHostException` | `.notConnectedToInternet`, `.cannotFindHost`, `.dnsLookupFailed` |
    /// | `ConnectException` | `.cannotConnectToHost`, `.networkConnectionLost` |
    /// | `InterruptedIOException`(소켓 타임아웃·callTimeout) | `.timedOut` |
    /// | 그 외 `IOException` | 그 외 전부 (`URLError`가 아닌 오류 포함) |
    ///
    /// `.notConnectedToInternet`이 ``offline``에 있는 것은 정본보다 나아진 부분이 아니라 같은
    /// 뜻이다 — 안드로이드에서 망이 끊긴 기기는 DNS부터 실패해 `UnknownHostException`으로 온다.
    /// iOS는 그 상황을 시스템이 먼저 알아채 별도 코드로 주는 것뿐이라 같은 문구로 모은다.
    ///
    /// 전체 호출 상한(`timeoutIntervalForResource`)이 끊을 때도 `.timedOut`이다 —
    /// 안드로이드에서 OkHttp `callTimeout`과 소켓 타임아웃이 `InterruptedIOException` 하나로
    /// 모이는 것과 같은 자리다.
    static func from(_ error: Error) -> TransportFailure {
        guard let urlError = error as? URLError else { return .unknown }
        switch urlError.code {
        case .notConnectedToInternet, .cannotFindHost, .dnsLookupFailed:
            return .offline
        case .cannotConnectToHost, .networkConnectionLost:
            return .serverUnreachable
        case .timedOut:
            return .timeout
        default:
            return .unknown
        }
    }

    /// 전송 실패를 사용자가 읽을 한 줄로 옮긴다 (KAN-147 2단계).
    ///
    /// 네트워크 스택의 문구("The request timed out.", "A server with the specified hostname
    /// could not be found.")를 그대로 상태 바에 태우면 사용자는 자기가 끊긴 건지 서버가 죽은 건지
    /// 알 수 없다. 원인을 단정할 수 있는 만큼만 말하고, 어느 쪽이든 복구 수단은 하나([재시도])라
    /// 문구는 전부 "다시 시도" 쪽으로 모은다.
    ///
    /// 안드로이드는 이 확장이 `UploadManager.kt`의 private 함수다. 여기서 파일을 옮긴 이유는
    /// 문구가 갈래에 딸린 값이어서다 — 같은 문구를 §6b의 화면 계층이 다시 적는 일을 막는다.
    var userMessage: String {
        switch self {
        case .offline: return "인터넷 연결을 확인해 주세요"
        case .serverUnreachable: return "서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요"
        case .timeout: return "응답이 늦어요. 다시 시도해 주세요"
        case .unknown: return "전송에 실패했어요. 다시 시도해 주세요"
        }
    }
}
