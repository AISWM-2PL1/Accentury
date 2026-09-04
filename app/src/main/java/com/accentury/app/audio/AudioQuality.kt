package com.accentury.app.audio

import kotlinx.serialization.Serializable
import kotlin.math.abs

enum class QualityStatus { NORMAL, TOO_SHORT, TOO_QUIET, CLIPPED }

/**
 * 업로드 meta 파트에 실리는 클라이언트 측 품질 지표(API 명세서 §3.3).
 * rms·peak·silenceRatio는 모두 0..1 정규화 실수다.
 */
@Serializable
data class ClientQuality(
    val rms: Double,
    val peak: Double,
    val silenceRatio: Double,
    val clipped: Boolean,
)

object AudioQuality {

    const val MIN_DURATION_MS = 1_000L
    const val QUIET_RMS_THRESHOLD = 100.0
    const val CLIP_SAMPLE_THRESHOLD = 32_000
    const val CLIP_RATIO_THRESHOLD = 0.01

    /** 16-bit PCM 전체 스케일. 정규화(0..1) 분모로 쓴다. */
    const val FULL_SCALE = 32_768.0

    /**
     * 무음으로 볼 진폭 상한. 전체 스케일의 1%(= -40 dBFS)로,
     * 조용한 실내 잡음은 걸러내면서 실제 발화는 남기는 수준이다.
     */
    const val SILENCE_SAMPLE_THRESHOLD = 328

    fun judge(pcm: ShortArray, durationMs: Long): QualityStatus {
        if (durationMs < MIN_DURATION_MS) return QualityStatus.TOO_SHORT
        if (pcm.isEmpty()) return QualityStatus.TOO_SHORT

        val quality = measure(pcm)
        if (quality.clipped) return QualityStatus.CLIPPED
        // QUIET_RMS_THRESHOLD는 정규화 전 원 스케일 기준이라 되돌려서 비교한다.
        if (quality.rms * FULL_SCALE < QUIET_RMS_THRESHOLD) return QualityStatus.TOO_QUIET

        return QualityStatus.NORMAL
    }

    /** 서버로 보낼 품질 지표를 한 번의 순회로 계산한다. 빈 배열은 전부 0으로 본다. */
    fun measure(pcm: ShortArray): ClientQuality {
        if (pcm.isEmpty()) return ClientQuality(rms = 0.0, peak = 0.0, silenceRatio = 0.0, clipped = false)

        var peak = 0
        var silentCount = 0
        var clippedCount = 0
        for (sample in pcm) {
            // Short.MIN_VALUE의 절댓값(32768)까지 담으려면 Int로 올려서 비교한다.
            val magnitude = abs(sample.toInt())
            if (magnitude > peak) peak = magnitude
            if (magnitude < SILENCE_SAMPLE_THRESHOLD) silentCount++
            if (magnitude >= CLIP_SAMPLE_THRESHOLD) clippedCount++
        }

        return ClientQuality(
            rms = calculateRms(pcm) / FULL_SCALE,
            peak = peak / FULL_SCALE,
            silenceRatio = silentCount.toDouble() / pcm.size,
            clipped = clippedCount.toDouble() / pcm.size > CLIP_RATIO_THRESHOLD,
        )
    }
}
