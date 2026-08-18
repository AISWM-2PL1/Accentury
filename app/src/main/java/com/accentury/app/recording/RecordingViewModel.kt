package com.accentury.app.recording

import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.accentury.app.BuildConfig
import com.accentury.app.audio.AudioQuality
import com.accentury.app.audio.RecordingEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class RecordingViewModel(
    private val engine: RecordingEngine = RecordingEngine(),
) : ViewModel() {

    private val _uiState = MutableStateFlow<RecordingUiState>(RecordingUiState.Idle)
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private var lastPcm: ShortArray? = null
    private var recordingJob: Job? = null

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun startRecording() {
        if (_uiState.value is RecordingUiState.Recording) return
        lastPcm = null
        val attemptId = "at_" + UUID.randomUUID()
        _uiState.value = RecordingUiState.Recording(elapsedMs = 0L, rms = 0.0)
        recordingJob = viewModelScope.launch {
            var peakRms = 0.0
            // 녹음 1회분 누적. 상한이 있는 목록이다 - 녹음이 10초에서 끊기고 프레임은 32ms
            // 간격이라 최대 313개 남짓이라, 링버퍼 없이 그냥 쌓아도 된다.
            val pitchFrames = ArrayList<RecordingEngine.PitchFrame>()
            val outcome = engine.record { progress ->
                if (progress.rms > peakRms) peakRms = progress.rms
                if (BuildConfig.DEBUG) {
                    // KAN-103 스파이크: 마이크 F0 확인용 로그. 무성음 프레임은 "-".
                    // KAN-104부터 청크당 여러 프레임이라 마지막 프레임만 찍고 개수를 함께 남긴다.
                    val lastPitch = progress.pitchFrames.lastOrNull()?.pitchHz
                    val f0 = lastPitch?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "-"
                    android.util.Log.d(TAG, "rec $attemptId elapsed=${progress.elapsedMs} rms=${progress.rms.toInt()} frames=${progress.pitchFrames.size} f0=$f0")
                }
                pitchFrames += progress.pitchFrames
                // 상태에 넣는 목록은 복사본이다 - 그대로 넘기면 다음 청크의 += 가 이미 방출한
                // 상태의 내용까지 바꿔 버려 Compose가 변화를 못 알아챈다.
                _uiState.value = RecordingUiState.Recording(
                    elapsedMs = progress.elapsedMs,
                    rms = progress.rms,
                    pitchFrames = pitchFrames.toList(),
                )
            }
            when (outcome) {
                is RecordingEngine.Outcome.Success -> {
                    lastPcm = outcome.pcm
                    val quality = AudioQuality.judge(outcome.pcm, outcome.durationMs)
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(TAG, "done $attemptId duration=${outcome.durationMs} peakRms=${peakRms.toInt()} quality=$quality")
                    }
                    _uiState.value = RecordingUiState.Review(
                        attemptId = attemptId,
                        durationMs = outcome.durationMs,
                        quality = quality,
                        autoStopped = outcome.autoStopped,
                    )
                }
                is RecordingEngine.Outcome.Failure -> {
                    lastPcm = null
                    _uiState.value = RecordingUiState.Failed(outcome.reason)
                }
            }
        }
    }

    fun stopRecording() {
        engine.requestStop()
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun retryRecording() {
        startRecording()
    }

    fun consumeRecording(): ShortArray? {
        val pcm = lastPcm
        lastPcm = null
        return pcm
    }

    fun reset() {
        recordingJob?.cancel()
        recordingJob = null
        lastPcm = null
        _uiState.value = RecordingUiState.Idle
    }

    private companion object {
        const val TAG = "RecordingVM"
    }
}
