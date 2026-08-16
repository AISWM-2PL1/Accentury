package com.accentury.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class YinPitchEstimatorTest {

    private fun sine(freqHz: Double, amplitude: Double = 8000.0, size: Int = CHUNK_SIZE): ShortArray =
        ShortArray(size) { (amplitude * sin(2 * PI * freqHz * it / SAMPLE_RATE)).toInt().toShort() }

    @Test
    fun `220Hz 사인파의 F0를 추정한다`() {
        val f0 = YinPitchEstimator.estimate(sine(220.0))
        assertNotNull(f0)
        assertEquals(220f, f0!!, 3f)
    }

    @Test
    fun `저음 경계 근처 100Hz를 추정한다`() {
        val f0 = YinPitchEstimator.estimate(sine(100.0))
        assertNotNull(f0)
        assertEquals(100f, f0!!, 3f)
    }

    @Test
    fun `고음 350Hz를 추정한다`() {
        val f0 = YinPitchEstimator.estimate(sine(350.0))
        assertNotNull(f0)
        assertEquals(350f, f0!!, 4f)
    }

    @Test
    fun `배음이 섞여도 기본 주파수를 잡는다 - 옥타브 오류 없음`() {
        // 실제 목소리처럼 2, 3배음 포함. 단순 autocorrelation이 배음(240Hz)으로 튀던 케이스.
        val f0Hz = 120.0
        val chunk = ShortArray(CHUNK_SIZE) {
            val t = 2 * PI * f0Hz * it / SAMPLE_RATE
            (5000 * sin(t) + 3000 * sin(2 * t) + 2000 * sin(3 * t)).toInt().toShort()
        }
        val f0 = YinPitchEstimator.estimate(chunk)
        assertNotNull(f0)
        assertEquals(120f, f0!!, 3f)
    }

    @Test
    fun `대역 상한 경계 396Hz도 400Hz를 넘기지 않는다`() {
        val f0 = YinPitchEstimator.estimate(sine(396.0))
        assertNotNull(f0)
        assertEquals(396f, f0!!, 4f)
        assertTrue(f0 <= 400f)
    }

    @Test
    fun `대역 밖 410Hz는 400Hz 초과 값을 반환하지 않는다`() {
        // τmin=40 경계에서 보간이 대역 밖으로 새는지 확인. null(무성음) 또는 clamp된 값만 허용.
        val f0 = YinPitchEstimator.estimate(sine(410.0))
        assertTrue(f0 == null || f0 <= 400f)
    }

    @Test
    fun `무음은 무성음으로 판정한다`() {
        assertNull(YinPitchEstimator.estimate(ShortArray(CHUNK_SIZE)))
    }

    @Test
    fun `백색잡음은 무성음으로 판정한다`() {
        val random = Random(42)
        val noise = ShortArray(CHUNK_SIZE) { (random.nextInt(16000) - 8000).toShort() }
        assertNull(YinPitchEstimator.estimate(noise))
    }

    @Test
    fun `탐색 대역 밖 저주파는 무성음으로 판정한다`() {
        // 50Hz(주기 320샘플)는 τmax=200 안에서 겹치는 지점이 없다.
        assertNull(YinPitchEstimator.estimate(sine(50.0)))
    }

    @Test
    fun `탐색에 필요한 길이보다 짧은 청크는 무성음으로 판정한다`() {
        assertNull(YinPitchEstimator.estimate(sine(220.0, size = 256)))
    }
}
