package com.accentury.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class RmsTest {

    @Test
    fun `무음은 RMS 0이다`() {
        assertEquals(0.0, calculateRms(ShortArray(2048)), 0.0)
    }

    @Test
    fun `일정 진폭 신호의 RMS는 그 진폭이다`() {
        val amplitude = 1000
        val square = ShortArray(2048) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }
        assertEquals(amplitude.toDouble(), calculateRms(square), 0.001)
    }
}
