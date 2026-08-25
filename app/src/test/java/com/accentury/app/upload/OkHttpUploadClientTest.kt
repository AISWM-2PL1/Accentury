package com.accentury.app.upload

import com.accentury.app.audio.ClientQuality
import com.accentury.app.net.TransportFailure
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpUploadClientTest {

    private lateinit var server: MockWebServer

    private val request = UploadRequest(
        attemptId = "attempt-abc",
        itemId = "item-42",
        wavBytes = ByteArray(64) { it.toByte() },
        durationMs = 3_210L,
        clientQuality = ClientQuality(rms = 0.11, peak = 0.83, silenceRatio = 0.12, clipped = false),
    )

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

    private fun client() = OkHttpUploadClient(server.url("/").toString())

    /** multipart 본문에서 meta 파트의 JSON만 뽑아 파싱한다. */
    private fun metaPartOf(body: String): JsonObject {
        val part = body.split("\r\n--").first { it.contains("""name="meta"""") }
        val payload = part.substring(part.indexOf('{'), part.lastIndexOf('}') + 1)
        return Json.parseToJsonElement(payload).jsonObject
    }

    @Test
    fun `202 응답이면 analysisJobId를 파싱해 Accepted를 반환한다`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody("""{"analysisJobId":"aj_123"}"""),
        )

        val result = client().upload("sess-1", "token-1", request)

        assertEquals(UploadResult.Accepted("aj_123"), result)
    }

    @Test
    fun `itemId는 경로에 실리고 Idempotency-Key 헤더는 attemptId와 같다`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody("""{"analysisJobId":"aj_123"}"""),
        )

        client().upload("sess-1", "token-1", request)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v0/sessions/sess-1/voice-items/item-42/recording", recorded.path)
        assertEquals("Bearer token-1", recorded.getHeader("Authorization"))
        assertEquals("attempt-abc", recorded.getHeader("Idempotency-Key"))
        assertTrue(recorded.getHeader("X-Correlation-Id")!!.isNotBlank())
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
    }

    @Test
    fun `본문은 audio와 meta 두 파트뿐이고 평면 파트는 없다`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody("""{"analysisJobId":"aj_123"}"""),
        )

        client().upload("sess-1", "token-1", request)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("""name="audio"; filename="recording.wav""""))
        assertTrue(body.contains("audio/wav"))
        assertTrue(body.contains("""name="meta""""))
        assertTrue(body.contains("application/json"))
        assertEquals(2, Regex("Content-Disposition").findAll(body).count())

        // KAN-88 티켓이 잘못 지시했던 평면 파트들. 정본에는 없어야 한다.
        assertFalse(body.contains("""name="itemId""""))
        assertFalse(body.contains("""name="idempotencyKey""""))
        assertFalse(body.contains("""name="recordedDurationMs""""))
    }

    @Test
    fun `meta 파트에 durationMs와 clientQuality 4필드가 실린다`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody("""{"analysisJobId":"aj_123"}"""),
        )

        client().upload("sess-1", "token-1", request)

        val meta = metaPartOf(server.takeRequest().body.readUtf8())
        assertEquals(3_210L, meta["durationMs"]!!.jsonPrimitive.long)

        val quality = meta["clientQuality"]!!.jsonObject
        assertEquals(0.11, quality["rms"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(0.83, quality["peak"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(0.12, quality["silenceRatio"]!!.jsonPrimitive.double, 1e-9)
        assertFalse(quality["clipped"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `오류 봉투를 그대로 Rejected 필드에 매핑한다`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(413).setBody(
                """{"code":"AUDIO_TOO_LARGE","message":"파일이 너무 큽니다","retryable":false,""" +
                    """"retryAfterMs":null,"correlationId":"corr-1"}""",
            ),
        )

        val result = client().upload("sess-1", "token-1", request)

        assertEquals(
            UploadResult.Rejected(
                code = "AUDIO_TOO_LARGE",
                message = "파일이 너무 큽니다",
                retryable = false,
                retryAfterMs = null,
            ),
            result,
        )
    }

    @Test
    fun `봉투 없는 500은 상태 코드 기준으로 재시도 가능한 Rejected가 된다`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>Bad Gateway</html>"))

        val result = client().upload("sess-1", "token-1", request)

        assertTrue(result is UploadResult.Rejected)
        result as UploadResult.Rejected
        assertNull(result.code)
        assertTrue(result.retryable)
        assertTrue(result.message!!.contains("500"))
    }

    @Test
    fun `봉투 없는 400은 재시도 불가로 판단한다`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("not json"))

        val result = client().upload("sess-1", "token-1", request)

        assertTrue(result is UploadResult.Rejected)
        assertEquals(false, (result as UploadResult.Rejected).retryable)
    }

    @Test
    fun `202인데 본문이 깨졌으면 재시도 가능한 Rejected로 방어한다`() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))

        val result = client().upload("sess-1", "token-1", request)

        assertTrue(result is UploadResult.Rejected)
        assertTrue((result as UploadResult.Rejected).retryable)
    }

    /*
     * 실제 예외가 무엇으로 올라오는지까지 확인한다 (KAN-147 2단계). 분류표만 단위 테스트하면
     * OkHttp가 다른 예외를 던지는 순간 사용자는 엉뚱한 안내를 받는데 아무도 모른다.
     */
    @Test
    fun `서버 연결이 끊기면 서버 쪽 문제로 분류한 TransportError를 반환한다`() = runTest {
        val client = client()
        server.shutdown()

        val result = client.upload("sess-1", "token-1", request)

        assertTrue(result is UploadResult.TransportError)
        result as UploadResult.TransportError
        assertEquals(TransportFailure.ServerUnreachable, result.failure)
        assertTrue(result.reason.isNotBlank())
    }
}
