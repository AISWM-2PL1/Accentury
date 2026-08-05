package com.accentury.app.recording

import com.accentury.app.audio.AudioRecorder
import com.accentury.app.audio.CHUNK_SIZE
import com.accentury.app.audio.PcmSource
import com.accentury.app.audio.QualityStatus
import com.accentury.app.audio.RecordingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModelWith(chunks: Flow<ShortArray>): RecordingViewModel {
        val source = object : PcmSource {
            override fun recordingFlow(): Flow<ShortArray> = chunks
        }
        return RecordingViewModel(RecordingEngine(source))
    }

    private fun chunksOf(count: Int, amplitude: Int = 1000): Flow<ShortArray> = flow {
        repeat(count) {
            emit(ShortArray(CHUNK_SIZE) { i -> if (i % 2 == 0) amplitude.toShort() else (-amplitude).toShort() })
        }
    }

    @Test
    fun `시작 직후 Recording 상태가 되고 완료 시 Review로 전이된다`() = runTest(dispatcher) {
        val vm = viewModelWith(chunksOf(16))

        vm.startRecording()
        assertTrue(vm.uiState.value is RecordingUiState.Recording)

        advanceUntilIdle()
        val review = vm.uiState.value as RecordingUiState.Review
        assertEquals(QualityStatus.NORMAL, review.quality)
        assertEquals(16 * CHUNK_SIZE * 1000L / 16_000, review.durationMs)
        assertTrue(review.canProceed)
    }

    @Test
    fun `1초 미만 발화는 TOO_SHORT 판정으로 다음 진행이 차단된다`() = runTest(dispatcher) {
        val vm = viewModelWith(chunksOf(4))

        vm.startRecording()
        advanceUntilIdle()

        val review = vm.uiState.value as RecordingUiState.Review
        assertEquals(QualityStatus.TOO_SHORT, review.quality)
        assertTrue(!review.canProceed)
    }

    @Test
    fun `재녹음은 새 attemptId를 발급한다`() = runTest(dispatcher) {
        val vm = viewModelWith(chunksOf(16))

        vm.startRecording()
        advanceUntilIdle()
        val first = (vm.uiState.value as RecordingUiState.Review).attemptId

        vm.retryRecording()
        advanceUntilIdle()
        val second = (vm.uiState.value as RecordingUiState.Review).attemptId

        assertNotEquals(first, second)
    }

    @Test
    fun `녹음 중 reset하면 진행 중이던 코루틴이 상태를 덮어쓰지 못한다`() = runTest(dispatcher) {
        val vm = viewModelWith(
            flow {
                repeat(100) {
                    kotlinx.coroutines.delay(100)
                    emit(ShortArray(CHUNK_SIZE) { 1000 })
                }
            },
        )

        vm.startRecording()
        dispatcher.scheduler.advanceTimeBy(350)
        assertTrue(vm.uiState.value is RecordingUiState.Recording)

        vm.reset()
        advanceUntilIdle()

        assertTrue(vm.uiState.value is RecordingUiState.Idle)
        assertEquals(null, vm.consumeRecording())
    }

    @Test
    fun `엔진 실패는 Failed 상태가 된다`() = runTest(dispatcher) {
        val vm = viewModelWith(flow { throw AudioRecorder.CaptureException("녹음 중 권한 회수") })

        vm.startRecording()
        advanceUntilIdle()

        val failed = vm.uiState.value as RecordingUiState.Failed
        assertTrue(failed.reason.contains("권한"))
    }

    @Test
    fun `다음으로 넘어가면 reset으로 Idle에 돌아오고 PCM은 1회만 소비된다`() = runTest(dispatcher) {
        val vm = viewModelWith(chunksOf(16))

        vm.startRecording()
        advanceUntilIdle()

        val pcm = vm.consumeRecording()
        assertTrue(pcm != null && pcm.isNotEmpty())
        assertEquals(null, vm.consumeRecording())

        vm.reset()
        assertTrue(vm.uiState.value is RecordingUiState.Idle)
    }
}
