package com.accentury.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharePayloadTest {

    /** 계약을 채운 payload. 테스트마다 관심 있는 필드만 갈아끼운다. */
    private fun payload(
        imageUrl: String = "https://cdn.accentury.app/share/grade-a.png",
        text: String = "내 사투리 등급은 '경상도 원어민'!",
        webTestUrl: String = "https://accentury.app/?utm_source=kakao",
        extra: String = "",
    ) = """{"imageUrl":"$imageUrl","text":"$text","webTestUrl":"$webTestUrl"$extra}"""

    @Test
    fun `계약대로 온 payload는 값 그대로 파싱된다`() {
        assertEquals(
            SharePayload(
                imageUrl = "https://cdn.accentury.app/share/grade-a.png",
                text = "내 사투리 등급은 '경상도 원어민'!",
                webTestUrl = "https://accentury.app/?utm_source=kakao",
            ),
            parseSharePayload(payload()),
        )
    }

    @Test
    fun `모르는 필드가 붙어 있어도 받는다 (필드 추가는 하위호환)`() {
        // 신버전 웹 + 구버전 앱 조합. 버전을 올리지 않는 변경이라 실제로 존재하는 조합이다 (§5).
        // 점수 같은 값이 나중에 붙어도 네이티브는 계약에 있는 셋만 읽는다.
        val parsed = parseSharePayload(payload(extra = ""","score":87,"sessionId":"s_1""""))

        assertEquals("https://accentury.app/?utm_source=kakao", parsed?.webTestUrl)
    }

    @Test
    fun `JSON이 아니거나 필드가 빠지면 무시한다`() {
        assertNull(parseSharePayload(""))
        assertNull(parseSharePayload("{oops"))
        assertNull(parseSharePayload("[]"))
        assertNull(parseSharePayload("""{"imageUrl":"https://a/b.png"}"""))
    }

    @Test
    fun `빈 값은 카드를 만들 수 없으므로 무시한다`() {
        assertNull(parseSharePayload(payload(text = "")))
        assertNull(parseSharePayload(payload(text = "   ")))
        assertNull(parseSharePayload(payload(imageUrl = "")))
        assertNull(parseSharePayload(payload(webTestUrl = "")))
    }

    @Test
    fun `https가 아닌 링크는 거부한다`() {
        // 이 값들은 화면에 그려지고 마는 게 아니라 남의 대화방까지 간다. 스킴을 열어 두면
        // 우리 앱이 임의 동작을 여는 링크의 배달부가 된다.
        assertNull(parseSharePayload(payload(webTestUrl = "javascript:alert(1)")))
        assertNull(parseSharePayload(payload(imageUrl = "javascript:alert(1)")))
        assertNull(parseSharePayload(payload(webTestUrl = "intent://evil#Intent;end")))
        assertNull(parseSharePayload(payload(imageUrl = "file:///data/data/com.accentury.app/x.png")))
        // http도 거부다 - 카카오가 이미지로 받지 않고, 평문 링크를 퍼뜨릴 이유도 없다.
        assertNull(parseSharePayload(payload(webTestUrl = "http://accentury.app/")))
        assertNull(parseSharePayload(payload(imageUrl = "http://cdn.accentury.app/a.png")))
        // 스킴은 앞에 있어야 한다 - 문자열 어딘가에 https가 섞인 값은 통과하지 못한다.
        assertNull(parseSharePayload(payload(webTestUrl = " https://accentury.app/")))
        // 스킴은 정확히 소문자 https다. 값을 정규화하지 않고 그대로 내보내므로 받은 그대로가 유효해야 한다.
        assertNull(parseSharePayload(payload(webTestUrl = "HTTPS://accentury.app/t")))
        assertNull(parseSharePayload(payload(imageUrl = "Https://cdn.accentury.app/a.png")))
    }

    @Test
    fun `https로 시작하지만 붙일 데가 없는 링크는 거부한다`() {
        // 접두사만 맞고 host가 없는 값들. 카드에 실려도 어디로도 가지 못하고, 카카오·인텐트가
        // 이런 값을 어떻게 다루는지는 받는 쪽 구현에 달려 있다.
        assertNull(parseSharePayload(payload(webTestUrl = "https://")))
        assertNull(parseSharePayload(payload(imageUrl = "https://")))
        assertNull(parseSharePayload(payload(webTestUrl = "https:///t")))
        assertNull(parseSharePayload(payload(imageUrl = "https:///a.png")))
        // 공백이 섞인 값은 URL이 아니다.
        assertNull(parseSharePayload(payload(webTestUrl = "https://accentury.app/t 1")))
        assertNull(parseSharePayload(payload(imageUrl = "https://cdn accentury.app/a.png")))
    }

    @Test
    fun `캠페인 파라미터가 붙은 정상 URL은 그대로 통과한다`() {
        assertEquals(
            "https://accentury.app/t?c=kko_share",
            parseSharePayload(payload(webTestUrl = "https://accentury.app/t?c=kko_share"))?.webTestUrl,
        )
    }

    @Test
    fun `문구가 카카오 템플릿 상한을 넘으면 무시한다`() {
        assertEquals(200, parseSharePayload(payload(text = "가".repeat(200)))?.text?.length)
        assertNull(parseSharePayload(payload(text = "가".repeat(201))))
    }
}
