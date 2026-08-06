package com.accentury.app.upload

import com.accentury.app.audio.ClientQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class UploadManagerTest {

    private class FakeUploadClient : UploadClient {

        val received = mutableListOf<UploadRequest>()

        private val gates = mutableMapOf<String, CompletableDeferred<UploadResult>>()

        private val swallowCancellation = mutableSetOf<String>()

        override suspend fun upload(
            sessionId: String,
            sessionToken: String,
            request: UploadRequest,
        ): UploadResult {
            received += request
            val gate = gates.getOrPut(request.attemptId) { CompletableDeferred() }
            return try {
                gate.await()
            } catch (e: CancellationException) {
                // 취소를 흘리지 않는 클라이언트 구현. 취소된 코루틴이 결과를 들고 publish까지 도달한다.
                if (request.attemptId in swallowCancellation) UploadResult.Accepted("aj_zombie") else throw e
            }
        }

        /** 이 키의 전송은 취소를 삼키고 성공 결과를 들고 돌아온다. 늦은 완료 경합을 결정론적으로 만든다. */
        fun swallowCancellationFor(attemptId: String) {
            swallowCancellation += attemptId
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

    @Test
    fun `discard한 Failed 건은 상태와 원본이 사라지고 같은 키로 다시 enqueue할 수 있다`() = withManager { fake, manager ->
        manager.enqueue(requestOf("at-1"))
        advanceUntilIdle()
        fake.respond("at-1", UploadResult.TransportError("network down"))
        advanceUntilIdle()
        assertEquals(UploadState.Failed(true, "network down"), manager.uploads.value["at-1"])

        manager.discard("at-1")
        assertEquals(null, manager.uploads.value["at-1"])

        // 원본이 풀렸으므로 retry는 보낼 바이트가 없다.
        manager.retry("at-1")
        advanceUntilIdle()
        assertEquals(1, fake.callsFor("at-1"))
        assertEquals(null, manager.uploads.value["at-1"])

        // 폐기는 시도 자체를 버리는 것이라 같은 키의 새 enqueue는 다시 받는다.
        manager.enqueue(requestOf("at-1"))
        advanceUntilIdle()
        assertEquals(2, fake.callsFor("at-1"))
        assertEquals(UploadState.InFlight, manager.uploads.value["at-1"])
    }

    @Test
    fun `discard는 진행 중 전송을 끊고 뒤늦은 응답도 상태를 되살리지 못한다`() = withManager { fake, manager ->
        manager.enqueue(requestOf("at-1"))
        advanceUntilIdle()
        assertEquals(UploadState.InFlight, manager.uploads.value["at-1"])

        manager.discard("at-1")
        advanceUntilIdle()
        assertEquals(null, manager.uploads.value["at-1"])

        fake.respond("at-1", UploadResult.Accepted("aj_late"))
        advanceUntilIdle()

        assertEquals(null, manager.uploads.value["at-1"])
    }

    @Test
    fun `clearAll은 상태가 섞인 여러 건을 전부 지우고 진행 중 건도 되살아나지 않는다`() = withManager { fake, manager ->
        manager.enqueue(requestOf("at-done"))
        manager.enqueue(requestOf("at-failed", itemId = "item-2"))
        manager.enqueue(requestOf("at-inflight", itemId = "item-3"))
        advanceUntilIdle()
        fake.respond("at-done", UploadResult.Accepted("aj_1"))
        fake.respond("at-failed", UploadResult.TransportError("network down"))
        advanceUntilIdle()
        assertEquals(3, manager.uploads.value.size)

        manager.clearAll()
        advanceUntilIdle()
        assertTrue(manager.uploads.value.isEmpty())

        fake.respond("at-inflight", UploadResult.Accepted("aj_late"))
        advanceUntilIdle()

        assertTrue(manager.uploads.value.isEmpty())
    }

    @Test
    fun `clearAll 후 새 enqueue는 정상 동작한다`() = withManager { fake, manager ->
        manager.enqueue(requestOf("at-1"))
        advanceUntilIdle()
        manager.clearAll()
        advanceUntilIdle()

        manager.enqueue(requestOf("at-2", itemId = "item-2"))
        advanceUntilIdle()
        assertEquals(UploadState.InFlight, manager.uploads.value["at-2"])

        fake.respond("at-2", UploadResult.Accepted("aj_2"))
        advanceUntilIdle()

        assertEquals(UploadState.Done("aj_2"), manager.uploads.value["at-2"])
        assertEquals(1, manager.uploads.value.size)
    }

    @Test
    fun `discard 후 다시 enqueue하면 옛 전송의 늦은 완료가 새 시도의 원본을 지우지 못한다`() =
        withManager { fake, manager ->
            fake.swallowCancellationFor("at-1")

            manager.enqueue(requestOf("at-1"))
            advanceUntilIdle()
            assertEquals(1, fake.callsFor("at-1"))

            // 폐기 직후 같은 키로 새 시도를 연다. 옛 코루틴은 아직 취소 재개를 돌리지 않았다.
            manager.discard("at-1")
            manager.enqueue(requestOf("at-1"))
            assertEquals(UploadState.InFlight, manager.uploads.value["at-1"])

            // 여기서 옛 코루틴이 Done을 들고 publish에 도달한다. 새 시도의 Job이 아니므로 버려져야 한다.
            advanceUntilIdle()
            assertEquals(2, fake.callsFor("at-1"))
            assertEquals(UploadState.InFlight, manager.uploads.value["at-1"])

            // 새 시도의 원본이 남아 있어야 재시도가 같은 바이트를 다시 보낼 수 있다.
            fake.respond("at-1", UploadResult.TransportError("network down"))
            advanceUntilIdle()
            assertEquals(UploadState.Failed(true, "network down"), manager.uploads.value["at-1"])

            manager.retry("at-1")
            advanceUntilIdle()
            assertEquals(3, fake.callsFor("at-1"))
            assertEquals(UploadState.InFlight, manager.uploads.value["at-1"])
        }

    /** 응답을 기다리지 않는 클라이언트. 경합 테스트에서 여러 스레드가 동시에 쓰므로 가변 상태를 두지 않는다. */
    private class ImmediateUploadClient : UploadClient {
        override suspend fun upload(
            sessionId: String,
            sessionToken: String,
            request: UploadRequest,
        ): UploadResult = UploadResult.Accepted("aj-${request.attemptId}")
    }

    /**
     * 스레드 경합은 가상 시간으로 재현할 수 없어 실제 디스패처와 실제 스레드를 쓴다.
     * enqueue와 clearAll을 CyclicBarrier로 정면 충돌시키고, 매 회차마다 폐기 불변식을 확인한다:
     * 진행 중 전송이 모두 끝난 뒤 originals에 남은 키는 uploads가 추적하는 키의 부분집합이어야 한다.
     * (enqueue의 "상태 등록 → 원본 보관 → 전송 시작"이 원자적이지 않으면
     *  폐기 이후에 시작된 업로드가 originals에 WAV 바이트를 영구히 남긴다.)
     */
    @Test
    fun `enqueue와 clearAll이 경합해도 폐기된 시도의 원본이 남지 않는다`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val manager = UploadManager(
            ImmediateUploadClient(),
            scope,
            sessionId = "sess-1",
            sessionToken = "token-1",
        )
        try {
            repeat(300) { round ->
                val attemptId = "at-$round"
                val barrier = CyclicBarrier(2)
                val enqueuer = thread {
                    barrier.await()
                    manager.enqueue(requestOf(attemptId))
                }
                val clearer = thread {
                    barrier.await()
                    manager.clearAll()
                }
                enqueuer.join()
                clearer.join()
                awaitNoInFlight(manager, round)

                val tracked = manager.uploads.value.keys
                val retained = manager.retainedOriginalKeys()
                assertTrue(
                    "round=$round 폐기된 원본이 남았다: retained=$retained tracked=$tracked",
                    tracked.containsAll(retained),
                )
                manager.clearAll()
            }
        } finally {
            scope.cancel()
        }
    }

    private fun awaitNoInFlight(manager: UploadManager, round: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (manager.uploads.value.values.any { it is UploadState.InFlight }) {
            if (System.nanoTime() > deadline) fail("round=$round 업로드가 InFlight에서 멈췄다")
            Thread.sleep(1)
        }
    }

    /** originals는 구현 세부라 공개하지 않는다. 폐기 불변식만 확인하려고 매니저의 락을 잡고 들여다본다. */
    private fun UploadManager.retainedOriginalKeys(): Set<String> {
        val lock = readPrivateField("lock")
        @Suppress("UNCHECKED_CAST")
        val originals = readPrivateField("originals") as Map<String, *>
        return synchronized(lock) { originals.keys.toSet() }
    }

    private fun UploadManager.readPrivateField(name: String): Any =
        UploadManager::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(this)!!
}
