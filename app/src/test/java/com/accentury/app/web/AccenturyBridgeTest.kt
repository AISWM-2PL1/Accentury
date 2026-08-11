package com.accentury.app.web

import com.accentury.app.bridge.VoiceItemStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccenturyBridgeTest {

    /** View.post처럼 실행을 미뤄두는 큐. 브리지의 "메인 스레드로 넘긴 뒤 검증" 순서를 재현한다. */
    private class FakeMainQueue {
        private val queue = mutableListOf<() -> Unit>()
        fun post(block: () -> Unit) {
            queue += block
        }
        fun drain() {
            queue.forEach { it() }
            queue.clear()
        }
    }

    /** 계약을 채운 payload. 테스트마다 관심 있는 필드만 갈아끼운다. */
    private fun payload(
        itemId: String = "item_1",
        prompt: String = "마! 니 어데 가노?",
        itemNumber: Int = 1,
        totalItems: Int = 10,
        maxDurationMs: Long = 15_000L,
        extra: String = "",
    ) = """{"itemId":"$itemId","prompt":"$prompt","itemNumber":$itemNumber,""" +
        """"totalItems":$totalItems,"maxDurationMs":$maxDurationMs$extra}"""

    /** startVoiceItem을 한 번 호출하고 콜백이 받은 값을 돌려준다. 무시됐으면 null. */
    private fun startVoiceItem(payloadJson: String, allowed: Boolean = true): VoiceItemStart? {
        val queue = FakeMainQueue()
        var received: VoiceItemStart? = null
        val bridge = AccenturyBridge(
            postToMain = queue::post,
            isCurrentUrlAllowed = { allowed },
            onRequestMicPermission = {},
            onStartVoiceItem = { received = it },
        )
        bridge.startVoiceItem(payloadJson)
        queue.drain()
        return received
    }

    @Test
    fun `허용된 origin이면 권한 게이트 콜백이 실행된다`() {
        val queue = FakeMainQueue()
        var fired = 0
        val bridge = AccenturyBridge(
            postToMain = queue::post,
            isCurrentUrlAllowed = { true },
            onRequestMicPermission = { fired++ },
            onStartVoiceItem = {},
        )
        bridge.requestMicPermission()
        queue.drain()
        assertEquals(1, fired)
    }

    @Test
    fun `allowlist 밖 origin에서는 콜백이 실행되지 않는다`() {
        val queue = FakeMainQueue()
        var fired = 0
        val bridge = AccenturyBridge(
            postToMain = queue::post,
            isCurrentUrlAllowed = { false },
            onRequestMicPermission = { fired++ },
            onStartVoiceItem = {},
        )
        bridge.requestMicPermission()
        queue.drain()
        assertEquals(0, fired)
    }

    @Test
    fun `origin 검증은 호출 시점이 아니라 메인 스레드 실행 시점 값으로 판정한다`() {
        // JS 스레드에서 호출된 직후 페이지가 allowlist 밖으로 리다이렉트되는 경합을 재현한다.
        // 호출 시점(allowed=true)이 아니라 실행 시점(allowed=false)을 봐야 안전하다 (§8).
        val queue = FakeMainQueue()
        var allowedNow = true
        var fired = 0
        val bridge = AccenturyBridge(
            postToMain = queue::post,
            isCurrentUrlAllowed = { allowedNow },
            onRequestMicPermission = { fired++ },
            onStartVoiceItem = {},
        )
        bridge.requestMicPermission() // 호출 시점엔 허용 상태
        allowedNow = false // 실행 전에 allowlist 밖으로 이동
        queue.drain()
        assertEquals(0, fired)
    }

    @Test
    fun `getContractVersion은 앱이 보유한 계약 버전을 돌려준다`() {
        val bridge = AccenturyBridge(
            postToMain = { it() },
            isCurrentUrlAllowed = { true },
            onRequestMicPermission = {},
            onStartVoiceItem = {},
        )
        assertEquals(BRIDGE_CONTRACT_VERSION, bridge.getContractVersion())
    }

    @Test
    fun `허용된 origin이면 문항 컨텍스트가 파싱돼 콜백으로 온다`() {
        assertEquals(
            VoiceItemStart(
                itemId = "item_1",
                prompt = "마! 니 어데 가노?",
                itemNumber = 1,
                totalItems = 10,
                maxDurationMs = 15_000L,
            ),
            startVoiceItem(payload()),
        )
    }

    @Test
    fun `allowlist 밖 origin에서는 payload가 멀쩡해도 무시한다`() {
        assertNull(startVoiceItem(payload(), allowed = false))
    }

    @Test
    fun `JSON이 아니거나 필드가 빠지면 무시한다`() {
        assertNull(startVoiceItem(""))
        assertNull(startVoiceItem("{oops"))
        assertNull(startVoiceItem("[]"))
        assertNull(startVoiceItem("""{"itemId":"item_1"}"""))
    }

    @Test
    fun `녹음 화면을 그릴 수 없는 값은 무시한다`() {
        assertNull(startVoiceItem(payload(itemId = "")))
        assertNull(startVoiceItem(payload(itemId = "   ")))
        assertNull(startVoiceItem(payload(itemNumber = 0)))
        assertNull(startVoiceItem(payload(totalItems = 0)))
        assertNull(startVoiceItem(payload(maxDurationMs = 0L)))
        assertNull(startVoiceItem(payload(maxDurationMs = -1L)))
        // 진행 표기가 "11/10"이 되는 조합. 정의를 읽는 쪽의 계산 착오이므로 화면을 띄우지 않는다.
        assertNull(startVoiceItem(payload(itemNumber = 11, totalItems = 10)))
    }

    @Test
    fun `모르는 필드가 붙어 있어도 받는다 (필드 추가는 하위호환)`() {
        // 신버전 웹 + 구버전 앱 조합. 버전을 올리지 않는 변경이라 실제로 존재하는 조합이다.
        val start = startVoiceItem(payload(extra = ",\"futureField\":\"whatever\""))

        assertEquals("item_1", start?.itemId)
    }

    @Test
    fun `origin 검증은 payload 파싱보다 먼저다`() {
        // allowlist 밖 페이지가 보낸 값은 내용과 무관하게 처리 대상이 아니다.
        val queue = FakeMainQueue()
        var allowedNow = true
        var received: VoiceItemStart? = null
        val bridge = AccenturyBridge(
            postToMain = queue::post,
            isCurrentUrlAllowed = { allowedNow },
            onRequestMicPermission = {},
            onStartVoiceItem = { received = it },
        )
        bridge.startVoiceItem(payload()) // 호출 시점엔 허용 상태
        allowedNow = false // 실행 전에 allowlist 밖으로 이동
        queue.drain()

        assertNull(received)
    }

    @Test
    fun `프롬프트의 따옴표나 유니코드가 값 그대로 전달된다`() {
        val start = startVoiceItem(
            """{"itemId":"item_1","prompt":"\"밥은\" 뭇나?\n마!","itemNumber":2,""" +
                """"totalItems":10,"maxDurationMs":15000}""",
        )

        assertEquals("\"밥은\" 뭇나?\n마!", start?.prompt)
    }
}
