package com.accentury.app.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {

    fun write(file: File, pcm: ShortArray, sampleRate: Int = SAMPLE_RATE) {
        val byteData = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(byteData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(pcm)

        FileOutputStream(file).use { out ->
            out.write(header(byteData.size, sampleRate))
            out.write(byteData)
        }
    }

    private fun header(pcmByteCount: Int, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(pcmByteCount + 36)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(pcmByteCount)
        return header.array()
    }
}
