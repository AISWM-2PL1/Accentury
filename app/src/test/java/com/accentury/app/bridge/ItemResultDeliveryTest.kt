package com.accentury.app.bridge

import com.accentury.app.audio.QualityStatus
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemResultDeliveryTest {

    /** payload 리터럴을 감싸는 고정 문구. 생성기와 이 파일이 함께 움직이는 지점이다. */
    private val CALL_PREFIX = "return false;f("
    private val CALL_SUFFIX = ");return true;})()"

    private val lineSeparator = Char(0x2028)
    private val paragraphSeparator = Char(0x2029)

    private fun resultWith(analysisJobId: String) = ItemResult(
        itemId = "item_1",
        attemptId = "at-1",
        analysisJobId = analysisJobId,
        durationMs = 4_200L,
        qualityStatus = QualityStatus.NORMAL,
    )

    /**
     * 생성된 JS에서 문자열 리터럴만 떼어 원래 JSON 텍스트로 되돌린다.
     * 웹이 하는 일(리터럴 해석 → JSON.parse)을 흉내 내는 것이 왕복 검증의 요점이다.
     *
     * 고정 문구를 경계로 삼는다 — 괄호 세기로는 안 된다. 감싸는 즉시실행 함수에도, payload 값
     * 자체에도 괄호가 들어갈 수 있다.
     */
    private fun decodePayload(js: String): String {
        val literal = js.substringAfter(CALL_PREFIX).substringBeforeLast(CALL_SUFFIX)
        return Json.decodeFromString(String.serializer(), literal)
    }

    /** 왕복 후 값을 꺼내 본다. analysisJobId를 통로로 쓴다 — 값 제약이 없는 문자열 필드다. */
    private fun roundTrip(analysisJobId: String): String {
        val decoded = decodePayload(itemResultDeliveryJs(resultWith(analysisJobId)))
        return Json.parseToJsonElement(decoded).jsonObject.getValue("analysisJobId").jsonPrimitive.content
    }

    /*
     * 수신자가 없어도 무해해야 하고(웹의 수신 지점 설치와 결과 도착은 순서가 보장되지 않는다),
     * 넘겼는지 여부가 돌려주는 값으로 구분돼야 한다 (KAN-146). 호출자가 이 값으로 녹음 화면을 놓을
     * 때를 정하므로, 못 넘긴 것을 넘긴 것으로 읽으면 화면이 앞 문항의 대기 화면 위로 걷힌다.
     */
    @Test
    fun `수신자가 없으면 호출 없이 false를 돌려준다`() {
        val js = itemResultDeliveryJs(resultWith("aj_1"))

        assertTrue(js, js.contains("var f=window.AccenturyWeb&&window.AccenturyWeb.onItemResult;"))
        assertTrue(js, js.contains("if(!f)return false;"))
    }

    @Test
    fun `넘긴 경우에만 true를 돌려준다`() {
        val js = itemResultDeliveryJs(resultWith("aj_1"))

        // 호출과 true 반환이 한 덩어리다 — 호출 없이 true가 나가는 경로가 없어야 한다.
        assertTrue(js, js.endsWith(CALL_SUFFIX))
        assertEquals(1, js.split("return true").size - 1)
    }

    @Test
    fun `payload는 객체가 아니라 문자열 리터럴로 실린다`() {
        val js = itemResultDeliveryJs(resultWith("aj_1"))

        // 리터럴을 되돌린 결과가 곧 계약 JSON이어야 한다.
        val obj = Json.parseToJsonElement(decodePayload(js)).jsonObject
        assertEquals("aj_1", obj.getValue("analysisJobId").jsonPrimitive.content)
        assertEquals("NORMAL", obj.getValue("qualityStatus").jsonPrimitive.content)
        assertEquals("4200", obj.getValue("durationMs").jsonPrimitive.content)
    }

    @Test
    fun `따옴표 백슬래시 개행이 섞여도 값이 그대로 왕복한다`() {
        // 리터럴을 조기 종료시키거나 주입으로 이어질 수 있는 문자들.
        val nasty = "aj\"1\\2\n3\t4'5"

        assertEquals(nasty, roundTrip(nasty))
    }

    @Test
    fun `JS 줄 종결자는 소스에 날것으로 남지 않는다`() {
        // U+2028·U+2029는 JSON에선 합법이라 kotlinx가 손대지 않는다. JS 소스로 나가는 이 자리에서만
        // 문제가 되므로, 생성된 코드에는 이스케이프된 형태로만 있어야 한다.
        val js = itemResultDeliveryJs(resultWith("aj${lineSeparator}1${paragraphSeparator}2"))

        assertEquals(-1, js.indexOf(lineSeparator))
        assertEquals(-1, js.indexOf(paragraphSeparator))
        assertTrue(js, js.contains("\\u2028"))
        assertTrue(js, js.contains("\\u2029"))
    }

    @Test
    fun `이스케이프된 줄 종결자도 값으로는 원래 문자로 복원된다`() {
        val separators = "aj${lineSeparator}1${paragraphSeparator}2"

        assertEquals(separators, roundTrip(separators))
    }
}
