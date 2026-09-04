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

/**
 * YIN 분석 창 길이 (16kHz에서 128ms). 캡처 단위가 아니다 - [OverlappedFramer]의 windowSize이고,
 * 남성 저음(~70Hz)의 주기 두 개가 들어가야 YIN이 최저 F0를 찾을 수 있어 이 길이가 필요하다.
 */
const val CHUNK_SIZE = 2048

/**
 * 마이크에서 한 번에 읽어 흘리는 샘플 수 (16kHz에서 32ms). [OverlappedFramer]의 기본 hop과 같다.
 *
 * 분석은 KAN-104부터 이미 hop 단위(32ms)로 돌지만 방출은 [CHUNK_SIZE]마다여서, 화면 곡선이
 * 128ms에 한 번 4점씩 계단으로 자랐다. 읽기 단위를 hop과 맞추면 청크마다 창이 정확히 하나
 * 완성돼 갱신 주기가 곧 32ms가 된다 (AC2 - 중급 단말에서 끊겨 보이지 않는다).
 * 상태 갱신 31회/s는 Compose에 부담이 없다 - 곡선 재계산은 최대 313프레임짜리 O(n)이다.
 */
const val READ_CHUNK_SIZE = 512

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
            // AudioRecord 내부 버퍼는 읽기 단위와 별개로 넉넉히 잡는다 - 32ms마다 읽으러 오는데
            // 버퍼가 그만큼밖에 없으면 스케줄링이 한 번만 밀려도 샘플이 덮어써진다.
            maxOf(minBufferSize, CHUNK_SIZE * 2),
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw CaptureException("AudioRecord 초기화 실패 — 권한 없음 또는 마이크 점유 중")
        }
        val buffer = ShortArray(READ_CHUNK_SIZE)
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
