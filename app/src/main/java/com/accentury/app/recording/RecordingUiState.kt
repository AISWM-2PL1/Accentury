package com.accentury.app.recording

import com.accentury.app.audio.QualityStatus

sealed interface RecordingUiState {

    data object Idle : RecordingUiState

    data class Recording(val elapsedMs: Long, val rms: Double) : RecordingUiState {
        val countdownActive: Boolean get() = elapsedMs >= COUNTDOWN_WARNING_MS

        companion object {
            const val COUNTDOWN_WARNING_MS = 8_000L
        }
    }

    data class Review(
        val attemptId: String,
        val durationMs: Long,
        val quality: QualityStatus,
        val autoStopped: Boolean,
    ) : RecordingUiState {
        val canProceed: Boolean get() = quality == QualityStatus.NORMAL
    }

    data class Failed(val reason: String) : RecordingUiState
}
