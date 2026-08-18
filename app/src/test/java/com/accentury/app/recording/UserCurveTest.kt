package com.accentury.app.recording

import com.accentury.app.audio.RecordingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class UserCurveTest {

    private fun frame(timestampMs: Long, hz: Float?) = RecordingEngine.PitchFrame(timestampMs, hz)

    @Test
    fun `창 길이는 가이드 전체 길이다 - 간격 곱하기 구간 수`() {
        assertEquals(1000L, userCurveWindowMs(frameIntervalMs = 10, valueCount = 101))
        assertEquals(320L, userCurveWindowMs(frameIntervalMs = 32, valueCount = 11))
    }

    @Test
    fun `가이드를 쓸 수 없으면 창 길이는 1초다`() {
        assertEquals(1000L, userCurveWindowMs(frameIntervalMs = null, valueCount = null))
        assertEquals(1000L, userCurveWindowMs(frameIntervalMs = 10, valueCount = 1))
        assertEquals(1000L, userCurveWindowMs(frameIntervalMs = 10, valueCount = 0))
        assertEquals(1000L, userCurveWindowMs(frameIntervalMs = 0, valueCount = 101))
        assertEquals(1000L, userCurveWindowMs(frameIntervalMs = -5, valueCount = 101))
    }

    @Test
    fun `그릴 프레임이 없으면 빈 목록이다`() {
        assertEquals(emptyList<CurvePoint>(), userCurveDisplayPoints(emptyList(), 1000L))
    }

    @Test
    fun `전부 무성이면 그릴 점이 없다`() {
        val frames = listOf(frame(0, null), frame(32, null), frame(64, null))
        assertEquals(emptyList<CurvePoint>(), userCurveDisplayPoints(frames, 1000L))
    }

    @Test
    fun `무성 프레임은 버리고 유성 프레임만 남는다`() {
        val frames = listOf(frame(0, 100f), frame(500, null), frame(1000, 200f))
        val points = userCurveDisplayPoints(frames, 1000L)
        assertEquals(2, points.size)
        assertEquals(0f, points.first().x, 1e-5f)
        assertEquals(1f, points.last().x, 1e-5f)
    }

    @Test
    fun `창이 차기 전에는 왼쪽부터 자란다`() {
        val frames = listOf(frame(0, 200f), frame(500, 200f))
        val points = userCurveDisplayPoints(frames, 1000L)
        // 최신이 500ms라도 창 시작은 0이다 - 곡선이 절반까지만 그려진다
        assertEquals(0f, points[0].x, 1e-5f)
        assertEquals(0.5f, points[1].x, 1e-5f)
    }

    @Test
    fun `최신 프레임이 창 길이에 딱 닿으면 오른쪽 끝이다`() {
        val points = userCurveDisplayPoints(listOf(frame(0, 200f), frame(1000, 200f)), 1000L)
        assertEquals(0f, points.first().x, 1e-5f)
        assertEquals(1f, points.last().x, 1e-5f)
    }

    @Test
    fun `창 길이를 넘기면 창이 미끄러지고 밀려난 프레임은 버린다`() {
        val frames = listOf(
            frame(0, 200f),
            frame(400, 200f),
            frame(500, 200f),
            frame(1000, 200f),
            frame(1500, 200f),
        )
        val points = userCurveDisplayPoints(frames, 1000L)
        // 최신 1500ms - 창 1000ms = 창 시작 500ms. 500ms 이전 두 프레임은 사라진다.
        assertEquals(3, points.size)
        assertEquals(0f, points[0].x, 1e-5f)
        assertEquals(0.5f, points[1].x, 1e-5f)
        assertEquals(1f, points[2].x, 1e-5f)
    }

    @Test
    fun `y축은 80에서 400Hz 고정이다 - 밴드 끝이 레인 끝이다`() {
        val points = userCurveDisplayPoints(listOf(frame(0, 80f), frame(1000, 400f)), 1000L)
        assertEquals(1f, points.first().y, 1e-5f)
        assertEquals(0f, points.last().y, 1e-5f)
    }

    @Test
    fun `y축은 로그 스케일이다 - 레인 중앙은 산술 중간이 아니라 기하 평균이다`() {
        val geometricMean = sqrt(80.0 * 400.0).toFloat() // 약 178.9Hz
        val points = userCurveDisplayPoints(
            listOf(frame(0, geometricMean), frame(500, 240f)),
            1000L,
        )
        assertEquals(0.5f, points[0].y, 1e-5f)
        // 산술 중간(240Hz)은 중앙보다 위(y가 더 작다)에 놓인다
        assertTrue("240Hz가 중앙 위여야 한다: $points", points[1].y < 0.5f)
    }

    @Test
    fun `높은 음이 위로 간다 - Hz가 클수록 y가 작다`() {
        val frames = listOf(frame(0, 100f), frame(200, 150f), frame(400, 300f))
        val points = userCurveDisplayPoints(frames, 1000L)
        for (k in 0 until points.size - 1) {
            assertTrue("y는 단조 감소해야 한다: $points", points[k].y > points[k + 1].y)
        }
    }

    @Test
    fun `밴드를 벗어난 값은 레인 안으로 눌러 담는다`() {
        val points = userCurveDisplayPoints(listOf(frame(0, 40f), frame(500, 800f)), 1000L)
        assertEquals(1f, points[0].y, 1e-5f)
        assertEquals(0f, points[1].y, 1e-5f)
    }

    @Test
    fun `창 길이가 0 이하면 그리지 않는다`() {
        assertEquals(emptyList<CurvePoint>(), userCurveDisplayPoints(listOf(frame(0, 200f)), 0L))
    }
}
