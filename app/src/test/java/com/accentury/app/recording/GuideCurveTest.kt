package com.accentury.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCurveTest {

    @Test
    fun `전부 무성이면 그릴 점이 없다`() {
        assertEquals(emptyList<CurvePoint>(), guideCurveDisplayPoints(listOf(null, null, null)))
        assertEquals(emptyList<CurvePoint>(), guideCurveDisplayPoints(emptyList()))
    }

    @Test
    fun `NaN도 무성으로 취급한다`() {
        assertEquals(emptyList<CurvePoint>(), guideCurveDisplayPoints(listOf(Double.NaN)))
    }

    @Test
    fun `높은 음이 위로 간다 - 값이 클수록 y가 작다`() {
        val points = guideCurveDisplayPoints(listOf(-1.0, 0.0, 1.0, 2.0))
        for (k in 0 until points.size - 1) {
            assertTrue("y는 단조 감소해야 한다: $points", points[k].y > points[k + 1].y)
        }
    }

    @Test
    fun `0은 무성이 아니라 유효한 semitone 값이다`() {
        // 0을 무성으로 잘못 취급하면 세 점이 아니라 두 점이 나온다
        val points = guideCurveDisplayPoints(listOf(-1.0, 0.0, 1.0))
        assertEquals(3, points.size)
    }

    @Test
    fun `중간 무성 구간은 양옆 값의 선형 보간으로 이어진다`() {
        val points = guideCurveDisplayPoints(listOf(0.0, null, null, 3.0))
        assertEquals(4, points.size)
        // 0→3 사이 두 무성 프레임은 1, 2로 채워진다. y 간격이 균일한지로 확인한다.
        val gaps = (0 until 3).map { points[it].y - points[it + 1].y }
        gaps.forEach { assertEquals(gaps[0], it, 1e-5f) }
    }

    @Test
    fun `앞뒤 무성 구간은 그리지 않되 x 위치는 원래 시각을 유지한다`() {
        val points = guideCurveDisplayPoints(listOf(null, 1.0, 2.0, 1.0, null, null))
        assertEquals(3, points.size)
        // 배열 길이 6 → x 간격은 1/5. 첫 유성 프레임은 index 1이므로 x = 0.2에서 시작한다.
        assertEquals(0.2f, points.first().x, 1e-5f)
        assertEquals(0.6f, points.last().x, 1e-5f)
    }

    @Test
    fun `x는 시간축 전체를 0에서 1로 나눈 위치다`() {
        val points = guideCurveDisplayPoints(listOf(1.0, 2.0, 3.0))
        assertEquals(listOf(0.0f, 0.5f, 1.0f), points.map { it.x })
    }

    @Test
    fun `표시 스케일 여백 - 최고점과 최저점이 레인 가장자리에 붙지 않는다`() {
        val points = guideCurveDisplayPoints(listOf(-2.0, 5.0))
        points.forEach {
            assertTrue("y가 가장자리를 벗어났다: $it", it.y > 0.05f && it.y < 0.95f)
        }
        // 여백 10% 기준 최고점 y = 1 - 1.1/1.2 ≈ 0.0833
        assertEquals(0.0833f, points.map { it.y }.min(), 1e-3f)
        assertEquals(0.9167f, points.map { it.y }.max(), 1e-3f)
    }

    @Test
    fun `평평한 곡선은 레인 중앙에 그린다`() {
        val points = guideCurveDisplayPoints(listOf(1.5, 1.5, 1.5))
        points.forEach { assertEquals(0.5f, it.y, 1e-5f) }
    }

    @Test
    fun `거의 평평한 곡선의 미세 잡음은 레인 전체로 증폭되지 않는다`() {
        // 부동소수 잡음 수준(1e-9 semitone)의 등락. 자기 스케일만 있으면 이게 전폭으로 튄다 —
        // 표시 범위 바닥값(0.5 semitone)이 잡음을 중앙 부근에 눌러 둔다.
        val points = guideCurveDisplayPoints(listOf(1.0, 1.0 + 1e-9, 1.0))
        points.forEach { assertEquals(0.5f, it.y, 1e-3f) }
    }

    @Test
    fun `유성 프레임이 하나뿐이면 그 시각에 점 하나다`() {
        val points = guideCurveDisplayPoints(listOf(null, 2.0, null))
        assertEquals(1, points.size)
        assertEquals(0.5f, points.single().x, 1e-5f)
        assertEquals(0.5f, points.single().y, 1e-5f) // 값 하나는 range 0 - 중앙
    }

    // --- 발행본 실문항 곡선 (KAN-194) -----------------------------------------
    // 아래 셋은 web `guideCurve.test.ts`의 「발행본 실문항 곡선」과 같은 케이스다. 두 구현이
    // 1:1 포팅이라(웹 `guideCurve.ts` 머리주석), 같은 실데이터에 같은 결과가 나오는지를
    // 여기서 못박는다 - 한쪽만 규칙이 흘러가면 사용자는 웹과 앱에서 다른 곡선을 본다.

    @Test
    fun `실문항은 무성 구간을 보간해 240점을 하나도 버리지 않고 그린다`() {
        val values = GuideF0Fixture.REAL.values
        assertEquals("v102", GuideF0Fixture.ITEM_ID)
        assertEquals(240, values.size)
        assertEquals(14, values.count { it == null })

        // 앞뒤 가장자리가 유성이라 첫 프레임부터 끝 프레임까지 다 들어온다 - 중간 무성은
        // 버려지는 게 아니라 양옆 값의 보간으로 메워진다
        val points = guideCurveDisplayPoints(values)
        assertEquals(240, points.size)
        assertEquals(0f, points.first().x, 1e-6f)
        assertEquals(1f, points.last().x, 1e-6f)
    }

    @Test
    fun `실문항의 보간된 무성 구간은 양옆 유성 값 사이를 고르게 잇는다`() {
        // index 125(-0.48)에서 140(-1.8)으로 내려가는 구간이 통째로 무성이다.
        // 값이 내려가므로 화면에서는 y가 단조 증가하고, 보간이라 간격이 균일하다
        val points = guideCurveDisplayPoints(GuideF0Fixture.REAL.values)
        val hole = points.subList(125, 141)
        val gaps = (0 until hole.size - 1).map { hole[it + 1].y - hole[it].y }
        gaps.forEach {
            assertTrue("무성 구간의 y는 단조 증가해야 한다: $gaps", it > 0f)
            assertEquals(gaps.first(), it, 1e-5f)
        }
    }

    @Test
    fun `실문항 곡선은 여백 안에 들어와 레인 밖으로 넘치지 않는다`() {
        val points = guideCurveDisplayPoints(GuideF0Fixture.REAL.values)
        points.forEach {
            assertTrue("y가 레인을 벗어났다: $it", it.y > 0f && it.y < 1f)
        }
        // 실측 범위는 -5.1 ~ 5.44. 자기 min/max 스케일이라 최고·최저점이 여백 10%만큼
        // 가장자리에서 떨어져 있다 - 장난감 두 점 케이스와 같은 0.0833/0.9167이 나온다.
        assertEquals(0.0833f, points.minOf { it.y }, 1e-3f)
        assertEquals(0.9167f, points.maxOf { it.y }, 1e-3f)
    }
}
