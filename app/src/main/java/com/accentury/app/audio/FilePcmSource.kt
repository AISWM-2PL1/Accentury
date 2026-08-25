package com.accentury.app.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.EOFException
import java.io.InputStream

/**
 * WAV 파일을 마이크 대신 흘려보내는 [PcmSource].
 *
 * 에뮬레이터 마이크가 무음(0)만 주는 환경에서 피치 곡선을 눈으로 다듬으려면 매번 같은 음성이
 * 같은 속도로 들어와야 한다. 배선은 [defaultPcmSource]가 하고, 여기서는 스트림을 청크로
 * 쪼개는 일만 한다.
 *
 * [open]이 스트림 자체가 아니라 스트림을 여는 람다인 이유: 녹음은 여러 번 일어나고
 * (재녹음·재응시) 한 번 읽은 스트림은 되감을 수 없다. 호출 때마다 새로 연다.
 *
 * 리샘플·다운믹스는 하지 않는다. 엔진 전체가 16kHz 모노 16bit를 가정하고 있어, 다른 포맷을
 * 조용히 변환해 주면 실제 마이크와 다른 조건에서 곡선을 보게 된다.
 */
class FilePcmSource(
    private val open: () -> InputStream,
    /** 마이크와 같은 단위로 흘린다 - 청크 길이가 다르면 곡선이 자라는 페이스도 달라진다. */
    private val chunkSize: Int = READ_CHUNK_SIZE,
    /**
     * 청크 하나가 실제로 담는 시간만큼 쉬어 가며 흘릴지 여부.
     *
     * 마이크와 같은 페이스여야 스무딩 계수나 체감 지연을 평가할 수 있다. 테스트에서는 파일을
     * 통째로 즉시 흘려야 하므로 false로 준다.
     */
    private val realtime: Boolean = true,
) : PcmSource {

    override fun recordingFlow(): Flow<ShortArray> = flow {
        open().use { input ->
            val dataByteCount = parseWavHeader(input)
            val buffer = ByteArray(chunkSize * 2)
            var remaining = dataByteCount
            while (remaining > 0 && currentCoroutineContext().isActive) {
                val want = minOf(buffer.size, remaining)
                val read = input.readAtMost(buffer, want)
                // data 청크 길이가 실제 파일보다 길게 적혀 있어도 여기서 멈춘다.
                if (read < 2) break
                val samples = ShortArray(read / 2) { i ->
                    // 리틀엔디언: 낮은 바이트가 앞이다.
                    ((buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)).toShort()
                }
                if (realtime) delay(samples.size * 1000L / SAMPLE_RATE)
                emit(samples)
                remaining -= read
            }
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * RIFF 청크를 훑어 fmt 를 검증하고 data 청크 앞까지 [input]을 진행시킨 뒤, data 바이트 수를 돌려준다.
 *
 * 44바이트 고정 헤더로 가정하지 않는 이유: 편집기가 만든 WAV에는 LIST·fact 같은 청크가 fmt 와
 * data 사이에 끼어 있는 경우가 흔하다. 그런 파일을 넣으면 메타데이터를 소리로 재생하게 된다.
 */
internal fun parseWavHeader(input: InputStream): Int {
    val riff = ByteArray(12)
    input.readFullyOrThrow(riff, "RIFF 헤더")
    require(riff.ascii(0, 4) == "RIFF" && riff.ascii(8, 4) == "WAVE") {
        "WAV 파일이 아님 - RIFF/WAVE 시그니처 없음"
    }

    var fmtSeen = false
    val chunkHeader = ByteArray(8)
    while (true) {
        input.readFullyOrThrow(chunkHeader, "청크 헤더")
        val id = chunkHeader.ascii(0, 4)
        val size = chunkHeader.leInt(4)
        require(size >= 0) { "청크 크기가 비정상 - id=$id size=$size" }
        when (id) {
            "fmt " -> {
                require(size >= 16) { "fmt 청크가 너무 짧음 - size=$size" }
                val fmt = ByteArray(size)
                input.readFullyOrThrow(fmt, "fmt 청크")
                val audioFormat = fmt.leShort(0)
                val channels = fmt.leShort(2)
                val sampleRate = fmt.leInt(4)
                val bitsPerSample = fmt.leShort(14)
                require(audioFormat == 1) { "PCM이 아님 - audioFormat=$audioFormat" }
                require(channels == 1) { "모노가 아님 - channels=$channels" }
                require(bitsPerSample == 16) { "16bit가 아님 - bitsPerSample=$bitsPerSample" }
                require(sampleRate == SAMPLE_RATE) {
                    "샘플레이트가 ${SAMPLE_RATE}Hz가 아님 - sampleRate=$sampleRate"
                }
                fmtSeen = true
            }

            "data" -> {
                require(fmtSeen) { "fmt 청크 없이 data 청크가 먼저 나옴" }
                return size
            }

            else -> {
                // RIFF 청크는 짝수 경계에 놓인다 - 홀수 길이면 뒤에 패딩 1바이트가 붙는다.
                input.skipExactly(size + (size and 1))
            }
        }
    }
}

/** [want] 바이트를 목표로 채우되, 스트림이 먼저 끝나면 그때까지 읽은 만큼만 돌려준다. */
private fun InputStream.readAtMost(buffer: ByteArray, want: Int): Int {
    var filled = 0
    while (filled < want) {
        val n = read(buffer, filled, want - filled)
        if (n <= 0) break
        filled += n
    }
    return filled
}

private fun InputStream.readFullyOrThrow(buffer: ByteArray, what: String) {
    if (readAtMost(buffer, buffer.size) != buffer.size) {
        throw EOFException("WAV가 중간에 끊김 - $what 를 읽지 못함")
    }
}

private fun InputStream.skipExactly(count: Int) {
    var left = count.toLong()
    while (left > 0) {
        val skipped = skip(left)
        if (skipped > 0) {
            left -= skipped
            continue
        }
        // skip이 0을 돌려주는 스트림도 있다 - 한 바이트라도 실제로 읽어 전진한다.
        if (read() < 0) throw EOFException("WAV가 중간에 끊김 - 청크를 건너뛰지 못함")
        left--
    }
}

private fun ByteArray.ascii(offset: Int, length: Int) = String(this, offset, length, Charsets.US_ASCII)

private fun ByteArray.leShort(offset: Int) =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.leInt(offset: Int) =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
