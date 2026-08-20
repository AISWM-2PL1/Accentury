package com.accentury.app.ui

import com.accentury.app.ui.components.progressFraction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 진척도 막대 비율의 가장자리 (KAN-148). 화면에서는 "막대가 안 보인다"·"칸을 넘어간다"로만
 * 드러나 원인을 찾기 어려운 종류라, 계산 자체를 여기서 고정한다.
 */
class ProgressFractionTest {
    @Test
    fun `정상 범위는 그대로 비율이 된다`() {
        assertEquals(0.3f, progressFraction(3, 10), 0.0001f)
        assertEquals(1f, progressFraction(10, 10), 0.0001f)
    }

    @Test
    fun `total이 0이면 0이다 - 0으로 나누면 NaN이 들어가 막대가 사라진다`() {
        assertEquals(0f, progressFraction(0, 0), 0.0001f)
        assertEquals(0f, progressFraction(3, 0), 0.0001f)
    }

    @Test
    fun `total을 넘는 current는 1로 잘린다 - 막대가 칸을 넘어 그려지지 않는다`() {
        assertEquals(1f, progressFraction(11, 10), 0.0001f)
    }

    @Test
    fun `음수 current는 0으로 잘린다`() {
        assertEquals(0f, progressFraction(-1, 10), 0.0001f)
    }
}
