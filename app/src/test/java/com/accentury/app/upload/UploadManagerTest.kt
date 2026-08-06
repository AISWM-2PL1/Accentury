package com.accentury.app.upload

import com.accentury.app.audio.ClientQuality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UploadManagerTest {

    private class FakeUploadClient : UploadClient {

        val received = mutableListOf<UploadRequest>()

        private val gates = mutableMapOf<String, CompletableDeferred<UploadResult>>()

        override suspend fun upload(
            sessionId: String,
            sessionToken: String,
            request: UploadRequest,
        ): UploadResult {
            received += request
            return gates.getOrPut(request.attemptId) { CompletableDeferred() }.await()
        }

        /** 테스트가 응답 시점을 정한다. 응답 뒤 게이트를 비워 재시도는 새로 대기하게 한다. */
        fun respond(attemptId: String, result: UploadResult) {
            gates.getOrPut(attemptId) { CompletableDeferred() }.complete(result)
            gates.remove(attemptId)
        }

        /** UploadResult 대신 예외를 흘리는 클라이언트 구현을 흉내 낸다. */
        fun failWith(attemptId: String, error: Throwable) {
            gates.getOrPut(attemptId) { CompletableDeferred() }.completeExceptionally(error)
            gates.remove(attemptId)
        }

        fun callsFor(attemptId: String): Int = received.count { it.attemptId == attemptId }
    }

    private fun requestOf(attemptId: String, itemId: String = "item-1") = UploadRequest(
        attemptId = attemptId,
        itemId = itemId,
        wavBytes = ByteArray(32) { (it + attemptId.length).toByte() },
        durationMs = 2_000L,
        clientQuality = ClientQuality(rms = 0.11, peak = 0.83, silenceRatio = 0.12, clipped = false),
    )

    /**
     * backgroundScope의 코루틴은 advanceUntilIdle이 돌려주지 않으므로,
     * 테스트 스케줄러를 공유하되 runTest 본문의 자식이 아닌 스코프를 주입한다.
     */
    private fun withManager(body: suspend TestScope.(FakeUploadClient, UploadManager) -> Unit) = runTest {
        val fake = FakeUploadClient()
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            body(fake, UploadManager(fake, scope, sessionId = "sess-1", sessionToken = "token-1"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `enqueue 직후 InFlight이고 응답이 오면 Done으로 전이된다`() = withManager { fake, manager ->
        manager.enqueue(requestOf("at-1"))
        assertEquals(UploadState.InFlight, manager.uploads.value["at-1"])

        advanceUntilIdle()
        fake.respond("at-1", UploadResult.Accepted("aj_1"))
        advanceUntilIdle()

        assertEquals(UploadState.Done("aj_1"), manager.uploads.value["at-1"])
    }

    @Test
    fun `첫 업로드가 끝나기 전에 다음 업로드를 넣어도 둘 다 진행된다`() = withManager { fake, manager ->
        manager.enqueue(requestOf("at-1"))
        advanceUntilIdle()
        manager.enqueue(requestOf("at-2", itemId = "item-2"))
        advanceUntilIdle()

        assertEquals(UploadState.InFlight, manager.uploads.value["at-1"])
        assertEquals(UploadState.InFlight, manager.uploads.value["at-2"])
        assertEquals(2, fake.received.size)

        fake.respond("at-2", UploadResult.Accepted("aj_2"))
        advanceUntilIdle()

        assertEquals(UploadState.Done("aj_2"), manager.uploads.value["at-2"])
        assertEquals(UploadState.InFlight, manager.uploads.value["at-1"])
    }

    @Test
    fun `재시도 불가 Rejected는 Failed로 남고 retry는 무시된다`() = withManager { fake, manager ->
        manager.enqueue(requestOf("at-1"))
        advanceUntilIdle()
        fake.respond(
            "at-1",
            UploadResult.Rejected("AUDIO_TOO_LARGE", "파일이 너무 큽니다", retryable = false, retryAfterMs = null),
        )
        advanceUntilIdle()

        assertEquals(UploadState.Failed(false, "파일이 너무 큽니다"), manager.uploads.value["at-1"])

        manager.retry("at-1")
        advanceUntilIdle()

        assertEquals(UploadState.Failed(false, "파일이 너무 큽니다"), manager.uploads.value["at-1"])
        assertEquals(1, fake.callsFor("at-1"))
    }

    @Test
    fun `TransportError는 재시도 가능한 Failed가 된다`() = withManager { fake, manager ->
        manager.enqueue(requestOf("at-1"))
        advanceUntilIdle()
        fake.respond("at-1", UploadResult.TransportError("timeout"))
        advanceUntilIdle()

        assertEquals(UploadState.Failed(true, "timeout"), manager.uploads.value["at-1"])
    }

    @Test
    fun `retry는 같은 멱등 키와 같은 바이트로 재전송한다`() = withManager { fake, manager ->
        val original = requestOf("at-1")

        manager.enqueue(original)
        advanceUntilIdle()
        fake.respond("at-1", UploadResult.TransportError("network down"))
        advanceUntilIdle()

        manager.retry("at-1")
        assertEquals(UploadState.InFlight, manager.uploads.value["at-1"])

        advanceUntilIdle()
        assertEquals(2, fake.callsFor("at-1"))
        val resent = fake.received.last()
        assertEquals(original.attemptId, resent.attemptId)
        assertTrue(original.wavBytes.contentEquals(resent.wavBytes))

        fake.respond("at-1", UploadResult.Accepted("aj_retry"))
        advanceUntilIdle()

        assertEquals(UploadState.Done("aj_retry"), manager.uploads.value["at-1"])
    }

    @Test
    fun `같은 attemptId로 다시 enqueue해도 이중 업로드하지 않는다`() = withManager { fake, manager ->
        manager.enqueue(requestOf("at-1"))
        manager.enqueue(requestOf("at-1"))
        advanceUntilIdle()
        assertEquals(1, fake.callsFor("at-1"))

        fake.respond("at-1", UploadResult.Accepted("aj_1"))
        advanceUntilIdle()

        manager.enqueue(requestOf("at-1"))
        advanceUntilIdle()

        assertEquals(1, fake.callsFor("at-1"))
        assertEquals(UploadState.Done("aj_1"), manager.uploads.value["at-1"])
    }

    @Test
    fun `실패한 키에 다른 바이트로 enqueue해도 무시되고 retry는 원본 바이트를 보낸다`() = withManager { fake, manager ->
        val original = requestOf("at-1")
        val originalBytes = original.wavBytes.copyOf()

        manager.enqueue(original)
        advanceUntilIdle()
        fake.respond("at-1", UploadResult.TransportError("network down"))
        advanceUntilIdle()
        assertEquals(UploadState.Failed(true, "network down"), manager.uploads.value["at-1"])

        // 같은 멱등 키에 다른 payload를 붙이려는 시도는 상태도 호출 횟수도 건드리지 못한다.
        manager.enqueue(original.copy(wavBytes = ByteArray(32) { 0x7F }))
        advanceUntilIdle()
        assertEquals(1, fake.callsFor("at-1"))
        assertEquals(UploadState.Failed(true, "network down"), manager.uploads.value["at-1"])

        manager.retry("at-1")
        advanceUntilIdle()

        assertEquals(2, fake.callsFor("at-1"))
        assertTrue(originalBytes.contentEquals(fake.received.last().wavBytes))
    }

    @Test
    fun `enqueue 후 호출자가 배열을 바꿔도 재전송 바이트는 스냅샷 그대로다`() = withManager { fake, manager ->
        val original = requestOf("at-1")
        val snapshot = original.wavBytes.copyOf()

        manager.enqueue(original)
        advanceUntilIdle()
        original.wavBytes.fill(0x7F) // 호출자가 버퍼를 재사용하는 상황

        assertTrue(snapshot.contentEquals(fake.received.first().wavBytes))

        fake.respond("at-1", UploadResult.TransportError("network down"))
        advanceUntilIdle()
        manager.retry("at-1")
        advanceUntilIdle()

        assertEquals(2, fake.callsFor("at-1"))
        assertTrue(snapshot.contentEquals(fake.received.last().wavBytes))
    }

    @Test
    fun `클라이언트가 예외를 던지면 InFlight로 남지 않고 재시도 가능한 Failed가 된다`() = withManager { fake, manager ->
        manager.enqueue(requestOf("at-1"))
        advanceUntilIdle()
        assertEquals(UploadState.InFlight, manager.uploads.value["at-1"])

        fake.failWith("at-1", RuntimeException("unexpected boom"))
        advanceUntilIdle()

        assertEquals(UploadState.Failed(true, "unexpected boom"), manager.uploads.value["at-1"])
    }

    @Test
    fun `완료된 업로드와 모르는 키에 대한 retry는 아무 일도 하지 않는다`() = withManager { fake, manager ->
        manager.enqueue(requestOf("at-1"))
        advanceUntilIdle()
        fake.respond("at-1", UploadResult.Accepted("aj_1"))
        advanceUntilIdle()

        manager.retry("at-1")
        manager.retry("at-unknown")
        advanceUntilIdle()

        assertEquals(1, fake.received.size)
        assertEquals(UploadState.Done("aj_1"), manager.uploads.value["at-1"])
        assertEquals(null, manager.uploads.value["at-unknown"])
    }
}
