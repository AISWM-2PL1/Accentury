import Foundation

// JSON에선 평범한 문자지만 JS 소스에선 줄 종결자인 두 글자. 소스에 그대로 적으면 보이지 않아
// 코드 리뷰에서 사라지므로 코드포인트로 둔다.
private let jsLineSeparator = "\u{2028}"
private let jsParagraphSeparator = "\u{2029}"

/// 네이티브 → 웹 주입 JS 한 줄을 만든다 (webview-layer.md §8).
///
/// `window.AccenturyWeb`의 수신 지점 하나를 `method` 이름으로 찾아 `payloadJson`을 넘긴다.
/// 결과 전달(KAN-100)과 재응시 실패 회신(KAN-34)이 같은 모양을 쓰므로 조립을 여기 한 곳에 둔다 —
/// 아래 이스케이프 규칙은 보안 경계라 두 벌로 늘어나면 한쪽만 늙는다.
///
/// **JSON을 객체 리터럴로 직접 주입하지 않고, 문자열 리터럴로 넘겨 웹이 파싱하게 하는 이유:**
/// JSON은 JavaScript의 부분집합이 아니다. U+2028·U+2029는 JSON 문자열 안에서 합법이지만
/// JS 소스에서는 줄 종결자라, 그대로 주입하면 구문이 깨질 수 있다. 주입한 코드가 파싱되는지가
/// 데이터 내용에 좌우돼서는 안 된다 — 문자열 리터럴 한 겹을 거치면 이스케이프 규칙이 한 가지로
/// 고정되고, 데이터는 코드가 아니라 데이터로만 남는다.
///
/// 수신자가 아직 없을 수 있다. 웹의 수신 지점 설치와 네이티브의 주입은 순서가 보장되지 않으므로,
/// 없으면 아무 일도 일어나지 않아야 한다.
///
/// 넘겼는지 여부를 boolean으로 돌려준다 (KAN-146). 옵셔널 체이닝만 쓰면 수신자가 없어도 평가가
/// 정상 완료해 "웹이 받았다"와 "아무 일도 없었다"가 구분되지 않는다.
///
/// - Parameter method: `window.AccenturyWeb`의 수신 메서드 이름. **호출자가 코드에 적는 상수여야
///   한다** — 이 값은 이스케이프 없이 JS 소스로 들어가므로 외부 입력이 흘러들면 그대로 주입 통로가 된다.
func webDeliveryJs(method: String, payloadJson: String) -> String {
    let payloadLiteral = jsonStringLiteral(payloadJson)
        .replacingOccurrences(of: jsLineSeparator, with: "\\u2028")
        .replacingOccurrences(of: jsParagraphSeparator, with: "\\u2029")
    return "(function(){var f=window.AccenturyWeb&&window.AccenturyWeb.\(method);"
        + "if(!f)return false;f(\(payloadLiteral));return true;})()"
}

/// 문자열 하나를 JSON 문자열 리터럴(따옴표 포함)로 인코딩한다.
///
/// 안드로이드는 `Json.encodeToString(String.serializer(), s)`로 kotlinx의 검증된 구현을 재사용한다.
/// 이쪽에서 `JSONEncoder`를 쓰지 않고 손으로 적은 이유는 **두 플랫폼의 출력이 한 글자도 달라지면
/// 안 되기 때문**이다 — Foundation은 `/`를 `\/`로 이스케이프하고 `\b`·`\f` 단축 표기를 쓰지 않아
/// 같은 값이 다른 리터럴이 된다. 아래 표는 kotlinx의 `ESCAPE_STRINGS`를 그대로 옮긴 것이다:
/// 0x00–0x1F는 소문자 `\u00xx`, 그중 다섯 개만 단축 표기, 그리고 `"`와 `\`.
/// 0x7F(DEL)이나 `/`는 kotlinx도 건드리지 않으므로 여기서도 건드리지 않는다.
///
/// **`public`인 이유**(5b 요청): 앱 계층의 브리지 shim도 `evaluateJavaScript`로 값을 주입할 때
/// 같은 규칙을 써야 한다(세션 토큰 주입 등). 이 루틴은 보안 경계라 두 벌로 늘어나면 한쪽만 늙는다 —
/// ``webDeliveryJs(method:payloadJson:)``가 두 전달 경로를 한 곳에 모은 것과 같은 이유다.
/// 값을 JS 소스에 싣는 자리라면 앱 계층에서도 반드시 이 함수를 거칠 것.
///
/// 다만 주입 문장 자체를 만드는 ``webDeliveryJs(method:payloadJson:)``는 `internal`로 남긴다 —
/// 그쪽은 `window.AccenturyWeb` 수신 지점 계약에 묶인 함수라 Core 밖에서 쓸 일이 없다.
public func jsonStringLiteral(_ value: String) -> String {
    var out = "\""
    for scalar in value.unicodeScalars {
        switch scalar {
        case "\"": out += "\\\""
        case "\\": out += "\\\\"
        case "\u{08}": out += "\\b"
        case "\u{09}": out += "\\t"
        case "\u{0A}": out += "\\n"
        case "\u{0C}": out += "\\f"
        case "\u{0D}": out += "\\r"
        default:
            if scalar.value < 0x20 {
                out += String(format: "\\u%04x", scalar.value)
            } else {
                out.unicodeScalars.append(scalar)
            }
        }
    }
    out += "\""
    return out
}
