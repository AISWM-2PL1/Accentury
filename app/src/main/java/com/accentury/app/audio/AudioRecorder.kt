package com.accentury.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.sqrt

const val SAMPLE_RATE = 16_000
const val CHUNK_SIZE = 2048

interface PcmSource {
    fun recordingFlow(): Flow<ShortArray>
}

class AudioRecorder : PcmSource {

    class CaptureException(message: String) : RuntimeException(message)

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    override fun recordingFlow(): Flow<ShortArray> = flow {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferSize, CHUNK_SIZE * 2),
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw CaptureException("AudioRecord 초기화 실패 — 권한 없음 또는 마이크 점유 중")
        }
        val buffer = ShortArray(CHUNK_SIZE)
        try {
            try {
                audioRecord.startRecording()
            } catch (e: IllegalStateException) {
                throw CaptureException("녹음 시작 실패 — ${e.message}")
            } catch (e: SecurityException) {
                throw CaptureException("녹음 권한 없음 — ${e.message}")
            }
            while (currentCoroutineContext().isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                when {
                    read > 0 -> emit(buffer.copyOf(read))
                    read < 0 -> throw CaptureException("read 실패 code=$read — 녹음 중 권한 회수 가능성")
                }
            }
        } finally {
            try {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            } catch (_: IllegalStateException) {
            } finally {
                audioRecord.release()
            }
        }
    }.flowOn(Dispatchers.IO)
}

fun calculateRms(chunk: ShortArray): Double {
    var sum = 0.0
    for (sample in chunk) sum += sample.toDouble() * sample
    return sqrt(sum / chunk.size)
}
