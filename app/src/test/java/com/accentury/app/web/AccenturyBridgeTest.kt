package com.accentury.app.web

import com.accentury.app.analytics.EventParam
import com.accentury.app.bridge.GuideF0
import com.accentury.app.bridge.SharePayload
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
            isOriginAllowedNow = { false },
            sessionToken = { "" },
            onRequestMicPermission = {},
            onStartVoiceItem = { received = it },
            onStartRetest = {},
            onShareResult = {},
            onLogEvent = { _, _ -> },
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
            isOriginAllowedNow = { false },
            sessionToken = { "" },
            onRequestMicPermission = { fired++ },
            onStartVoiceItem = {},
            onStartRetest = {},
            onShareResult = {},
            onLogEvent = { _, _ -> },
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
            isOriginAllowedNow = { false },
            sessionToken = { "" },
            onRequestMicPermission = { fired++ },
            onStartVoiceItem = {},
            onStartRetest = {},
            onShareResult = {},
            onLogEvent = { _, _ -> },
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
            isOriginAllowedNow = { false },
            sessionToken = { "" },
            onRequestMicPermission = { fired++ },
            onStartVoiceItem = {},
            onStartRetest = {},
            onShareResult = {},
            onLogEvent = { _, _ -> },
        )
        bridge.requestMicPermission() // 호출 시점엔 허용 상태
        allowedNow = false // 실행 전에 allowlist 밖으로 이동
        queue.drain()
        assertEquals(0, fired)
    }

    /** startRetest를 한 번 호출하고 콜백이 몇 번 불렸는지 돌려준다. */
    private fun startRetest(allowed: Boolean): Int {
        val queue = FakeMainQueue()
        var fired = 0
        val bridge = AccenturyBridge(
            postToMain = queue::post,
            isCurrentUrlAllowed = { allowed },
            isOriginAllowedNow = { false },
            sessionToken = { "" },
            onRequestMicPermission = {},
            onStartVoiceItem = {},
            onStartRetest = { fired++ },
            onShareResult = {},
            onLogEvent = { _, _ -> },
        )
        bridge.startRetest()
        queue.drain()
        return fired
    }

    @Test
    fun `허용된 origin이면 재응시 콜백이 실행된다`() {
        assertEquals(1, startRetest(allowed = true))
    }

    @Test
    fun `allowlist 밖 origin에서는 재응시가 무시된다`() {
        // 재응시는 서버 쪽 세션·결과를 즉시 폐기시키는 호출이라(KAN-107) origin 검증이 곧 보안 경계다.
        assertEquals(0, startRetest(allowed = false))
    }

    @Test
    fun `재응시도 실행 시점 origin으로 판정한다`() {
        // 호출 직후 allowlist 밖으로 리다이렉트되는 경합. 판정은 메인 스레드 실행 시점 값이어야 한다 (§8).
        val queue = FakeMainQueue()
        var allowedNow = true
        var fired = 0
        val bridge = AccenturyBridge(
            postToMain = queue::post,
            isCurrentUrlAllowed = { allowedNow },
            isOriginAllowedNow = { false },
            sessionToken = { "" },
            onRequestMicPermission = {},
            onStartVoiceItem = {},
            onStartRetest = { fired++ },
            onShareResult = {},
            onLogEvent = { _, _ -> },
        )
        bridge.startRetest() // 호출 시점엔 허용 상태
        allowedNow = false // 실행 전에 allowlist 밖으로 이동
        queue.drain()

        assertEquals(0, fired)
    }

    @Test
    fun `연타는 브리지가 아니라 콜백 너머에서 걸러진다 - 브리지는 호출을 그대로 넘긴다`() {
        /*
         * 진행 중이라는 사실의 주인은 상태 머신 하나여야 한다 (SessionGateController.retestInFlight).
         * 브리지에도 플래그를 두면 두 값이 어긋나는 상태가 생기고, 어긋나는 순간 막으려던 이중
         * 요청이 새어 나간다. 그래서 브리지는 세지 않고, 여기서는 그 계약을 못박는다.
         */
        val queue = FakeMainQueue()
        var fired = 0
        val bridge = AccenturyBridge(
            postToMain = queue::post,
            isCurrentUrlAllowed = { true },
            isOriginAllowedNow = { false },
            sessionToken = { "" },
            onRequestMicPermission = {},
            onStartVoiceItem = {},
            onStartRetest = { fired++ },
            onShareResult = {},
            onLogEvent = { _, _ -> },
        )
        bridge.startRetest()
        bridge.startRetest()
        queue.drain()

        assertEquals(2, fired)
    }

    @Test
    fun `getContractVersion은 앱이 보유한 계약 버전을 돌려준다`() {
        val bridge = AccenturyBridge(
            postToMain = { it() },
            isCurrentUrlAllowed = { true },
            isOriginAllowedNow = { false },
            sessionToken = { "" },
            onRequestMicPermission = {},
            onStartVoiceItem = {},
            onStartRetest = {},
            onShareResult = {},
            onLogEvent = { _, _ -> },
        )
        assertEquals(BRIDGE_CONTRACT_VERSION, bridge.getContractVersion())
    }

    /** getSessionToken은 동기 반환이라 postToMain을 타지 않는다 — 플래그 검증만 본다. */
    private fun bridgeForToken(originAllowedNow: Boolean, token: String) = AccenturyBridge(
        postToMain = { it() },
        isCurrentUrlAllowed = { true },
        isOriginAllowedNow = { originAllowedNow },
        sessionToken = { token },
        onRequestMicPermission = {},
        onStartVoiceItem = {},
        onStartRetest = {},
        onShareResult = {},
        onLogEvent = { _, _ -> },
    )

    @Test
    fun `허용된 origin이면 세션 토큰을 돌려준다`() {
        assertEquals("token-1", bridgeForToken(originAllowedNow = true, token = "token-1").getSessionToken())
    }

    @Test
    fun `allowlist 밖 origin에서는 토큰 대신 빈 문자열이다`() {
        // 비밀값이므로 거부 신호도 조용해야 한다 — 예외를 던지면 웹 콘솔에 흔적이 남는다.
        assertEquals("", bridgeForToken(originAllowedNow = false, token = "token-1").getSessionToken())
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
    fun `guideF0가 실려 있으면 무성 null까지 값 그대로 파싱된다`() {
        val start = startVoiceItem(
            payload(extra = ""","guideF0":{"unit":"semitone","frameIntervalMs":10,"values":[0.5,null,-1.2]}"""),
        )

        assertEquals(
            GuideF0(unit = "semitone", frameIntervalMs = 10, values = listOf(0.5, null, -1.2)),
            start?.guideF0,
        )
    }

    @Test
    fun `guideF0가 없는 구버전 웹 payload도 받는다 (가이드 레인만 비운다)`() {
        val start = startVoiceItem(payload())

        assertEquals("item_1", start?.itemId)
        assertNull(start?.guideF0)
    }

    @Test
    fun `guideF0 속의 모르는 필드(허용 밴드)는 무성 null이 섞여 있어도 무시하고 받는다`() {
        // 밴드는 채점 층위라 네이티브 계약에 없다. 타입으로 들고 있으면 읽지도 않는 필드의
        // 형태(무성 프레임 null)가 payload 전체를 거부하게 만든다 — 그 회귀를 여기서 막는다.
        val start = startVoiceItem(
            payload(
                extra = ""","guideF0":{"unit":"semitone","frameIntervalMs":10,""" +
                    """"values":[0.5],"bandLow":[null],"bandHigh":[1.0]}""",
            ),
        )

        assertEquals(GuideF0(unit = "semitone", frameIntervalMs = 10, values = listOf(0.5)), start?.guideF0)
    }

    @Test
    fun `guideF0 형태가 불량이면 곡선만 버리고 녹음 컨텍스트는 받는다`() {
        // guideF0 내용은 웹 빌드가 아니라 서버가 발행한 정의에서 온다 - 정의 데이터 한 줄이
        // 문항 진행 전체를 막으면 안 되므로, 다른 필드와 달리 payload째 거부하지 않는다.
        val badShapes = listOf(
            ""","guideF0":{"unit":"semitone","frameIntervalMs":10,"values":["x"]}""", // 값 타입 불일치
            ""","guideF0":{"frameIntervalMs":10,"values":[0.5]}""", // unit 누락
            ""","guideF0":{"unit":"semitone","frameIntervalMs":10.5,"values":[0.5]}""", // 정수 자리에 실수
            ""","guideF0":42""", // 객체가 아님
        )
        badShapes.forEach { extra ->
            val start = startVoiceItem(payload(extra = extra))
            assertEquals("불량 guideF0에도 문항은 받아야 한다: $extra", "item_1", start?.itemId)
            assertNull("불량 guideF0는 버려져야 한다: $extra", start?.guideF0)
        }
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
            isOriginAllowedNow = { false },
            sessionToken = { "" },
            onRequestMicPermission = {},
            onStartVoiceItem = { received = it },
            onStartRetest = {},
            onShareResult = {},
            onLogEvent = { _, _ -> },
        )
        bridge.startVoiceItem(payload()) // 호출 시점엔 허용 상태
        allowedNow = false // 실행 전에 allowlist 밖으로 이동
        queue.drain()

        assertNull(received)
    }

    /** shareResult를 한 번 호출하고 콜백이 받은 값을 돌려준다. 무시됐으면 null. */
    private fun shareResult(payloadJson: String, allowed: Boolean = true): SharePayload? {
        val queue = FakeMainQueue()
        var received: SharePayload? = null
        val bridge = AccenturyBridge(
            postToMain = queue::post,
            isCurrentUrlAllowed = { allowed },
            isOriginAllowedNow = { false },
            sessionToken = { "" },
            onRequestMicPermission = {},
            onStartVoiceItem = {},
            onStartRetest = {},
            onShareResult = { received = it },
            onLogEvent = { _, _ -> },
        )
        bridge.shareResult(payloadJson)
        queue.drain()
        return received
    }

    private val sharePayloadJson =
        """{"imageUrl":"https://cdn.accentury.app/share/grade-a.png",""" +
            """"text":"\uB0B4 \uB4F1\uAE09!","webTestUrl":"https://accentury.app/?utm_source=kakao"}"""

    @Test
    fun `허용된 origin이면 공유 카드가 파싱돼 콜백으로 온다`() {
        assertEquals(
            SharePayload(
                imageUrl = "https://cdn.accentury.app/share/grade-a.png",
                text = "내 등급!",
                webTestUrl = "https://accentury.app/?utm_source=kakao",
            ),
            shareResult(sharePayloadJson),
        )
    }

    @Test
    fun `allowlist 밖 origin에서는 공유 payload가 멀쩡해도 무시한다`() {
        // 이 payload는 카카오 템플릿과 공유 인텐트를 타고 앱 밖으로 나간다 - allowlist 밖 페이지가
        // 우리 앱 이름으로 링크를 뿌리는 통로가 되면 안 된다.
        assertNull(shareResult(sharePayloadJson, allowed = false))
    }

    @Test
    fun `공유 payload가 불량이면 무시한다`() {
        assertNull(shareResult("{oops"))
        assertNull(shareResult("""{"imageUrl":"https://a/b.png","text":"x"}"""))
        // https가 아닌 링크. 검증은 parseSharePayload가 하고, 브리지는 그 결과를 그대로 따른다.
        assertNull(
            shareResult(
                """{"imageUrl":"https://a/b.png","text":"x","webTestUrl":"javascript:alert(1)"}""",
            ),
        )
    }

    /** logEvent를 한 번 호출하고 창구가 받은 이벤트를 돌려준다. 버려졌으면 null. */
    private fun logEvent(
        name: String,
        paramsJson: String,
        allowed: Boolean = true,
    ): Pair<String, Map<String, EventParam>>? {
        val queue = FakeMainQueue()
        var received: Pair<String, Map<String, EventParam>>? = null
        val bridge = AccenturyBridge(
            postToMain = queue::post,
            isCurrentUrlAllowed = { allowed },
            isOriginAllowedNow = { false },
            sessionToken = { "" },
            onRequestMicPermission = {},
            onStartVoiceItem = {},
            onStartRetest = {},
            onShareResult = {},
            onLogEvent = { eventName, params -> received = eventName to params },
        )
        bridge.logEvent(name, paramsJson)
        queue.drain()
        return received
    }

    @Test
    fun `허용된 origin이면 계측 이벤트가 타입을 살린 채 창구로 온다`() {
        // 숫자가 숫자로 남아야 GA4에서 평균·P95를 낼 수 있다 (KAN-33 AC). 변환 규칙 자체는
        // EventParamsTest가 못박고, 여기서는 브리지가 그 결과를 그대로 넘기는지만 본다.
        assertEquals(
            "analysis_wait_duration" to mapOf(
                "duration_ms" to EventParam.Count(8_200L),
                "pending_item_count" to EventParam.Count(2L),
            ),
            logEvent("analysis_wait_duration", """{"duration_ms":8200,"pending_item_count":2}"""),
        )
    }

    @Test
    fun `allowlist 밖 origin에서는 계측 이벤트도 무시한다`() {
        // 계측은 집계 축이라 한 번 쌓이면 지울 수 없다 - allowlist 밖 페이지가 우리 대시보드에
        // 값을 남기는 통로가 되면 안 된다.
        assertNull(logEvent("item_shown", """{"item_seq":1}""", allowed = false))
    }

    @Test
    fun `불량 JSON이나 규격 밖 이벤트명은 조용히 버린다`() {
        // 이벤트 하나를 잃는 편이 낫다 - 웹은 오류를 돌려줄 상대가 아니고(§8), 규격 밖 이름이
        // 흘러가면 GA4에 지울 수 없는 축이 생긴다. 버렸다는 사실은 Crashlytics로만 남는다.
        assertNull(logEvent("item_shown", "{oops"))
        assertNull(logEvent("item_shown", "[]"))
        assertNull(logEvent("Item_Shown", """{"item_seq":1}"""))
        assertNull(logEvent("item-shown", """{"item_seq":1}"""))
        assertNull(logEvent("firebase_item_shown", """{"item_seq":1}"""))
        assertNull(logEvent("", """{"item_seq":1}"""))
    }

    @Test
    fun `파라미터 없는 이벤트도 그대로 지나간다`() {
        assertEquals("retest_started" to emptyMap<String, EventParam>(), logEvent("retest_started", "{}"))
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
