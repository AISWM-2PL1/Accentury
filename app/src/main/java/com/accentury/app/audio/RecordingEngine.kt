package com.accentury.app.audio

import androidx.annotation.RequiresPermission
import com.accentury.app.analytics.CrashReports
import kotlinx.coroutines.flow.takeWhile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RecordingEngine(private val source: PcmSource = AudioRecorder()) {

    /** 분석 창 1개의 F0. timestampMs는 창 **중앙** 샘플의 시각이고, 무성음이면 pitchHz가 null이다. */
    data class PitchFrame(
        val timestampMs: Long,
        val pitchHz: Float?,
    )

    data class Progress(
        val elapsedMs: Long,
        val rms: Double,
        /** 이번 청크가 완성시킨 분석 창들의 F0. 청크 길이에 따라 0개 이상이고 32ms 간격이다. */
        val pitchFrames: List<PitchFrame>,
    )

    sealed interface Outcome {
        data class Success(
            val pcm: ShortArray,
            val durationMs: Long,
            val autoStopped: Boolean,
        ) : Outcome

        data class Failure(val reason: String) : Outcome
    }

    private val activeSession = AtomicReference<AtomicBoolean?>(null)

    fun requestStop() {
        activeSession.get()?.set(true)
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    suspend fun record(onProgress: (Progress) -> Unit): Outcome {
        val stopRequested = AtomicBoolean(false)
        activeSession.set(stopRequested)
        val chunks = ArrayList<ShortArray>()
        var totalSamples = 0
        // 프레이머는 녹음 1회분 상태다. 이전 녹음의 잔여 샘플이 섞이지 않도록 여기서 새로 만든다.
        val framer = OverlappedFramer()
        try {
            source.recordingFlow()
                .takeWhile { !stopRequested.get() && totalSamples < MAX_SAMPLES }
                .collect { chunk ->
                    chunks += chunk
                    totalSamples += chunk.size
                    val pitchFrames = framer.push(chunk).map { frame ->
                        PitchFrame(
                            // 창 시작이 아니라 **중앙**의 시각이다. YIN이 낸 F0는 창 하나(128ms)
                            // 전체를 대표하는 값이라 대표 시각도 그 한가운데가 맞다. 시작 시각으로
                            // 찍으면 곡선이 실제보다 64ms 앞당겨 그려져, 같은 시간축에 놓인 가이드
                            // 곡선과 정렬이 어긋난다. 체감 지연은 이 변경으로 달라지지 않는다 -
                            // 값이 나오는 시점이 창이 다 찬 뒤라는 사실은 그대로고, 시간축 위의
                            // **위치**만 정직해진다.
                            timestampMs = (frame.startSampleIndex + frame.samples.size / 2) * 1000L / SAMPLE_RATE,
                            pitchHz = YinPitchEstimator.estimate(frame.samples),
                        )
                    }
                    onProgress(
                        Progress(
                            elapsedMs = minOf(totalSamples, MAX_SAMPLES) * 1000L / SAMPLE_RATE,
                            rms = calculateRms(chunk),
                            pitchFrames = pitchFrames,
                        ),
                    )
                }
        } catch (e: AudioRecorder.CaptureException) {
            /*
             * 마이크가 열리지 않은 실패는 사용자에게 "녹음에 실패했어요"까지만 보이고 그 뒤가
             * 남지 않는다 (KAN-33). 기기·OS 조합에 따라 갈리는 실패라 분포를 봐야 대응이 갈려서,
             * 여기서 비치명 이벤트로 남긴다. 문구는 AudioRecorder가 든 우리 고정 문자열이다.
             *
             * 아래 SecurityException에는 붙이지 않는다 - 녹음 중 권한이 회수된 경우라 결함이 아니라
             * 사용자 상태이고, 앱이 고칠 것도 없다.
             */
            CrashReports.recordCaptureFailure(e.message ?: "capture error")
            return Outcome.Failure(e.message ?: "capture error")
        } catch (e: SecurityException) {
            return Outcome.Failure("녹음 권한 없음 - ${e.message}")
        } finally {
            activeSession.compareAndSet(stopRequested, null)
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
