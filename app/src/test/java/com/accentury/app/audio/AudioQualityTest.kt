package com.accentury.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioQualityTest {

    private fun pcmOfSeconds(seconds: Double, amplitude: Int): ShortArray =
        ShortArray((SAMPLE_RATE * seconds).toInt()) {
            if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort()
        }

    @Test
    fun `1초 미만 발화는 TOO_SHORT다`() {
        val pcm = pcmOfSeconds(0.5, 1000)
        assertEquals(QualityStatus.TOO_SHORT, AudioQuality.judge(pcm, 500L))
    }

    @Test
    fun `무음에 가까운 녹음은 TOO_QUIET다`() {
        val pcm = pcmOfSeconds(2.0, 10)
        assertEquals(QualityStatus.TOO_QUIET, AudioQuality.judge(pcm, 2000L))
    }

    @Test
    fun `클리핑 비율이 임계를 넘으면 CLIPPED다`() {
        val pcm = pcmOfSeconds(2.0, 1000)
        val clipCount = (pcm.size * 0.02).toInt()
        for (i in 0 until clipCount) pcm[i] = Short.MAX_VALUE
        assertEquals(QualityStatus.CLIPPED, AudioQuality.judge(pcm, 2000L))
    }

    @Test
    fun `정상 발화는 NORMAL이다`() {
        val pcm = pcmOfSeconds(2.0, 1000)
        assertEquals(QualityStatus.NORMAL, AudioQuality.judge(pcm, 2000L))
    }
}
