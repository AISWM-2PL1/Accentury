package com.accentury.app.audio

enum class QualityStatus { NORMAL, TOO_SHORT, TOO_QUIET, CLIPPED }

object AudioQuality {

    const val MIN_DURATION_MS = 1_000L
    const val QUIET_RMS_THRESHOLD = 100.0
    const val CLIP_SAMPLE_THRESHOLD = 32_000
    const val CLIP_RATIO_THRESHOLD = 0.01

    fun judge(pcm: ShortArray, durationMs: Long): QualityStatus {
        if (durationMs < MIN_DURATION_MS) return QualityStatus.TOO_SHORT
        if (pcm.isEmpty()) return QualityStatus.TOO_SHORT

        var clipped = 0
        for (sample in pcm) {
            if (sample >= CLIP_SAMPLE_THRESHOLD || sample <= -CLIP_SAMPLE_THRESHOLD) clipped++
        }
        if (clipped.toDouble() / pcm.size > CLIP_RATIO_THRESHOLD) return QualityStatus.CLIPPED

        if (calculateRms(pcm) < QUIET_RMS_THRESHOLD) return QualityStatus.TOO_QUIET

        return QualityStatus.NORMAL
    }
}
