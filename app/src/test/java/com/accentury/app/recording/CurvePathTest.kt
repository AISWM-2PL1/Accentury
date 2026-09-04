package com.accentury.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurvePathTest {

    private val width = 100f
    private val height = 40f

    /** x는 고르게, y는 오르내리게 - 중간점이 원래 점과 겹치지 않아야 검사가 의미를 갖는다. */
    private fun points(n: Int): List<CurvePoint> =
        List(n) { i -> CurvePoint(x = i / 10f, y = if (i % 2 == 0) 0.2f else 0.8f) }

    private fun commands(n: Int) = smoothPathCommands(points(n), width, height)

    private fun px(i: Int) = points(20)[i].x * width
    private fun py(i: Int) = points(20)[i].y * height

    @Test
    fun `점이 2개면 중간점을 거치는 직선 두 도막이다`() {
        val commands = commands(2)

        assertEquals(
            listOf(
                PathCommand.MoveTo(px(0), py(0)),
                PathCommand.LineTo((px(0) + px(1)) / 2f, (py(0) + py(1)) / 2f),
                PathCommand.LineTo(px(1), py(1)),
            ),
            commands,
        )
    }

    @Test
    fun `점이 2개 미만이면 명령이 없다 - 원 그리기는 CurveLane이 한다`() {
        assertEquals(emptyList<PathCommand>(), smoothPathCommands(emptyList(), width, height))
        assertEquals(emptyList<PathCommand>(), smoothPathCommands(points(1), width, height))
    }

    @Test
    fun `점이 붙어도 이미 그린 곡선은 다시 계산되지 않는다 - 인과성`() {
        // n개 명령에서 꼬리 LineTo 하나를 뺀 나머지 == n+1개 명령의 접두사.
        // 다시 그려지는 곳은 마지막 반 구간(직전 중간점 -> 마지막 점, 16ms)뿐이다.
        for (n in 2..8) {
            val settled = commands(n).dropLast(1)
            val next = commands(n + 1)

            assertTrue(
                "n=$n: 명령이 줄었다 (settled=${settled.size}, next=${next.size})",
                settled.size <= next.size,
            )
            assertEquals("n=$n 에서 이미 그린 구간이 바뀌었다", settled, next.take(settled.size))
        }
    }

    @Test
    fun `점 하나가 늘 때 명령도 하나만 는다`() {
        // 접두사만 보면 "새 점이 아무것도 안 그렸다"도 통과한다 - 자라기는 자라야 한다.
        for (n in 2..8) {
            assertEquals("n=$n", commands(n).size + 1, commands(n + 1).size)
        }
    }

    @Test
    fun `모든 QuadTo는 제어점이 원래 점이고 끝점이 이웃과의 중간점이다`() {
        val n = 6
        val commands = commands(n)
        val quads = commands.filterIsInstance<PathCommand.QuadTo>()

        // i = 1..n-2 각각 하나씩.
        assertEquals(n - 2, quads.size)
        quads.forEachIndexed { index, quad ->
            val i = index + 1
            assertEquals("제어점 x (i=$i)", px(i), quad.cx, 0f)
            assertEquals("제어점 y (i=$i)", py(i), quad.cy, 0f)
            assertEquals("끝점 x (i=$i)", (px(i) + px(i + 1)) / 2f, quad.x, 0f)
            assertEquals("끝점 y (i=$i)", (py(i) + py(i + 1)) / 2f, quad.y, 0f)
        }
        // 곡선은 첫 중간점에서 시작해 마지막 점으로 닫힌다.
        assertEquals(PathCommand.MoveTo(px(0), py(0)), commands.first())
        assertEquals(PathCommand.LineTo((px(0) + px(1)) / 2f, (py(0) + py(1)) / 2f), commands[1])
        assertEquals(PathCommand.LineTo(px(n - 1), py(n - 1)), commands.last())
    }

    @Test
    fun `비율 좌표에 캔버스 크기를 곱한다`() {
        val scaled = smoothPathCommands(points(3), width * 2f, height * 2f)

        assertEquals(PathCommand.MoveTo(px(0) * 2f, py(0) * 2f), scaled.first())
        assertEquals(PathCommand.LineTo(px(2) * 2f, py(2) * 2f), scaled.last())
    }
}
