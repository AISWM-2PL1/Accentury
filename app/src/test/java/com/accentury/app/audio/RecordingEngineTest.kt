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
    fun `녹음 시작 전의 정지 요청은 새 녹음에 영향을 주지 않는다`() = runBlocking {
        val finite = FakeSource(
            flow { repeat(3) { emit(ShortArray(CHUNK_SIZE)) } },
        )
        val engine = RecordingEngine(finite)
        engine.requestStop()

        val outcome = engine.record {}

        assertTrue(outcome is RecordingEngine.Outcome.Success)
        outcome as RecordingEngine.Outcome.Success
        assertFalse(outcome.autoStopped)
        assertEquals(3 * CHUNK_SIZE, outcome.pcm.size)
    }

    @Test
    fun `진행 리포트 경과 시간이 10초를 넘지 않는다`() = runBlocking {
        val engine = RecordingEngine(infiniteSource())
        var maxElapsed = 0L

        engine.record { maxElapsed = maxOf(maxElapsed, it.elapsedMs) }

        assertEquals(RecordingEngine.MAX_DURATION_MS, maxElapsed)
    }

    /** 청크 경계에서도 위상이 이어지는 220Hz 사인. 창이 경계를 걸쳐도 파형이 온전하다. */
    private fun sine220Source(chunkSize: Int, chunkCount: Int): FakeSource {
        val sample = { i: Int ->
            (8000 * kotlin.math.sin(2 * Math.PI * 220.0 * i / SAMPLE_RATE)).toInt().toShort()
        }
        return FakeSource(
            flow {
                repeat(chunkCount) { c ->
                    emit(ShortArray(chunkSize) { sample(c * chunkSize + it) })
                }
            },
        )
    }

    @Test
    fun `진행 리포트에 겹침 프레임별 F0 추정값이 실린다`() = runBlocking {
        val engine = RecordingEngine(sine220Source(chunkSize = CHUNK_SIZE, chunkCount = 3))
        val reports = mutableListOf<List<RecordingEngine.PitchFrame>>()

        engine.record { reports += it.pitchFrames }

        assertEquals(3, reports.size)
        // 첫 청크는 창을 막 채워 1개, 이후에는 hop(512) 기준으로 청크당 4개가 나온다.
        assertEquals(1, reports[0].size)
        assertEquals(4, reports[1].size)
        reports.flatten().forEach { frame ->
            assertTrue(frame.pitchHz != null && kotlin.math.abs(frame.pitchHz!! - 220f) < 3f)
        }
    }

    @Test
    fun `연속 프레임 간격이 32ms로 유지된다 - NFR-PF-02`() = runBlocking {
        val engine = RecordingEngine(sine220Source(chunkSize = CHUNK_SIZE, chunkCount = 3))
        val timestamps = mutableListOf<Long>()

        engine.record { progress -> progress.pitchFrames.forEach { timestamps += it.timestampMs } }

        assertTrue(timestamps.size >= 5)
        // 시각은 창 중앙이다 - 첫 창(0..2047)의 중앙은 1024샘플 = 64ms.
        assertEquals(CHUNK_SIZE / 2 * 1000L / SAMPLE_RATE, timestamps.first())
        assertEquals(64L, timestamps.first())
        timestamps.zipWithNext().forEach { (prev, next) -> assertEquals(32L, next - prev) }
    }

    @Test
    fun `hop보다 짧은 청크가 이어져도 F0 프레임이 나온다`() = runBlocking {
        // AudioRecord.read()가 짧게 돌려주면 청크 단위 추정은 전부 null이 되던 케이스.
        val engine = RecordingEngine(sine220Source(chunkSize = 300, chunkCount = 20))
        val frames = mutableListOf<RecordingEngine.PitchFrame>()

        engine.record { frames += it.pitchFrames }

        assertTrue(frames.isNotEmpty())
        frames.forEach { frame ->
            assertTrue(frame.pitchHz != null && kotlin.math.abs(frame.pitchHz!! - 220f) < 3f)
        }
    }

    @Test
    fun `읽기 청크가 hop과 같으면 청크마다 프레임이 정확히 1개씩 나온다 - KAN-105`() = runBlocking {
        // 실제 마이크의 방출 단위(READ_CHUNK_SIZE = 512). 곡선이 4점씩 계단으로 자라지 않고
        // 청크마다 1점씩 이어져야 32ms 주기로 갱신된다.
        val chunkCount = 10
        val engine = RecordingEngine(sine220Source(chunkSize = READ_CHUNK_SIZE, chunkCount = chunkCount))
        val reports = mutableListOf<List<RecordingEngine.PitchFrame>>()

        engine.record { reports += it.pitchFrames }

        assertEquals(chunkCount, reports.size)
        // 창(2048)을 채우는 동안은 빈 리포트고, 채운 뒤로는 hop이 곧 청크라 매번 1개다.
        val warmupChunks = CHUNK_SIZE / READ_CHUNK_SIZE - 1
        reports.take(warmupChunks).forEach { assertTrue(it.isEmpty()) }
        reports.drop(warmupChunks).forEach { assertEquals(1, it.size) }
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
