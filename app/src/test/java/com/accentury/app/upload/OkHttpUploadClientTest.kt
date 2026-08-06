package com.accentury.app.upload

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun `202 응답이면 analysisJobId를 파싱해 Accepted를 반환한다`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody("""{"analysisJobId":"aj_123"}"""),
        )

        val result = client().upload("sess-1", "token-1", request)

        assertEquals(UploadResult.Accepted("aj_123"), result)
    }

    @Test
    fun `요청에 4개 파트와 인증 헤더가 실리고 idempotencyKey는 attemptId와 같다`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody("""{"analysisJobId":"aj_123"}"""),
        )

        client().upload("sess-1", "token-1", request)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v0/sessions/sess-1/audio", recorded.path)
        assertEquals("Bearer token-1", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("X-Correlation-Id")!!.isNotBlank())
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("multipart/form-data"))

        val body = recorded.body.readUtf8()
        assertTrue(body.contains("""name="audio"; filename="recording.wav""""))
        assertTrue(body.contains("audio/wav"))
        assertTrue(body.contains("""name="itemId""""))
        assertTrue(body.contains("item-42"))
        assertTrue(body.contains("""name="idempotencyKey""""))
        assertTrue(body.contains("attempt-abc"))
        assertTrue(body.contains("""name="recordedDurationMs""""))
        assertTrue(body.contains("3210"))
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

    @Test
    fun `서버 연결이 끊기면 TransportError를 반환한다`() = runTest {
        val client = client()
        server.shutdown()

        val result = client.upload("sess-1", "token-1", request)

        assertTrue(result is UploadResult.TransportError)
        assertTrue((result as UploadResult.TransportError).reason.isNotBlank())
    }
}
