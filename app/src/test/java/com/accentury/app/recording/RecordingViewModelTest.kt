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

    /** 청크 사이에 시간을 두어 진행 중 상태를 중간에 들여다볼 수 있게 한다 */
    private fun delayedChunks(count: Int, amplitude: Int = 1000): Flow<ShortArray> = flow {
        repeat(count) {
            kotlinx.coroutines.delay(100)
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
    fun `Recording 상태의 pitchFrames는 청크를 넘기며 누적된다`() = runTest(dispatcher) {
        val vm = viewModelWith(delayedChunks(4))

        vm.startRecording()
        assertEquals(emptyList<RecordingEngine.PitchFrame>(), (vm.uiState.value as RecordingUiState.Recording).pitchFrames)

        dispatcher.scheduler.advanceTimeBy(150)
        val first = (vm.uiState.value as RecordingUiState.Recording).pitchFrames
        assertTrue("첫 청크에서 프레임이 나와야 한다", first.isNotEmpty())

        dispatcher.scheduler.advanceTimeBy(100)
        val second = (vm.uiState.value as RecordingUiState.Recording).pitchFrames
        assertTrue("프레임이 늘어야 한다: ${first.size} -> ${second.size}", second.size > first.size)
        // 앞 청크의 프레임이 그대로 앞에 남아 있다 - 곡선이 매번 다시 그려져도 과거가 안 잘린다
        assertEquals(first, second.take(first.size))

        vm.reset()
        advanceUntilIdle()
    }

    @Test
    fun `Review 상태에 녹음 전체의 pitchFrames가 남는다`() = runTest(dispatcher) {
        val vm = viewModelWith(delayedChunks(4))

        vm.startRecording()
        advanceUntilIdle()

        val review = vm.uiState.value as RecordingUiState.Review
        // 4청크 x 2048샘플이면 프레이머가 (8192 - 2048) / 512 + 1 = 13개 창을 완성한다
        assertEquals(13, review.pitchFrames.size)
        // 녹음 중 마지막으로 방출된 누적과 같은 내용이어야 한다 - 완료 직전 곡선이 그대로 남는다.
        // 첫 창(0..2047)의 시각은 그 중앙인 1024샘플 = 64ms다.
        assertEquals(64L, review.pitchFrames.first().timestampMs)
    }

    @Test
    fun `두 번째 녹음은 빈 누적으로 시작한다`() = runTest(dispatcher) {
        val vm = viewModelWith(delayedChunks(4))

        vm.startRecording()
        dispatcher.scheduler.advanceTimeBy(150)
        val afterFirstChunk = (vm.uiState.value as RecordingUiState.Recording).pitchFrames.size
        advanceUntilIdle()
        assertTrue(vm.uiState.value is RecordingUiState.Review)

        vm.retryRecording()
        dispatcher.scheduler.advanceTimeBy(150)
        val frames = (vm.uiState.value as RecordingUiState.Recording).pitchFrames
        assertEquals(afterFirstChunk, frames.size)
        // 시각이 첫 창의 중앙(64ms)으로 되돌아왔다 = 이전 녹음의 프레이머 상태가 안 남았다.
        assertEquals(64L, frames.first().timestampMs)

        vm.reset()
        advanceUntilIdle()
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
