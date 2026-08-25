package com.accentury.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * [inner]가 흘리는 PCM을 스피커로도 내보내는 [PcmSource] 데코레이터.
 *
 * 디버그 전용이다. 가짜 마이크([FilePcmSource])를 끼우면 녹음 버튼을 눌러도 아무 소리가 나지
 * 않아, 지금 어떤 음성이 어느 지점을 지나는지 개발자가 알 수 없다. 곡선이 이상할 때 그게 파일
 * 탓인지 분석 탓인지 가르려면 귀로 확인할 수 있어야 해서, 지나가는 청크를 그대로 [AudioTrack]에
 * 한 번 더 써 준다. 배선은 [defaultPcmSource]가 하며, 파일 소스일 때만 씌운다 - 실제 마이크에
 * 씌우면 자기 목소리가 스피커로 되돌아가 하울링이 난다.
 *
 * 청크는 손대지 않고 그대로 아래로 흘린다. 모니터는 관찰일 뿐이라 엔진이 받는 데이터가
 * 달라지면 안 된다.
 */
class MonitoredPcmSource(private val inner: PcmSource) : PcmSource {

    override fun recordingFlow(): Flow<ShortArray> = flow {
        val track = buildTrack()
        // 초기화 실패는 녹음을 막을 이유가 못 된다. 소리를 못 듣는 건 진단이 불편해지는 것뿐이라,
        // 모니터를 포기하고 inner를 그대로 흘린다.
        if (track == null) {
            emitAll(inner.recordingFlow())
            return@flow
        }
        try {
            track.play()
            inner.recordingFlow().collect { chunk ->
                track.write(chunk, 0, chunk.size)
                emit(chunk)
            }
        } finally {
            try {
                track.stop()
            } catch (_: IllegalStateException) {
            } finally {
                track.release()
            }
        }
    }
        // inner의 flowOn은 inner 상류에만 걸린다. 여기에 붙이지 않으면 블로킹 호출인
        // track.write가 수집 측 컨텍스트(viewModelScope = Main)에서 돌아 UI를 멈춘다.
        .flowOn(Dispatchers.IO)

    private fun buildTrack(): AudioTrack? {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            // 0.5초 분량은 물고 있어야 write가 매번 막히지 않는다 - 재생이 끊기면
            // 파일 페이스로 흘러오는 청크를 제때 받지 못해 분석 쪽까지 밀린다.
            // 청크가 32ms(READ_CHUNK_SIZE)로 잘게 쪼개져도 버퍼는 시간 기준으로 잡는다 -
            // 청크 수 기준으로 줄이면 write 사이 간격만 짧아지고 언더런 여유는 사라진다.
            .setBufferSizeInBytes(maxOf(minBufferSize, CHUNK_SIZE * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return null
        }
        return track
    }
}
