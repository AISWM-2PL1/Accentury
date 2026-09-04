package com.accentury.app.bridge

import com.accentury.app.session.RetestOutcome
import com.accentury.app.session.SessionFailureReason
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetestFailedDeliveryTest {

    /** payload 리터럴을 감싸는 고정 문구. 생성기와 이 파일이 함께 움직이는 지점이다. */
    private val CALL_PREFIX = "return false;f("
    private val CALL_SUFFIX = ");return true;})()"

    private fun failed(
        reason: SessionFailureReason,
        code: String? = null,
        retryAfterMs: Long? = null,
    ) = RetestOutcome.Failed(reason = reason, code = code, retryAfterMs = retryAfterMs)

    /** 생성된 JS에서 문자열 리터럴만 떼어 원래 JSON 텍스트로 되돌린다 (ItemResultDeliveryTest와 같은 방식). */
    private fun decodePayload(js: String): String {
        val literal = js.substringAfter(CALL_PREFIX).substringBeforeLast(CALL_SUFFIX)
        return Json.decodeFromString(String.serializer(), literal)
    }

    // --- payload 조립 (RetestFailure.kt) ---

    @Test
    fun `요청 제한은 대기 시간을 그대로 실어 보낸다 - 웹이 그것으로 안내를 만든다`() {
        val payload = retestFailurePayload(
            failed(SessionFailureReason.RateLimited, code = "RATE_LIMITED", retryAfterMs = 5_000L),
        )

        assertEquals("RATE_LIMITED", payload.code)
        assertEquals(5_000L, payload.retryAfterMs)
        assertTrue(payload.retryable)
    }

    @Test
    fun `서버가 재시도 불가로 못박은 거절만 retryable이 false다`() {
        assertFalse(retestFailurePayload(failed(SessionFailureReason.Unsupported)).retryable)
        assertTrue(retestFailurePayload(failed(SessionFailureReason.Network)).retryable)
        assertTrue(retestFailurePayload(failed(SessionFailureReason.Server)).retryable)
        assertTrue(retestFailurePayload(failed(SessionFailureReason.RateLimited)).retryable)
    }

    @Test
    fun `봉투를 못 읽은 응답의 code는 null이다 - 앱이 자기 판정을 코드처럼 지어내지 않는다`() {
        assertEquals(null, retestFailurePayload(failed(SessionFailureReason.Network)).code)
    }

    @Test
    fun `갈래마다 보여줄 문구가 다르다 - 빈 문구로 나가는 갈래가 없어야 한다`() {
        val messages = SessionFailureReason.entries.map { retestFailurePayload(failed(it)).message }

        assertTrue(messages.toString(), messages.all { it.isNotBlank() })
        assertEquals(SessionFailureReason.entries.size, messages.toSet().size)
    }

    // --- 주입 JS (RetestFailedDelivery.kt) ---

    @Test
    fun `수신자가 없으면 호출 없이 false를 돌려준다`() {
        val js = retestFailedDeliveryJs(retestFailurePayload(failed(SessionFailureReason.Network)))

        assertTrue(js, js.contains("var f=window.AccenturyWeb&&window.AccenturyWeb.onRetestFailed;"))
        assertTrue(js, js.contains("if(!f)return false;"))
    }

    @Test
    fun `결과 수신 지점이 아니라 재응시 수신 지점으로 간다`() {
        val js = retestFailedDeliveryJs(retestFailurePayload(failed(SessionFailureReason.Network)))

        // 잘못된 슬롯으로 가면 진행 화면이 실패 회신을 문항 결과로 읽으려 든다.
        assertFalse(js, js.contains("onItemResult"))
    }

    @Test
    fun `payload는 객체가 아니라 문자열 리터럴로 실린다`() {
        val js = retestFailedDeliveryJs(
            retestFailurePayload(
                failed(SessionFailureReason.RateLimited, code = "RATE_LIMITED", retryAfterMs = 5_000L),
            ),
        )

        val obj = Json.parseToJsonElement(decodePayload(js)).jsonObject
        assertEquals("RATE_LIMITED", obj.getValue("code").jsonPrimitive.content)
        assertEquals("5000", obj.getValue("retryAfterMs").jsonPrimitive.content)
        assertEquals("true", obj.getValue("retryable").jsonPrimitive.content)
        assertTrue(obj.getValue("message").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `없는 값은 필드를 빼는 것이 아니라 null로 실린다 - 웹 파서가 세 필드를 계약으로 읽는다`() {
        val js = retestFailedDeliveryJs(retestFailurePayload(failed(SessionFailureReason.Network)))

        val obj = Json.parseToJsonElement(decodePayload(js)).jsonObject
        assertEquals(JsonNull, obj.getValue("code"))
        assertEquals(JsonNull, obj.getValue("retryAfterMs"))
    }

    @Test
    fun `서버 문구에 따옴표나 JS 줄 종결자가 섞여도 주입이 깨지지 않는다`() {
        // code는 서버가 준 값을 그대로 싣는 자리라, 값 제약이 없는 유일한 통로다.
        val nasty = "RATE\"LIMITED\\1\n2${Char(0x2028)}3${Char(0x2029)}4"
        val js = retestFailedDeliveryJs(
            retestFailurePayload(failed(SessionFailureReason.RateLimited, code = nasty)),
        )

        assertEquals(-1, js.indexOf(Char(0x2028)))
        assertEquals(-1, js.indexOf(Char(0x2029)))
        val obj = Json.parseToJsonElement(decodePayload(js)).jsonObject
        assertEquals(nasty, obj.getValue("code").jsonPrimitive.content)
    }
}
