package com.accentury.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlappedFramerTest {

    /** 값이 곧 전역 샘플 인덱스인 램프 신호. 창 내용이 어디서 잘려 나왔는지 값으로 검증할 수 있다. */
    private fun ramp(from: Int, size: Int) = ShortArray(size) { (from + it).toShort() }

    private fun assertRampFrame(frame: AnalysisFrame, expectedStart: Long) {
        assertEquals(expectedStart, frame.startSampleIndex)
        assertEquals(CHUNK_SIZE, frame.samples.size)
        frame.samples.forEachIndexed { i, sample ->
            assertEquals((expectedStart + i).toShort(), sample)
        }
    }

    @Test
    fun `창 길이와 같은 청크는 프레임 1개를 만든다`() {
        val framer = OverlappedFramer()

        val frames = framer.push(ramp(0, CHUNK_SIZE))

        assertEquals(1, frames.size)
        assertRampFrame(frames[0], 0L)
    }

    @Test
    fun `창 하나 뒤에 hop만큼 더 들어오면 프레임이 하나 더 나온다`() {
        val framer = OverlappedFramer()
        framer.push(ramp(0, CHUNK_SIZE))

        val frames = framer.push(ramp(CHUNK_SIZE, 512))

        assertEquals(1, frames.size)
        assertRampFrame(frames[0], 512L)
    }

    @Test
    fun `hop보다 짧은 청크만으로는 프레임이 나오지 않는다`() {
        val framer = OverlappedFramer()
        framer.push(ramp(0, CHUNK_SIZE))

        assertTrue(framer.push(ramp(CHUNK_SIZE, 300)).isEmpty())
    }

    @Test
    fun `한 번의 큰 청크가 여러 프레임을 만든다`() {
        val framer = OverlappedFramer()

        val frames = framer.push(ramp(0, CHUNK_SIZE + 512 * 3))

        assertEquals(4, frames.size)
        listOf(0L, 512L, 1024L, 1536L).forEachIndexed { i, start ->
            assertRampFrame(frames[i], start)
        }
    }

    @Test
    fun `불규칙한 작은 청크들도 이어 붙여 올바른 프레임을 만든다`() {
        // AudioRecord.read()가 요청보다 짧게 돌려주는 경우. 청크 경계와 창 경계가 어긋난다.
        val framer = OverlappedFramer()
        val collected = mutableListOf<AnalysisFrame>()
        var pushed = 0
        while (pushed < CHUNK_SIZE + 512 * 2) {
            collected += framer.push(ramp(pushed, 300))
            pushed += 300
        }

        assertEquals(3, collected.size)
        listOf(0L, 512L, 1024L).forEachIndexed { i, start ->
            assertRampFrame(collected[i], start)
        }
    }

    @Test
    fun `창을 채우지 못한 꼬리는 프레임으로 나오지 않는다`() {
        val framer = OverlappedFramer()

        assertTrue(framer.push(ramp(0, CHUNK_SIZE - 1)).isEmpty())
        // 남은 꼬리(2047샘플)는 다음 입력이 올 때까지 그대로 대기한다.
        assertEquals(1, framer.push(ramp(CHUNK_SIZE - 1, 1)).size)
    }

    @Test
    fun `빈 청크는 프레임을 만들지 않는다`() {
        val framer = OverlappedFramer()

        assertTrue(framer.push(ShortArray(0)).isEmpty())
        assertEquals(1, framer.push(ramp(0, CHUNK_SIZE)).size)
    }
}
