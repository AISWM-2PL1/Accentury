package com.accentury.app.audio

/** 분석 창 하나. samples는 항상 windowSize 길이이고, startSampleIndex는 녹음 시작 기준 전역 샘플 위치다. */
class AnalysisFrame(
    val samples: ShortArray,
    val startSampleIndex: Long,
)

/**
 * 겹침 프레이밍 - 임의 길이 PCM 청크를 고정 길이 분석 창으로 잘라낸다 (KAN-104).
 *
 * AudioRecord.read()는 요청한 2048샘플보다 짧게 돌려줄 수 있고, 청크당 1회 추정하면
 * 갱신 주기가 128ms라 NFR-PF-02(100ms 이하)를 못 맞춘다. 창을 hopSize만큼만 밀어
 * 창 길이(YIN 탐색에 필요한 2048샘플)는 유지한 채 갱신 주기만 hop 기준으로 낮춘다.
 * 기본값 2048/512는 75% 겹침 = 16kHz에서 32ms 주기.
 *
 * 상태가 있으므로 녹음 1회당 1개를 쓴다.
 */
class OverlappedFramer(
    private val windowSize: Int = CHUNK_SIZE,
    private val hopSize: Int = 512,
) {
    init {
        require(windowSize > 0 && hopSize > 0) { "windowSize/hopSize는 양수여야 함" }
        require(hopSize <= windowSize) { "hopSize는 windowSize 이하여야 함" }
    }

    /** buffer[0 until count]는 아직 창으로 소비되지 않은 샘플이고, buffer[0]의 전역 위치가 bufferStartIndex다. */
    private var buffer = ShortArray(windowSize * 2)
    private var count = 0
    private var bufferStartIndex = 0L

    /** 청크를 밀어넣고 이번에 완성된 창들을 순서대로 돌려준다. 완성된 창이 없으면 빈 리스트. */
    fun push(chunk: ShortArray): List<AnalysisFrame> {
        if (chunk.isEmpty()) return emptyList()
        ensureCapacity(count + chunk.size)
        System.arraycopy(chunk, 0, buffer, count, chunk.size)
        count += chunk.size

        val frames = ArrayList<AnalysisFrame>()
        var offset = 0
        while (count - offset >= windowSize) {
            frames += AnalysisFrame(
                samples = buffer.copyOfRange(offset, offset + windowSize),
                startSampleIndex = bufferStartIndex + offset,
            )
            offset += hopSize
        }
        if (offset > 0) {
            // 다음 창 시작점 앞은 다시 볼 일이 없다. 버려서 버퍼가 녹음 길이만큼 자라지 않게 한다.
            System.arraycopy(buffer, offset, buffer, 0, count - offset)
            count -= offset
            bufferStartIndex += offset
        }
        return frames
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var newSize = buffer.size
        while (newSize < needed) newSize *= 2
        buffer = buffer.copyOf(newSize)
    }
}
