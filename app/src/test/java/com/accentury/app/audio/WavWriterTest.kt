package com.accentury.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {

    @Test
    fun `헤더 44바이트가 16kHz mono 16bit 규격대로 생성된다`() {
        val pcm = ShortArray(SAMPLE_RATE) { (it % 100).toShort() }
        val file = File.createTempFile("wav_test", ".wav")

        WavWriter.write(file, pcm)
        val bytes = file.readBytes()
        file.delete()

        assertEquals(44 + SAMPLE_RATE * 2, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("fmt ", String(bytes, 12, 4))
        assertEquals("data", String(bytes, 36, 4))

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, bb.getShort(20).toInt())
        assertEquals(1, bb.getShort(22).toInt())
        assertEquals(SAMPLE_RATE, bb.getInt(24))
        assertEquals(SAMPLE_RATE * 2, bb.getInt(28))
        assertEquals(16, bb.getShort(34).toInt())
        assertEquals(SAMPLE_RATE * 2, bb.getInt(40))
    }

    @Test
    fun `PCM 데이터가 손실 없이 기록된다`() {
        val pcm = shortArrayOf(0, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE)
        val file = File.createTempFile("wav_test", ".wav")

        WavWriter.write(file, pcm)
        val bytes = file.readBytes()
        file.delete()

        val restored = ShortArray(pcm.size)
        ByteBuffer.wrap(bytes, 44, pcm.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(restored)
        assertArrayEquals(pcm, restored)
    }
}
