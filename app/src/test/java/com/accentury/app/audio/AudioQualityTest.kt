package com.accentury.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `measure는 진폭을 0에서 1 사이로 정규화한다`() {
        val half = (AudioQuality.FULL_SCALE / 2).toInt()
        val quality = AudioQuality.measure(pcmOfSeconds(1.0, half))

        assertEquals(0.5, quality.rms, 1e-6)
        assertEquals(0.5, quality.peak, 1e-6)
        assertEquals(0.0, quality.silenceRatio, 1e-9)
        assertFalse(quality.clipped)
    }

    @Test
    fun `무음 배열은 silenceRatio가 1이고 나머지는 0이다`() {
        val quality = AudioQuality.measure(ShortArray(SAMPLE_RATE))

        assertEquals(0.0, quality.rms, 1e-9)
        assertEquals(0.0, quality.peak, 1e-9)
        assertEquals(1.0, quality.silenceRatio, 1e-9)
        assertFalse(quality.clipped)
    }

    @Test
    fun `클리핑이 임계를 넘으면 measure의 clipped가 true다`() {
        val pcm = pcmOfSeconds(2.0, 1000)
        val clipCount = (pcm.size * 0.02).toInt()
        for (i in 0 until clipCount) pcm[i] = Short.MAX_VALUE

        val quality = AudioQuality.measure(pcm)

        assertTrue(quality.clipped)
        assertTrue(quality.peak > 0.99)
    }

    @Test
    fun `빈 배열은 0으로 나누지 않고 전부 0을 반환한다`() {
        val quality = AudioQuality.measure(ShortArray(0))

        assertEquals(ClientQuality(rms = 0.0, peak = 0.0, silenceRatio = 0.0, clipped = false), quality)
    }
}
