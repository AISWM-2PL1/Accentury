package com.accentury.app.session

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpSessionClientTest {

    private lateinit var server: MockWebServer

    /** 계약대로 5필드를 모두 담은 201 본문 (§3.1). */
    private val createdBody = """
        {"sessionId":"s_abc","sessionToken":"st_xyz","testVersion":"gn-2026.08.1",
         "scoreVersion":"sv-1","expiresAt":"2026-08-24T10:30:00Z"}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        // 전송 실패 테스트는 본문에서 이미 shutdown 했다.
        runCatching { server.shutdown() }
    }

    private fun client() = OkHttpSessionClient(server.url("/").toString())

    @Test
    fun `201 응답의 5필드를 그대로 Session으로 담는다`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(createdBody))

        val result = client().create(appVersion = "1.0")

        assertEquals(
            SessionResult.Created(
                Session(
                    sessionId = "s_abc",
                    sessionToken = "st_xyz",
                    testVersion = "gn-2026.08.1",
                    scoreVersion = "sv-1",
                    expiresAt = "2026-08-24T10:30:00Z",
                ),
            ),
            result,
        )
    }

    @Test
    fun `POST v0 sessions로 나가고 바디에 platform과 appVersion이 실린다`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(createdBody))

        client().create(appVersion = "1.2.3")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v0/sessions", recorded.path)
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
        assertTrue(recorded.getHeader("X-Correlation-Id")!!.isNotBlank())

        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val client = body["client"]!!.jsonObject
        assertEquals("ANDROID", client["platform"]!!.jsonPrimitive.content)
        assertEquals("1.2.3", client["appVersion"]!!.jsonPrimitive.content)
        // 앱 최초 응시에는 유입 코드가 없다 — 없는 값을 빈 문자열로 만들어 보내지 않는다.
        assertNull(body["campaignToken"])
    }

    @Test
    fun `최초 응시에는 Authorization 헤더를 붙이지 않는다`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(createdBody))

        client().create(appVersion = "1.0")

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `이전 토큰을 주면 Bearer로 실어 보낸다 - 재응시 폐기 경로 (KAN-107)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(createdBody))

        client().create(appVersion = "1.0", previousToken = "st_old")

        assertEquals("Bearer st_old", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `429 봉투의 retryAfterMs를 결과에 싣는다`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "3")
                .setBody(
                    """{"code":"RATE_LIMITED","message":"요청이 너무 많습니다.","retryable":true,""" +
                        """"retryAfterMs":2100,"correlationId":"corr-1"}""",
                ),
        )

        val result = client().create(appVersion = "1.0")

        assertEquals(
            SessionResult.Rejected(
                code = "RATE_LIMITED",
                message = "요청이 너무 많습니다.",
                retryable = true,
                retryAfterMs = 2_100L,
            ),
            result,
        )
    }

    @Test
    fun `봉투를 못 읽는 429는 Retry-After 헤더를 밀리초로 환산해 쓴다`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "5").setBody("<html>nope</html>"),
        )

        val result = client().create(appVersion = "1.0")

        assertTrue(result is SessionResult.Rejected)
        result as SessionResult.Rejected
        assertNull(result.code)
        assertTrue(result.retryable)
        assertEquals(5_000L, result.retryAfterMs)
    }

    @Test
    fun `HTTP-date 꼴 Retry-After는 숫자로 읽히지 않아 대기 시간 없이 남는다`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT")
                .setBody("nope"),
        )

        val result = client().create(appVersion = "1.0")

        assertNull((result as SessionResult.Rejected).retryAfterMs)
    }

    @Test
    fun `400 VALIDATION_FAILED는 재시도 불가 거절로 온다`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"code":"VALIDATION_FAILED","message":"영숫자와 ._- 조합 최대 64자만 허용됩니다",""" +
                    """"retryable":false,"correlationId":"corr-2"}""",
            ),
        )

        val result = client().create(appVersion = "1.0")

        assertTrue(result is SessionResult.Rejected)
        result as SessionResult.Rejected
        assertEquals("VALIDATION_FAILED", result.code)
        assertEquals(false, result.retryable)
    }

    @Test
    fun `봉투 없는 500은 상태 코드 기준으로 재시도 가능한 거절이 된다`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>Bad Gateway</html>"))

        val result = client().create(appVersion = "1.0")

        assertTrue(result is SessionResult.Rejected)
        result as SessionResult.Rejected
        assertTrue(result.retryable)
        assertTrue(result.message!!.contains("500"))
    }

    @Test
    fun `2xx인데 필드가 빠졌으면 재시도 가능한 거절로 방어한다`() = runTest {
        // sessionToken은 이 응답에서 한 번만 오는 값이라 없으면 세션 자체를 쓸 수 없다.
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"sessionId":"s_abc","testVersion":"gn-2026.08.1","scoreVersion":"sv-1","expiresAt":"z"}"""),
        )

        val result = client().create(appVersion = "1.0")

        assertTrue(result is SessionResult.Rejected)
        assertTrue((result as SessionResult.Rejected).retryable)
    }

    @Test
    fun `2xx인데 sessionId가 빈 문자열이면 받아들이지 않는다`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"sessionId":"","sessionToken":"st_xyz","testVersion":"v","scoreVersion":"s","expiresAt":"z"}""",
            ),
        )

        val result = client().create(appVersion = "1.0")

        assertTrue(result is SessionResult.Rejected)
    }

    @Test
    fun `서버 연결이 끊기면 TransportError를 반환한다`() = runTest {
        val client = client()
        server.shutdown()

        val result = client.create(appVersion = "1.0")

        assertTrue(result is SessionResult.TransportError)
        assertTrue((result as SessionResult.TransportError).reason.isNotBlank())
    }
}
