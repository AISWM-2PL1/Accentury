package com.accentury.app.upload

import androidx.lifecycle.ViewModelStore
import com.accentury.app.audio.ClientQuality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UploadViewModelTest {

    /** 응답하지 않는 클라이언트. 업로드는 InFlight에 머물러 폐기 시점을 테스트가 정한다. */
    private class NeverRespondingClient : UploadClient {

        val received = mutableListOf<UploadRequest>()

        private val never = CompletableDeferred<UploadResult>()

        override suspend fun upload(
            sessionId: String,
            sessionToken: String,
            request: UploadRequest,
        ): UploadResult {
            received += request
            return never.await()
        }
    }

    private fun requestOf(attemptId: String, itemId: String = "item_1") = UploadRequest(
        attemptId = attemptId,
        itemId = itemId,
        wavBytes = ByteArray(16) { it.toByte() },
        durationMs = 2_000L,
        clientQuality = ClientQuality(rms = 0.11, peak = 0.83, silenceRatio = 0.12, clipped = false),
    )

    /** UploadManagerTest와 같은 이유로 스코프를 주입한다 — runTest 본문의 자식이면 폐기를 볼 수 없다. */
    private fun withViewModel(
        body: suspend TestScope.(UploadViewModel, CoroutineScope) -> Unit,
    ) = runTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            body(
                UploadViewModel(
                    client = NeverRespondingClient(),
                    sessionId = "sess-1",
                    sessionToken = "token-1",
                    scope = scope,
                ),
                scope,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `업로드를 걸면 라벨을 함께 기억한다`() = withViewModel { vm, _ ->
        vm.enqueue(requestOf("at_1"), label = "1번 문항")
        advanceUntilIdle()

        assertEquals(UploadState.InFlight, vm.uploads.value["at_1"])
        assertEquals("1번 문항", vm.labelOf("at_1"))
    }

    @Test
    fun `모르는 시도에는 기본 라벨을 준다 - 상태 바가 빈 칸을 그리지 않게`() = withViewModel { vm, _ ->
        assertEquals("문항", vm.labelOf("at_unknown"))
    }

    /*
     * 한 건만 버리는 경로 (KAN-147). 재녹음 전환이 확정된 업로드와, 같은 문항의 새 녹음에
     * 밀려난 앞 시도가 여기로 온다 - 둘 다 결과가 나올 일이 없어진 시도다.
     */
    @Test
    fun `discard는 업로드와 라벨을 함께 지운다`() = withViewModel { vm, _ ->
        vm.enqueue(requestOf("at_1"), label = "1번 문항")
        vm.enqueue(requestOf("at_2", itemId = "item_2"), label = "2번 문항")
        advanceUntilIdle()

        vm.discard("at_1")
        advanceUntilIdle()

        assertEquals(null, vm.uploads.value["at_1"])
        assertEquals("문항", vm.labelOf("at_1"))
        // 남은 건은 그대로다 - 폐기는 지목한 시도 하나만 버린다.
        assertEquals(UploadState.InFlight, vm.uploads.value["at_2"])
        assertEquals("2번 문항", vm.labelOf("at_2"))
    }

    @Test
    fun `전체 폐기는 업로드와 라벨을 함께 지운다 - onCleared가 부르는 경로`() = withViewModel { vm, _ ->
        vm.enqueue(requestOf("at_1"), label = "1번 문항")
        advanceUntilIdle()

        vm.clearAll()
        advanceUntilIdle()

        assertTrue(vm.uploads.value.isEmpty())
        assertEquals("문항", vm.labelOf("at_1"))
    }

    @Test
    fun `뷰모델이 정리될 때 남은 업로드를 폐기하고 스코프를 내린다 - 회전이 아닌 진짜 종료`() =
        withViewModel { vm, scope ->
            vm.enqueue(requestOf("at_1"), label = "1번 문항")
            advanceUntilIdle()
            assertEquals(UploadState.InFlight, vm.uploads.value["at_1"])

            // Activity가 완전히 끝날 때 ViewModelStore가 하는 일(onCleared)을 그대로 흉내 낸다.
            ViewModelStore().apply { put("upload", vm) }.clear()
            advanceUntilIdle()

            assertTrue(vm.uploads.value.isEmpty())
            assertTrue(!scope.isActive)
        }
}
