package com.accentury.app.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEngineTest {

    private class FakeSource(private val chunks: Flow<ShortArray>) : PcmSource {
        override fun recordingFlow(): Flow<ShortArray> = chunks
    }

    private fun infiniteSource() = FakeSource(
        flow {
            while (true) emit(ShortArray(CHUNK_SIZE) { 1000 })
        },
    )

    @Test
    fun `10초 도달 시 자동 종료되고 정확히 10초로 잘린다`() = runBlocking {
        val engine = RecordingEngine(infiniteSource())

        val outcome = engine.record {}

        assertTrue(outcome is RecordingEngine.Outcome.Success)
        outcome as RecordingEngine.Outcome.Success
        assertTrue(outcome.autoStopped)
        assertEquals(10_000L, outcome.durationMs)
        assertEquals(RecordingEngine.MAX_SAMPLES, outcome.pcm.size)
    }

    @Test
    fun `수동 정지 시 그때까지 캡처된 PCM만 반환한다`() = runBlocking {
        val engine = RecordingEngine(infiniteSource())
        var chunkCount = 0

        val outcome = engine.record {
            chunkCount++
            if (chunkCount == 5) engine.requestStop()
        }

        assertTrue(outcome is RecordingEngine.Outcome.Success)
        outcome as RecordingEngine.Outcome.Success
        assertFalse(outcome.autoStopped)
        assertEquals(5 * CHUNK_SIZE, outcome.pcm.size)
        assertEquals(5 * CHUNK_SIZE * 1000L / SAMPLE_RATE, outcome.durationMs)
    }

    @Test
    fun `진행 리포트의 경과 시간이 샘플 수 기준으로 계산된다`() = runBlocking {
        val engine = RecordingEngine(infiniteSource())
        val elapsed = mutableListOf<Long>()

        engine.record {
            elapsed += it.elapsedMs
            if (elapsed.size == 3) engine.requestStop()
        }

        assertEquals(
            listOf(
                CHUNK_SIZE * 1000L / SAMPLE_RATE,
                2 * CHUNK_SIZE * 1000L / SAMPLE_RATE,
                3 * CHUNK_SIZE * 1000L / SAMPLE_RATE,
            ),
            elapsed,
        )
    }

    @Test
    fun `캡처 예외는 Failure로 변환된다`() = runBlocking {
        val failing = FakeSource(
            flow { throw AudioRecorder.CaptureException("녹음 중 권한 회수") },
        )
        val engine = RecordingEngine(failing)

        val outcome = engine.record {}

        assertTrue(outcome is RecordingEngine.Outcome.Failure)
        outcome as RecordingEngine.Outcome.Failure
        assertTrue(outcome.reason.contains("권한"))
    }
}
