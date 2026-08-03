package com.accentury.app.audio

import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.takeWhile
import java.util.concurrent.atomic.AtomicBoolean

class RecordingEngine(private val recorder: AudioRecorder = AudioRecorder()) {

    data class Progress(val elapsedMs: Long, val rms: Double)

    sealed interface Outcome {
        data class Success(
            val pcm: ShortArray,
            val durationMs: Long,
            val autoStopped: Boolean,
        ) : Outcome

        data class Failure(val reason: String) : Outcome
    }

    private val stopRequested = AtomicBoolean(false)

    fun requestStop() {
        stopRequested.set(true)
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    suspend fun record(onProgress: (Progress) -> Unit): Outcome {
        stopRequested.set(false)
        val chunks = ArrayList<ShortArray>()
        var totalSamples = 0
        try {
            recorder.recordingFlow()
                .takeWhile { !stopRequested.get() && totalSamples < MAX_SAMPLES }
                .collect { chunk ->
                    chunks += chunk
                    totalSamples += chunk.size
                    onProgress(
                        Progress(
                            elapsedMs = totalSamples * 1000L / SAMPLE_RATE,
                            rms = calculateRms(chunk),
                        ),
                    )
                }
        } catch (e: AudioRecorder.CaptureException) {
            return Outcome.Failure(e.message ?: "capture error")
        }

        if (totalSamples == 0) return Outcome.Failure("캡처된 오디오가 없음")

        val pcm = ShortArray(minOf(totalSamples, MAX_SAMPLES))
        var offset = 0
        for (chunk in chunks) {
            val len = minOf(chunk.size, pcm.size - offset)
            if (len <= 0) break
            System.arraycopy(chunk, 0, pcm, offset, len)
            offset += len
        }
        return Outcome.Success(
            pcm = pcm,
            durationMs = pcm.size * 1000L / SAMPLE_RATE,
            autoStopped = totalSamples >= MAX_SAMPLES,
        )
    }

    companion object {
        const val MAX_DURATION_MS = 10_000L
        const val MAX_SAMPLES = (SAMPLE_RATE * MAX_DURATION_MS / 1000).toInt()
    }
}
