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

    // KAN-218 - 천장·바닥 인셋. 웹 CurveLane.test.tsx의 인셋 케이스와 같은 값이다.

    @Test
    fun `천장과 바닥은 선 굵기 절반만큼 안으로 들어온다 - 가운데는 그대로`() {
        val stroke = 3f
        val inset = insetY(
            listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f)),
            strokeWidthPx = stroke,
            heightPx = height,
        )

        // 픽셀로 옮겨 보면 y=0 → 1.5, y=0.5 → 20(불변), y=1 → 38.5다.
        assertEquals(stroke / 2f, inset[0].y * height, 1e-4f)
        assertEquals(height / 2f, inset[1].y * height, 1e-4f)
        assertEquals(height - stroke / 2f, inset[2].y * height, 1e-4f)
    }

    @Test
    fun `고립점은 굵기의 2배를 넘겨 반지름만큼 들인다 - 경계 점의 중심이 반지름 자리에 온다`() {
        // CurveLane.kt가 점에 넘기는 값은 2·stroke다. 굵기 3 → 천장 점의 중심 y_px = 3, 바닥은 height − 3.
        val stroke = 3f
        val top = insetY(listOf(CurvePoint(0.5f, 0f)), strokeWidthPx = 2f * stroke, heightPx = height).single()
        val bottom = insetY(listOf(CurvePoint(0.5f, 1f)), strokeWidthPx = 2f * stroke, heightPx = height).single()

        assertEquals(stroke, top.y * height, 1e-4f)
        assertEquals(height - stroke, bottom.y * height, 1e-4f)
    }

    @Test
    fun `인셋은 x를 건드리지 않는다`() {
        val original = points(6)
        val inset = insetY(original, strokeWidthPx = 2f, heightPx = height)

        assertEquals(original.size, inset.size)
        assertEquals(original.map { it.x }, inset.map { it.x })
    }

    @Test
    fun `인셋한 점을 그대로 명령으로 옮기면 천장 선의 중심이 캔버스 안에 놓인다`() {
        // 웹의 실제 호출 순서(insetY → smoothPathCommands)를 그대로 따라간다.
        val stroke = 3f
        val ceiling = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 0f))
        val commands = smoothPathCommands(insetY(ceiling, stroke, height), width, height)

        val first = commands.first() as PathCommand.MoveTo
        val last = commands.last() as PathCommand.LineTo
        assertEquals(0f, first.x, 0f)
        assertEquals(stroke / 2f, first.y, 1e-4f)
        assertEquals(width, last.x, 0f)
        assertEquals(stroke / 2f, last.y, 1e-4f)
    }
}
