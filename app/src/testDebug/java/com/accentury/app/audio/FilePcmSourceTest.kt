package com.accentury.app.audio

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FilePcmSourceTest {

    /**
     * 테스트용 WAV 바이트. [extraChunks]는 fmt 와 data 사이에 끼워 넣을 (id, payload) 쌍이다 -
     * 편집기가 만든 파일에는 LIST 같은 청크가 그 자리에 흔히 들어간다.
     */
    private fun wavBytes(
        samples: ShortArray,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = 1,
        bitsPerSample: Int = 16,
        extraChunks: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray {
        val dataBytes = samples.size * 2
        val extraBytes = extraChunks.sumOf { 8 + it.second.size + (it.second.size and 1) }
        val buffer = ByteBuffer.allocate(44 + extraBytes + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + extraBytes + dataBytes)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * bitsPerSample / 8)
        buffer.putShort((channels * bitsPerSample / 8).toShort())
        buffer.putShort(bitsPerSample.toShort())
        extraChunks.forEach { (id, payload) ->
            buffer.put(id.toByteArray())
            buffer.putInt(payload.size)
            buffer.put(payload)
            if (payload.size and 1 == 1) buffer.put(0)
        }
        buffer.put("data".toByteArray())
        buffer.putInt(dataBytes)
        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }

    private fun collect(bytes: ByteArray, chunkSize: Int = CHUNK_SIZE): List<ShortArray> =
        runBlocking {
            FilePcmSource(
                open = { ByteArrayInputStream(bytes) },
                chunkSize = chunkSize,
                realtime = false,
            ).recordingFlow().toList()
        }

    @Test
    fun `마지막 청크만 짧고 총 샘플 수가 data 길이와 같다`() {
        val samples = ShortArray(CHUNK_SIZE * 3 + 100) { (it % 1000).toShort() }

        val chunks = collect(wavBytes(samples))

        assertEquals(4, chunks.size)
        chunks.dropLast(1).forEach { assertEquals(CHUNK_SIZE, it.size) }
        assertEquals(100, chunks.last().size)
        assertEquals(samples.size, chunks.sumOf { it.size })
    }

    @Test
    fun `기본 청크는 마이크와 같은 READ_CHUNK_SIZE다 - KAN-105`() {
        // 가짜 마이크가 실제 마이크와 다른 페이스로 흘리면 곡선이 자라는 모습도 달라져,
        // 파일로 눈으로 다듬은 결과가 실기기에서 그대로 재현되지 않는다.
        val samples = ShortArray(READ_CHUNK_SIZE * 2) { (it % 1000).toShort() }

        val chunks = runBlocking {
            FilePcmSource(
                open = { ByteArrayInputStream(wavBytes(samples)) },
                realtime = false,
            ).recordingFlow().toList()
        }

        assertEquals(2, chunks.size)
        chunks.forEach { assertEquals(READ_CHUNK_SIZE, it.size) }
    }

    @Test
    fun `샘플 값이 리틀엔디언 그대로 전달된다`() {
        // 부호·상하위 바이트가 갈리는 값들 - 엔디언이 뒤집히면 바로 어긋난다.
        val samples = shortArrayOf(0, 1, -1, 256, -256, 32767, -32768, 4660)

        val chunks = collect(wavBytes(samples), chunkSize = 4)

        assertEquals(2, chunks.size)
        assertTrue(samples.contentEquals(chunks.flatMap { it.toList() }.toShortArray()))
    }

    @Test
    fun `fmt 와 data 사이에 다른 청크가 있어도 data를 찾는다`() {
        val samples = ShortArray(10) { (it * 7).toShort() }
        // 홀수 길이 페이로드까지 넣어 패딩 1바이트 건너뛰기도 함께 확인한다.
        val bytes = wavBytes(
            samples,
            extraChunks = listOf(
                "LIST" to ByteArray(9) { 0x41 },
                "fact" to ByteArray(4),
            ),
        )

        val chunks = collect(bytes)

        assertTrue(samples.contentEquals(chunks.flatMap { it.toList() }.toShortArray()))
    }

    @Test
    fun `스테레오는 거부한다`() {
        val bytes = wavBytes(ShortArray(100), channels = 2)

        val thrown = runCatching { collect(bytes) }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `8bit는 거부한다`() {
        val bytes = wavBytes(ShortArray(100), bitsPerSample = 8)

        val thrown = runCatching { collect(bytes) }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `다른 샘플레이트는 거부한다 - 리샘플하지 않는다`() {
        val bytes = wavBytes(ShortArray(100), sampleRate = 44_100)

        val thrown = runCatching { collect(bytes) }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `WAV가 아니면 거부한다`() {
        val thrown = runCatching { collect(ByteArray(64) { 0x30 }) }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `엔진에 물리면 파일 길이만큼의 Success가 나온다`() = runBlocking {
        val samples = ShortArray(SAMPLE_RATE * 2) { (it % 500).toShort() }
        val bytes = wavBytes(samples)
        val engine = RecordingEngine(
            FilePcmSource({ ByteArrayInputStream(bytes) }, realtime = false),
        )

        val outcome = engine.record {}

        assertTrue(outcome is RecordingEngine.Outcome.Success)
        outcome as RecordingEngine.Outcome.Success
        assertEquals(samples.size, outcome.pcm.size)
        assertEquals(2_000L, outcome.durationMs)
        assertTrue(samples.contentEquals(outcome.pcm))
    }

    @Test
    fun `배치된 fake_mic asset이 그대로 재생된다`() = runBlocking {
        val asset = File(moduleDir(), "src/debug/assets/fake_mic.wav")
        assertTrue("asset이 없다: ${asset.absolutePath}", asset.exists())
        val engine = RecordingEngine(
            FilePcmSource({ FileInputStream(asset) }, realtime = false),
        )

        val outcome = engine.record {}

        outcome as RecordingEngine.Outcome.Success
        // 2.5초 파일. 마지막 청크가 짧아 청크 하나(32ms)만큼의 오차는 허용한다.
        assertTrue(
            "durationMs=${outcome.durationMs}",
            kotlin.math.abs(outcome.durationMs - 2_500L) <= READ_CHUNK_SIZE * 1000L / SAMPLE_RATE,
        )
    }

    /** 테스트 실행 cwd는 Gradle 설정에 달렸다 - app 모듈이 아니면 레포 루트로 보고 내려간다. */
    private fun moduleDir(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "src/debug/assets").isDirectory) cwd else File(cwd, "app")
    }
}
