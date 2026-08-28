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
        /**
         * 지금이 8초 경고 구간인가. 판정은 [isCountdownWarning]이 하고 여기는 상한만 채운다 —
         * 경계와 반올림이 웹과 같은 값이어야 해서 규칙을 한 자리에 뒀다 (`RecordingCountdown.kt`).
         *
         * `elapsedMs >= 8_000`을 직접 적던 것을 "남은 시간 2초 이하"로 바꿨다. 값이 같아
         * 동작은 그대로지만, 상한이 문항마다 달라지는 날 비율도 절대값도 아닌 **남은 시간**이
         * 옳은 기준이라는 것이 식에 남는다.
         */
        val countdownActive: Boolean
            get() = isCountdownWarning(elapsedMs, RecordingEngine.MAX_DURATION_MS)
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
