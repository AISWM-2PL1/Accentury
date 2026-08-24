package com.accentury.app.bridge

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val json = Json

// JSON에선 평범한 문자지만 JS 소스에선 줄 종결자인 두 글자. 소스에 그대로 적으면 보이지 않아
// 코드 리뷰에서 사라지므로 코드포인트로 둔다.
private const val JS_LINE_SEPARATOR = 0x2028
private const val JS_PARAGRAPH_SEPARATOR = 0x2029

/**
 * 네이티브 → 웹 주입 JS 한 줄을 만든다 (webview-layer.md §8).
 *
 * `window.AccenturyWeb`의 수신 지점 하나를 [method] 이름으로 찾아 [payloadJson]을 넘긴다.
 * 결과 전달(KAN-100)과 재응시 실패 회신(KAN-34)이 같은 모양을 쓰므로 조립을 여기 한 곳에 둔다 —
 * 아래 이스케이프 규칙은 보안 경계라 두 벌로 늘어나면 한쪽만 늙는다.
 *
 * **JSON을 객체 리터럴로 직접 주입하지 않고, 문자열 리터럴로 넘겨 웹이 파싱하게 하는 이유:**
 * JSON은 JavaScript의 부분집합이 아니다. U+2028·U+2029는 JSON 문자열 안에서 합법이지만
 * JS 소스에서는 줄 종결자라, 그대로 주입하면 구문이 깨질 수 있다. 주입한 코드가 파싱되는지가
 * 데이터 내용에 좌우돼서는 안 된다 — 문자열 리터럴 한 겹을 거치면 이스케이프 규칙이 한 가지로
 * 고정되고, 데이터는 코드가 아니라 데이터로만 남는다.
 *
 * 이스케이프를 손으로 짜지 않고 kotlinx의 String 직렬화를 재사용하는 것도 같은 이유다 —
 * 따옴표·백슬래시·제어문자 처리는 이미 검증된 구현이 있다. 다만 그 구현은 JSON 명세를 따르므로
 * 위 두 글자를 건드리지 않는다(JSON에선 escape할 이유가 없다). JS 소스로 나갈 때만 문제가 되는
 * 차이라, 그만큼만 여기서 덧붙여 처리한다.
 *
 * 수신자가 아직 없을 수 있다. 웹의 수신 지점 설치와 네이티브의 주입은 순서가 보장되지 않으므로,
 * 없으면 아무 일도 일어나지 않아야 한다.
 *
 * 넘겼는지 여부를 boolean으로 돌려준다 (KAN-146). 옵셔널 체이닝만 쓰면 수신자가 없어도 평가가
 * 정상 완료해 "웹이 받았다"와 "아무 일도 없었다"가 구분되지 않는다.
 *
 * @param method `window.AccenturyWeb`의 수신 메서드 이름. **호출자가 코드에 적는 상수여야 한다** —
 *   이 값은 이스케이프 없이 JS 소스로 들어가므로 외부 입력이 흘러들면 그대로 주입 통로가 된다
 */
internal fun webDeliveryJs(method: String, payloadJson: String): String {
    val payloadLiteral = json.encodeToString(String.serializer(), payloadJson)
        .replace(Char(JS_LINE_SEPARATOR).toString(), "\\u2028")
        .replace(Char(JS_PARAGRAPH_SEPARATOR).toString(), "\\u2029")
    return "(function(){var f=window.AccenturyWeb&&window.AccenturyWeb.$method;" +
        "if(!f)return false;f($payloadLiteral);return true;})()"
}
