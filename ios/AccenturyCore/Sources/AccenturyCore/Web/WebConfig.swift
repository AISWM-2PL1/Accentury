import Foundation

/// 앱이 보유한 브리지 계약 버전 (webview-layer.md §5).
/// 규칙: 메서드·필드 추가는 하위호환이라 버전을 유지하고, 삭제·의미 변경 시에만 올린다.
/// ItemResult 5필드(KAN-89 계약)를 바꾸는 변경도 반드시 버전 증가 대상이다.
public let bridgeContractVersion = 1

/// 로드 실패 판정 자체 타임아웃 (§6). 페이지 로드 완료 콜백이 영영 안 오는 경우를 대비한다.
/// 8초 = Nielsen 10초 주의력 한계 직전, "진입 → 결과 3분" 목표와 정합하는 제안값.
///
/// 안드로이드는 `LOAD_TIMEOUT_MS`를 밀리초 `Long`으로 들고 있다. iOS 쪽 타이머 API가
/// 전부 초 단위 `TimeInterval`을 받으므로 여기서는 단위를 초로 바꿔 담는다 — 값 자체(8초)는 같다.
public let loadTimeout: TimeInterval = 8

/// 테스트 진입 파라미터 (KAN-100). 시작 게이트(KAN-98)를 통과한 뒤의 정식 진입 URL에만 붙는다 —
/// 웹은 `screen=test`를 보고 인트로 대신 문항 진행 화면으로 들어간다 (web/src/App.tsx).
public struct TestEntry: Equatable, Sendable {
    /// 세션에 고정된 정의 버전. 웹이 `GET /v0/tests/{testVersion}`으로 정의를 받는다.
    public let testVersion: String
    /// 진행 스냅샷을 세션별로 가르는 식별자. 업로드가 붙는 세션과 같은 값이어야 한다.
    public let sessionId: String

    public init(testVersion: String, sessionId: String) {
        self.testVersion = testVersion
        self.sessionId = sessionId
    }
}

/// WebView가 로드할 최종 URL. 브리지 버전과 앱 버전을 쿼리로 실어 보낸다 —
/// 스큐 판정의 주체는 웹이므로(§5) 앱은 자기 버전을 알리기만 하면 된다.
///
/// `testEntry`가 있으면 테스트 진입 URL, 없으면 인트로 URL이다. 두 URL을 한 함수로 묶은 이유:
/// 스큐 파라미터는 어느 쪽에도 빠지면 안 되는데(빠지면 웹이 업데이트 안내를 띄운다) 조립을
/// 나누면 한쪽만 고치는 실수가 생긴다.
public func buildWebUrl(base: String, appVersionName: String, testEntry: TestEntry? = nil) -> String {
    let separator: Character = base.contains("?") ? "&" : "?"
    var query = "bridge=\(bridgeContractVersion)&app=\(encodeQueryValue(appVersionName))"
    if let testEntry {
        query += "&screen=test"
        query += "&testVersion=\(encodeQueryValue(testEntry.testVersion))"
        query += "&sessionId=\(encodeQueryValue(testEntry.sessionId))"
    }
    return "\(base)\(separator)\(query)"
}

/// 값에 든 `&`·`=`·공백이 쿼리 구조를 깨뜨리지 않게 한다. 앱 버전은 물론 서버가 발급하는
/// testVersion·sessionId도 형식을 앱이 정하지 않으므로 전부 거쳐 간다.
///
/// 안드로이드가 쓰는 `URLEncoder.encode(value, "UTF-8")`(application/x-www-form-urlencoded)와
/// 같은 결과를 낸다 — 공백은 `%20`이 아니라 `+`, 그리고 `.` `-` `*` `_`만 영숫자와 함께 그대로 남는다.
/// Foundation의 `addingPercentEncoding`은 공백을 `%20`으로 만들어 두 플랫폼의 URL이 갈리므로 쓰지 않는다.
private func encodeQueryValue(_ value: String) -> String {
    var out = ""
    for byte in Array(value.utf8) {
        switch byte {
        case UInt8(ascii: "a")...UInt8(ascii: "z"),
             UInt8(ascii: "A")...UInt8(ascii: "Z"),
             UInt8(ascii: "0")...UInt8(ascii: "9"),
             UInt8(ascii: "."), UInt8(ascii: "-"), UInt8(ascii: "*"), UInt8(ascii: "_"):
            out.append(Character(UnicodeScalar(byte)))
        case UInt8(ascii: " "):
            out.append("+")
        default:
            out += String(format: "%%%02X", byte)
        }
    }
    return out
}

/// URL의 origin(스킴://호스트[:포트])을 뽑는다. http(s)가 아니거나(javascript: 등)
/// 파싱이 안 되면 nil — allowlist 비교의 입력을 한 가지 꼴로 좁히는 함수다.
/// 기본 포트(80/443)는 표기 유무가 같은 origin이 되도록 지운다.
public func webOrigin(_ url: String) -> String? {
    guard let components = URLComponents(string: url) else { return nil }
    guard let scheme = components.scheme?.lowercased() else { return nil }
    guard scheme == "http" || scheme == "https" else { return nil }
    guard let rawHost = components.host, !rawHost.isEmpty else { return nil }
    let host = rawHost.lowercased()
    let port: Int? = switch (scheme, components.port) {
    case (_, nil): nil
    case ("http", 80): nil
    case ("https", 443): nil
    case (_, let value): value
    }
    guard let port else { return "\(scheme)://\(host)" }
    return "\(scheme)://\(host):\(port)"
}

/// allowlist 검사 (§7). 브리지가 마이크 권한 게이트를 호출하므로 이 검사가 곧 보안 경계다 —
/// allowlist 밖 URL은 WebView 로드도, 브리지 실행도 막는다.
public func isAllowedWebUrl(_ url: String?, allowedOrigins: Set<String>) -> Bool {
    guard let url, let origin = webOrigin(url) else { return false }
    return allowedOrigins.contains(origin)
}
