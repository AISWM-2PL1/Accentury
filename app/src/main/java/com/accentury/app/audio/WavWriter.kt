package com.accentury.app.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {

    fun write(file: File, pcm: ShortArray, sampleRate: Int = SAMPLE_RATE) {
        FileOutputStream(file).use { out -> out.write(toWavBytes(pcm, sampleRate)) }
    }

    /** 업로드는 파일을 거치지 않고 메모리에서 바로 멀티파트로 실어 보낸다 (KAN-88). */
    fun toWavBytes(pcm: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val wav = ByteArray(44 + pcm.size * 2)
        header(pcm.size * 2, sampleRate).copyInto(wav)
        ByteBuffer.wrap(wav, 44, pcm.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(pcm)
        return wav
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
