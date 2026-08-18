package com.accentury.app.recording

import com.accentury.app.audio.QualityStatus
import com.accentury.app.audio.RecordingEngine

sealed interface RecordingUiState {

    data object Idle : RecordingUiState

    /**
     * @property pitchFrames 녹음 시작부터 지금까지 누적된 F0 프레임 (시각 순).
     *   청크마다 새로 온 몇 개가 아니라 전부인 이유는 곡선을 매번 다시 그리기 때문이다.
     */
    data class Recording(
        val elapsedMs: Long,
        val rms: Double,
        val pitchFrames: List<RecordingEngine.PitchFrame> = emptyList(),
    ) : RecordingUiState {
        val countdownActive: Boolean get() = elapsedMs >= COUNTDOWN_WARNING_MS

        companion object {
            const val COUNTDOWN_WARNING_MS = 8_000L
        }
    }

    /**
     * @property pitchFrames 방금 끝난 녹음의 F0 프레임 전체. 재녹음과 다음을 고르는 화면이
     *   자기 억양을 가이드와 비교할 순간이라, 곡선을 지우지 않고 남겨 둔다.
     */
    data class Review(
        val attemptId: String,
        val durationMs: Long,
        val quality: QualityStatus,
        val autoStopped: Boolean,
        val pitchFrames: List<RecordingEngine.PitchFrame> = emptyList(),
    ) : RecordingUiState {
        val canProceed: Boolean get() = quality == QualityStatus.NORMAL
    }

    data class Failed(val reason: String) : RecordingUiState
}
