package com.accentury.app.bridge

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val json = Json

// JSON에선 평범한 문자지만 JS 소스에선 줄 종결자인 두 글자. 소스에 그대로 적으면 보이지 않아
// 코드 리뷰에서 사라지므로 코드포인트로 둔다.
private const val JS_LINE_SEPARATOR = 0x2028
private const val JS_PARAGRAPH_SEPARATOR = 0x2029

/**
 * 네이티브 → 웹 결과 전달에 쓸 JS 한 줄을 만든다 (KAN-100). 실제 `evaluateJavascript` 결선은
 * 화면 오케스트레이션(Stage 4) 몫이고, 이 파일은 문자열 생성만 한다 — 그래야 JVM 단위 테스트로
 * 이스케이프 규칙을 직접 검증할 수 있다.
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
 * 수신자가 아직 없을 수 있다. 웹의 수신 지점 설치와 네이티브의 결과 도착은 순서가 보장되지 않으므로,
 * 없으면 아무 일도 일어나지 않아야 한다.
 *
 * 넘겼는지 여부를 boolean으로 돌려준다 (KAN-146). 호출자는 이 값으로 녹음 화면을 놓을 때를 정하는데,
 * 옵셔널 체이닝만 쓰면 수신자가 없어도 평가가 정상 완료해 "웹이 받았다"와 "아무 일도 없었다"가
 * 구분되지 않는다. 못 넘긴 것을 넘긴 것으로 읽으면 화면이 앞 문항의 대기 화면 위로 걷힌다.
 */
fun itemResultDeliveryJs(result: ItemResult): String {
    val payloadLiteral = json.encodeToString(String.serializer(), result.toJson())
        .replace(Char(JS_LINE_SEPARATOR).toString(), "\\u2028")
        .replace(Char(JS_PARAGRAPH_SEPARATOR).toString(), "\\u2029")
    return "(function(){var f=window.AccenturyWeb&&window.AccenturyWeb.onItemResult;" +
        "if(!f)return false;f($payloadLiteral);return true;})()"
}
