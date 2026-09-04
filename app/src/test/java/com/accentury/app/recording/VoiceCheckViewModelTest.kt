package com.accentury.app.recording

import com.accentury.app.audio.PcmSource
import com.accentury.app.audio.READ_CHUNK_SIZE
import com.accentury.app.audio.RecordingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCheckViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 마이크를 늦게 놓는 가짜 소스. 실제 [com.accentury.app.audio.AudioRecorder]의 release가
     * flow의 finally에서 IO로 일어나는 것을 흉내낸다 — 취소가 돌아온 뒤에도 한동안 마이크를
     * 쥐고 있는 구간이다. 열림/닫힘을 [events]에 순서대로 남겨 겹침을 단언할 수 있게 한다.
     */
    private class SlowReleaseSource(private val events: MutableList<String>) : PcmSource {
        private var opened = 0

        override fun recordingFlow(): Flow<ShortArray> {
            val id = ++opened
            return flow {
                events += "open$id"
                try {
                    while (true) {
                        delay(CHUNK_INTERVAL_MS)
                        emit(ShortArray(READ_CHUNK_SIZE))
                    }
                } finally {
                    // 취소된 뒤라 그냥 delay하면 즉시 튕긴다. 실제 release도 취소와 무관하게
                    // 끝까지 수행되므로 NonCancellable이 맞는 흉내다.
                    withContext(NonCancellable) { delay(RELEASE_MS) }
                    events += "release$id"
                }
            }
        }
    }

    private fun viewModelWith(source: PcmSource) = VoiceCheckViewModel(RecordingEngine(source))

    @Test
    fun `stop 직후 start는 이전 캡처가 마이크를 놓을 때까지 기다렸다가 연다`() = runTest(dispatcher) {
        val events = mutableListOf<String>()
        val vm = viewModelWith(SlowReleaseSource(events))

        vm.start()
        dispatcher.scheduler.advanceTimeBy(150)
        assertEquals(listOf("open1"), events)

        // 회전: 화면이 빠지며 stop, 새 화면이 곧바로 start
        vm.stop()
        vm.start()
        dispatcher.scheduler.advanceTimeBy(RELEASE_MS + 50)

        // release1이 open2보다 **앞**에 있어야 한다. 뒤집히면 두 AudioRecord가 겹쳐 열려
        // "마이크 점유 중"으로 초기화가 실패한다.
        assertEquals(listOf("open1", "release1", "open2"), events)

        vm.stop()
        advanceUntilIdle()
    }

    @Test
    fun `stop 뒤 start하면 다시 Listening으로 들어가 프레임을 새로 쌓는다`() = runTest(dispatcher) {
        val events = mutableListOf<String>()
        val vm = viewModelWith(SlowReleaseSource(events))

        vm.start()
        dispatcher.scheduler.advanceTimeBy(150)

        vm.stop()
        vm.start()
        assertTrue(vm.state.value is VoiceCheckState.Listening)

        dispatcher.scheduler.advanceTimeBy(RELEASE_MS + 500)
        assertTrue("두 번째 캡처가 열려야 한다: $events", events.contains("open2"))
        val listening = vm.state.value as VoiceCheckState.Listening
        // 새 캡처의 프레임이다 - 시각이 0부터 다시 시작한다.
        assertTrue("프레임이 쌓여야 한다", listening.frames.isNotEmpty())
        assertEquals(64L, listening.frames.first().timestampMs)

        vm.stop()
        advanceUntilIdle()
    }

    private companion object {
        const val CHUNK_INTERVAL_MS = 32L
        const val RELEASE_MS = 200L
    }
}
