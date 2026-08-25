package com.accentury.app.recording

import com.accentury.app.audio.RecordingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class UserCurveTest {

    private fun frame(timestampMs: Long, hz: Float?) = RecordingEngine.PitchFrame(timestampMs, hz)

    /** 실제 엔진과 같은 32ms 간격으로 프레임을 만든다. null은 무성 프레임이다. */
    private fun frames(vararg hz: Float?, startMs: Long = 0L): List<RecordingEngine.PitchFrame> =
        hz.mapIndexed { i, v -> frame(startMs + i * FRAME_MS, v) }

    /** 중심 잠금에 필요한 최소 유성 프레임. 전부 같은 값이라 중심이 곧 [hz]다. */
    private fun centerFrames(hz: Float = CENTER_HZ): List<RecordingEngine.PitchFrame> =
        List(CENTER_MIN_VOICED_FRAMES) { frame(it * FRAME_MS, hz) }

    /** 중심에서 [st] semitone 떨어진 Hz */
    private fun semitone(st: Double, center: Float = CENTER_HZ): Float =
        (center * 2.0.pow(st / 12.0)).toFloat()

    /** 중심 프레임 다음에 오는 프레임의 시각 */
    private fun after(gapMs: Long): Long = (CENTER_MIN_VOICED_FRAMES - 1) * FRAME_MS + gapMs

    // --- 창 길이 -------------------------------------------------------------

    @Test
    fun `가이드 길이는 간격 곱하기 구간 수고 알 수 없으면 0이다`() {
        assertEquals(1000L, guideDurationMs(frameIntervalMs = 10, valueCount = 101))
        assertEquals(320L, guideDurationMs(frameIntervalMs = 32, valueCount = 11))
        assertEquals(0L, guideDurationMs(frameIntervalMs = null, valueCount = null))
        assertEquals(0L, guideDurationMs(frameIntervalMs = 10, valueCount = 1))
        assertEquals(0L, guideDurationMs(frameIntervalMs = 0, valueCount = 101))
    }

    @Test
    fun `창 길이는 가이드 길이의 두 배다`() {
        assertEquals(2000L, userCurveWindowMs(frameIntervalMs = 10, valueCount = 101))
        assertEquals(640L, userCurveWindowMs(frameIntervalMs = 32, valueCount = 11))
    }

    @Test
    fun `가이드를 쓸 수 없으면 창 길이는 폴백 1초의 두 배다`() {
        assertEquals(2000L, userCurveWindowMs(frameIntervalMs = null, valueCount = null))
        assertEquals(2000L, userCurveWindowMs(frameIntervalMs = 10, valueCount = 1))
        assertEquals(2000L, userCurveWindowMs(frameIntervalMs = 10, valueCount = 0))
        assertEquals(2000L, userCurveWindowMs(frameIntervalMs = 0, valueCount = 101))
        assertEquals(2000L, userCurveWindowMs(frameIntervalMs = -5, valueCount = 101))
    }

    @Test
    fun `Review 창은 라이브 창보다 긴 녹음을 통째로 담는다`() {
        // 3.168초짜리 녹음이면 2초 라이브 창으로는 앞부분이 잘린다
        val long = List(100) { frame(it * FRAME_MS, CENTER_HZ) }
        val lastMs = 99 * FRAME_MS
        assertTrue("전제: 녹음이 라이브 창보다 길다", lastMs > WINDOW_MS)
        assertEquals(lastMs + FRAME_MS, reviewWindowMs(long, WINDOW_MS))
    }

    @Test
    fun `라이브 창 안에 들어오는 녹음이면 Review도 라이브 창을 쓴다`() {
        // 창을 녹음 길이에 맞춰 줄이면 짧은 발화가 레인 폭을 억지로 채워 늘어져 보인다
        assertEquals(WINDOW_MS, reviewWindowMs(centerFrames(), WINDOW_MS))
    }

    @Test
    fun `프레임이 없으면 Review 창은 라이브 창 그대로다`() {
        assertEquals(WINDOW_MS, reviewWindowMs(emptyList(), WINDOW_MS))
    }

    @Test
    fun `Review 창으로 그리면 첫 프레임부터 마지막 프레임까지 다 들어온다`() {
        val total = 100
        val long = List(total) { frame(it * FRAME_MS, CENTER_HZ) }
        val windowMs = reviewWindowMs(long, WINDOW_MS)
        val points = userCurveDisplayPoints(long, windowMs).single()

        assertEquals(total, points.size)
        assertEquals(
            "첫 점은 첫 프레임 시각 자리다",
            long.first().timestampMs.toFloat() / windowMs,
            points.first().x,
            1e-5f,
        )
        assertTrue("마지막 점은 오른쪽 모서리에 붙지 않는다: ${points.last().x}", points.last().x < 1f)
    }

    // --- 가이드를 사용자 창에 맞추기 -----------------------------------------

    @Test
    fun `가이드 x는 창 비율만큼 줄고 y는 그대로다`() {
        val points = listOf(CurvePoint(0f, 0.2f), CurvePoint(0.5f, 0.8f), CurvePoint(1f, 0.4f))
        val aligned = alignGuideToWindow(points, guideMs = 1000L, windowMs = 2000L)

        assertEquals(listOf(0f, 0.25f, 0.5f), aligned.map { it.x })
        assertEquals(points.map { it.y }, aligned.map { it.y })
    }

    @Test
    fun `가이드가 창을 다 채우면 좌표가 그대로다`() {
        val points = listOf(CurvePoint(0f, 0.2f), CurvePoint(1f, 0.4f))
        assertEquals(points, alignGuideToWindow(points, guideMs = 900L, windowMs = 900L))
    }

    @Test
    fun `길이를 알 수 없으면 가이드 좌표를 그대로 둔다`() {
        val points = listOf(CurvePoint(0f, 0.2f), CurvePoint(1f, 0.4f))
        assertEquals(points, alignGuideToWindow(points, guideMs = 0L, windowMs = 2000L))
        assertEquals(points, alignGuideToWindow(points, guideMs = -1L, windowMs = 2000L))
        assertEquals(points, alignGuideToWindow(points, guideMs = 1000L, windowMs = 0L))
        assertEquals(points, alignGuideToWindow(points, guideMs = 1000L, windowMs = -1L))
    }

    @Test
    fun `가이드 점이 없으면 빈 목록이다`() {
        assertEquals(emptyList<CurvePoint>(), alignGuideToWindow(emptyList(), 1000L, 2000L))
    }

    // --- 그릴 게 없는 경우 ---------------------------------------------------

    @Test
    fun `그릴 프레임이 없으면 빈 목록이다`() {
        assertEquals(emptyList<List<CurvePoint>>(), userCurveDisplayPoints(emptyList(), WINDOW_MS))
    }

    @Test
    fun `전부 무성이면 그릴 점이 없다`() {
        val frames = frames(null, null, null)
        assertEquals(emptyList<List<CurvePoint>>(), userCurveDisplayPoints(frames, WINDOW_MS))
    }

    @Test
    fun `창 길이가 0 이하면 그리지 않는다`() {
        assertEquals(emptyList<List<CurvePoint>>(), userCurveDisplayPoints(centerFrames(), 0L))
    }

    // --- 중심 잠금 -----------------------------------------------------------

    @Test
    fun `유성 프레임이 모자라면 축이 없어 그리지 않는다`() {
        val notEnough = List(CENTER_MIN_VOICED_FRAMES - 1) { frame(it * FRAME_MS, CENTER_HZ) }
        assertNull(userCurveCenterHz(notEnough))
        assertEquals(emptyList<List<CurvePoint>>(), userCurveDisplayPoints(notEnough, WINDOW_MS))
    }

    @Test
    fun `유성 프레임이 채워지는 순간부터 그려진다`() {
        val enough = centerFrames()
        assertEquals(CENTER_HZ, userCurveCenterHz(enough)!!, 1e-3f)
        val segments = userCurveDisplayPoints(enough, WINDOW_MS)
        assertEquals(1, segments.size)
        assertEquals(CENTER_MIN_VOICED_FRAMES, segments.single().size)
    }

    @Test
    fun `중심은 처음 여덟 프레임으로 잠긴다 - 뒤에 뭐가 와도 안 변한다`() {
        val locked = userCurveCenterHz(centerFrames())!!
        val more = centerFrames() + frames(400f, 400f, 400f, 400f, startMs = 8 * FRAME_MS)
        assertEquals(locked, userCurveCenterHz(more)!!, 1e-3f)
    }

    @Test
    fun `중앙값이라 옥타브 오류 한 프레임에 중심이 안 밀린다`() {
        // 여덟 중 하나가 두 배로 튄 경우 - 평균이면 12퍼센트 넘게 밀리지만 중앙값은 그대로다
        val withOctaveError = frames(
            CENTER_HZ, CENTER_HZ, CENTER_HZ, CENTER_HZ * 2,
            CENTER_HZ, CENTER_HZ, CENTER_HZ, CENTER_HZ,
        )
        assertEquals(CENTER_HZ, userCurveCenterHz(withOctaveError)!!, 1e-3f)
    }

    @Test
    fun `무성 프레임은 중심 계산에서 세지 않는다`() {
        val sparse = frames(
            CENTER_HZ, null, CENTER_HZ, null, CENTER_HZ, null, CENTER_HZ, null,
            CENTER_HZ, null, CENTER_HZ, null, CENTER_HZ, null, CENTER_HZ,
        )
        assertEquals(CENTER_HZ, userCurveCenterHz(sparse)!!, 1e-3f)
    }

    // --- y축 스케일 ----------------------------------------------------------

    @Test
    fun `중심 음높이는 레인 한가운데다`() {
        val segments = userCurveDisplayPoints(centerFrames(), WINDOW_MS)
        segments.single().forEach { assertEquals(0.5f, it.y, 1e-4f) }
    }

    @Test
    fun `중심에서 위아래 7 semitone이 레인 끝이다`() {
        // 긴 구멍 뒤에 두어 EMA가 초기화되게 한다 - 스무딩이 섞이지 않은 순수 좌표를 본다
        val up = centerFrames() + listOf(frame(after(LONG_GAP_MS), semitone(7.0)))
        assertEquals(0f, userCurveDisplayPoints(up, WINDOW_MS).last().single().y, 1e-4f)

        val down = centerFrames() + listOf(frame(after(LONG_GAP_MS), semitone(-7.0)))
        assertEquals(1f, userCurveDisplayPoints(down, WINDOW_MS).last().single().y, 1e-4f)
    }

    @Test
    fun `창을 벗어난 값은 레인 안으로 눌러 담는다`() {
        val up = centerFrames() + listOf(frame(after(LONG_GAP_MS), semitone(20.0)))
        assertEquals(0f, userCurveDisplayPoints(up, WINDOW_MS).last().single().y, 1e-4f)

        val down = centerFrames() + listOf(frame(after(LONG_GAP_MS), semitone(-20.0)))
        assertEquals(1f, userCurveDisplayPoints(down, WINDOW_MS).last().single().y, 1e-4f)
    }

    @Test
    fun `높은 음이 위로 간다 - Hz가 클수록 y가 작다`() {
        val rising = centerFrames() + frames(
            semitone(1.0), semitone(3.0), semitone(6.0),
            startMs = CENTER_MIN_VOICED_FRAMES * FRAME_MS,
        )
        val points = userCurveDisplayPoints(rising, WINDOW_MS).single()
        val tail = points.takeLast(3)
        for (k in 0 until tail.size - 1) {
            assertTrue("y는 단조 감소해야 한다: $tail", tail[k].y > tail[k + 1].y)
        }
    }

    @Test
    fun `centerHz를 주면 자동 계산을 쓰지 않는다`() {
        // 유성 프레임이 셋뿐이라 자동 계산은 null인데, 중심을 받았으니 그려진다
        val short = frames(CENTER_HZ, CENTER_HZ, CENTER_HZ)
        assertNull(userCurveCenterHz(short))
        val segments = userCurveDisplayPoints(short, WINDOW_MS, centerHz = CENTER_HZ)
        assertEquals(3, segments.single().size)
        segments.single().forEach { assertEquals(0.5f, it.y, 1e-4f) }

        // 중심을 위로 올려 주면 같은 프레임이 레인 아래쪽에 놓인다
        val higherCenter = userCurveDisplayPoints(short, WINDOW_MS, centerHz = semitone(7.0))
        assertEquals(1f, higherCenter.single().first().y, 1e-4f)
    }

    // --- EMA 스무딩 ----------------------------------------------------------

    @Test
    fun `EMA는 튀는 한 프레임을 알파배로 눌러 준다`() {
        val spike = centerFrames() + listOf(frame(after(FRAME_MS), semitone(7.0)))
        val points = userCurveDisplayPoints(spike, WINDOW_MS).single()
        // 스무딩이 없었다면 y=0(레인 끝)이라 중앙에서 0.5만큼 움직였을 값이다
        val displacement = 0.5f - points.last().y
        assertEquals(0.5f * USER_CURVE_EMA_ALPHA, displacement, 1e-3f)
    }

    @Test
    fun `선분 첫 프레임은 지연 없이 제 값 그대로다`() {
        // 첫 프레임부터 중심에서 떨어져 있어도 0에서 끌려오지 않는다
        val offset = List(CENTER_MIN_VOICED_FRAMES) { frame(it * FRAME_MS, semitone(3.5)) }
        // 중심이 곧 이 값이므로 자동 계산에서는 항상 0.5다 - 중심을 명시해 상대 위치를 본다
        val points = userCurveDisplayPoints(offset, WINDOW_MS, centerHz = CENTER_HZ).single()
        assertEquals(0.5f - 3.5f / USER_CURVE_SPAN_SEMITONE.toFloat(), points.first().y, 1e-3f)
    }

    // --- 무성 구간 -----------------------------------------------------------

    @Test
    fun `짧은 구멍은 직전 값을 유지해 선이 이어진다`() {
        val gapMs = HOLD_MAX_GAP_MS // 경계값 포함
        val withHole = centerFrames() +
            listOf(frame(after(FRAME_MS), null), frame(after(gapMs), semitone(2.0)))
        val segments = userCurveDisplayPoints(withHole, WINDOW_MS)
        assertEquals("구멍이 짧으면 선분이 갈라지지 않는다", 1, segments.size)

        val points = segments.single()
        // 구멍 자리에도 점이 있다 - 프레임 수만큼 점이 나온다
        assertEquals(CENTER_MIN_VOICED_FRAMES + 2, points.size)
        val held = points[CENTER_MIN_VOICED_FRAMES]
        assertEquals("유지 값은 직전 점과 같다", points[CENTER_MIN_VOICED_FRAMES - 1].y, held.y, 1e-6f)
    }

    @Test
    fun `긴 구멍은 선을 끊고 EMA를 초기화한다`() {
        val withPause = centerFrames() + listOf(frame(after(LONG_GAP_MS), semitone(7.0)))
        val segments = userCurveDisplayPoints(withPause, WINDOW_MS)
        assertEquals(2, segments.size)
        // 새 선분 첫 점은 직전 선분의 값(중앙 0.5)에 끌리지 않고 제 값(레인 끝)에서 시작한다
        assertEquals(0f, segments[1].single().y, 1e-4f)
    }

    @Test
    fun `유지는 직전 유성 프레임 기준이라 구멍이 길어지면 멈춘다`() {
        // 무성이 계속되면 HOLD_MAX_GAP_MS를 넘는 순간부터는 점을 두지 않는다
        val longHole = centerFrames() + frames(
            null, null, null, null, null, null, null, null, null, null,
            startMs = CENTER_MIN_VOICED_FRAMES * FRAME_MS,
        )
        val points = userCurveDisplayPoints(longHole, WINDOW_MS).single()
        val heldCount = points.size - CENTER_MIN_VOICED_FRAMES
        // 마지막 유성 시각에서 32ms씩 떨어진 프레임 중 250ms 이하인 일곱(32~224ms)만 유지되고,
        // 256ms부터는 끊긴다
        assertEquals((HOLD_MAX_GAP_MS / FRAME_MS).toInt(), heldCount)
        assertEquals(7, heldCount)
    }

    // --- 시간 가중 EMA -------------------------------------------------------

    @Test
    fun `구멍이 길수록 옛 값의 몫이 줄어든다`() {
        // 100ms 구멍은 프레임 3개어치라 0.7^3 = 34%가 남는다
        assertEquals(0.343, residualAfterGap(100L), 0.005)
        // 250ms(유지 한계)는 프레임 8개어치라 0.7^8 = 6%다 - 옛 값에 끌려가지 않는다
        assertEquals(0.058, residualAfterGap(250L), 0.005)
    }

    @Test
    fun `유지 한계 안의 구멍은 선분을 가르지 않는다`() {
        val segments = userCurveDisplayPoints(gapThenJump(250L), WINDOW_MS)
        assertEquals("250ms는 HOLD_MAX_GAP_MS 이하라 한 선분이다", 1, segments.size)
    }

    @Test
    fun `연속 프레임의 EMA는 시간 가중 전후가 같다`() {
        // gapFrames=1이면 retain=0.7이라 `직전*0.7 + 현재*0.3`과 정확히 같다
        assertEquals(1.0 - USER_CURVE_EMA_ALPHA, residualAfterGap(FRAME_MS), 1e-3)
    }

    @Test
    fun `유지 한계를 넘는 구멍은 선을 끊고 새 값 그대로 시작한다`() {
        val jumpSt = 3.5
        val over = centerFrames() + listOf(frame(after(300L), semitone(jumpSt)))
        val segments = userCurveDisplayPoints(over, WINDOW_MS)
        assertEquals("300ms는 HOLD_MAX_GAP_MS를 넘어 선분이 갈린다", 2, segments.size)
        val expectedY = 0.5f - (jumpSt / USER_CURVE_SPAN_SEMITONE).toFloat()
        assertEquals(expectedY, segments[1].single().y, 1e-4f)
    }

    // --- 실시간성 ------------------------------------------------------------

    @Test
    fun `프레임이 더 쌓여도 이미 그린 점은 그대로다`() {
        val all = centerFrames() + frames(
            semitone(1.0), semitone(4.0), semitone(-2.0), semitone(5.0),
            semitone(2.0), semitone(-3.0), semitone(6.0), semitone(0.0),
            startMs = CENTER_MIN_VOICED_FRAMES * FRAME_MS,
        )
        val earlier = userCurveDisplayPoints(all.take(12), WINDOW_MS).single()
        val later = userCurveDisplayPoints(all, WINDOW_MS).single()
        assertTrue(later.size > earlier.size)
        earlier.forEachIndexed { i, p ->
            assertEquals("점 $i 의 x가 변했다", p.x, later[i].x, 1e-6f)
            assertEquals("점 $i 의 y가 변했다", p.y, later[i].y, 1e-6f)
        }
    }

    @Test
    fun `창이 차기 전에는 왼쪽부터 자란다`() {
        // 최신이 창의 절반쯤이면 곡선도 절반까지만 그려진다
        val points = userCurveDisplayPoints(centerFrames(), WINDOW_MS).single()
        assertEquals(0f, points.first().x, 1e-5f)
        val lastMs = (CENTER_MIN_VOICED_FRAMES - 1) * FRAME_MS
        assertEquals(lastMs.toFloat() / WINDOW_MS, points.last().x, 1e-5f)
    }

    @Test
    fun `창 길이를 넘기면 창이 미끄러지고 밀려난 프레임은 버린다`() {
        val total = 40
        val long = List(total) { frame(it * FRAME_MS, CENTER_HZ) }
        val windowMs = 1000L
        val points = userCurveDisplayPoints(long, windowMs).single()

        val newestMs = (total - 1) * FRAME_MS
        val windowStartMs = newestMs - windowMs
        val expected = long.count { it.timestampMs >= windowStartMs }
        assertEquals(expected, points.size)
        assertTrue("가장 오래된 점은 창 왼쪽에 붙는다: ${points.first()}", points.first().x < 0.05f)
        assertEquals("최신 점은 오른쪽 끝이다", 1f, points.last().x, 1e-5f)
    }

    // --- Review 구멍 보간 ----------------------------------------------------

    @Test
    fun `짧은 구멍은 semitone 선형으로 메워진다`() {
        val filled = fillShortGaps(HOLE_192MS)

        assertEquals("개수가 보존된다", HOLE_192MS.size, filled.size)
        assertEquals(
            "시각과 순서가 보존된다",
            HOLE_192MS.map { it.timestampMs },
            filled.map { it.timestampMs },
        )
        // 양 끝 유성 값은 그대로다
        assertEquals(200f, filled[1].pitchHz!!, 1e-3f)
        assertEquals(800f, filled[7].pitchHz!!, 1e-3f)
        // 구멍 한가운데(t=128, 비율 0.5)는 산술평균 500이 아니라 기하평균 400이다
        assertEquals(400f, filled[4].pitchHz!!, 1e-2f)
        // 나머지 구멍 자리도 전부 채워졌고, 단조 증가한다
        val inside = (2..6).map { filled[it].pitchHz!! }
        assertTrue("구멍이 다 채워져야 한다: $inside", inside.all { it > 0f })
        for (k in 0 until inside.size - 1) {
            assertTrue("보간값은 단조 증가한다: $inside", inside[k] < inside[k + 1])
        }
    }

    @Test
    fun `앞뒤 가장자리 구멍은 그대로 둔다`() {
        val filled = fillShortGaps(HOLE_192MS)
        assertNull("녹음 시작 전 무성", filled.first().pitchHz)
        assertNull("녹음이 끝난 뒤 무성", filled.last().pitchHz)
    }

    @Test
    fun `긴 구멍은 진짜 쉼이라 메우지 않는다`() {
        // 유성 두 개 사이가 608ms라 REVIEW_FILL_MAX_GAP_MS(500)를 넘는다
        val hole = listOf(frame(0L, 200f)) +
            List(18) { frame((it + 1) * FRAME_MS, null) } +
            listOf(frame(19 * FRAME_MS, 800f))
        val filled = fillShortGaps(hole)

        assertEquals(hole.size, filled.size)
        assertEquals(hole, filled)
        assertTrue("구멍이 그대로 null이다", filled.subList(1, 19).all { it.pitchHz == null })
    }

    @Test
    fun `메울 짝이 없으면 원본 그대로다`() {
        assertEquals(emptyList<RecordingEngine.PitchFrame>(), fillShortGaps(emptyList()))
        val allUnvoiced = frames(null, null, null)
        assertEquals(allUnvoiced, fillShortGaps(allUnvoiced))
        val onlyOneVoiced = frames(null, 200f, null)
        assertEquals(onlyOneVoiced, fillShortGaps(onlyOneVoiced))
    }

    @Test
    fun `메운 프레임은 곡선을 한 선분으로 잇는다`() {
        // 608ms 구멍은 실시간 곡선이라면 선분을 가르지만, 한계를 늘려 메우면 한 선분이 된다
        val hole = centerFrames() +
            List(18) { frame(after((it + 1) * FRAME_MS), null) } +
            listOf(frame(after(19 * FRAME_MS), semitone(3.0)))
        assertEquals(2, userCurveDisplayPoints(hole, WINDOW_MS).size)
        val filled = fillShortGaps(hole, maxGapMs = 1000L)
        assertEquals(1, userCurveDisplayPoints(filled, WINDOW_MS).size)
    }

    /** 중심 프레임 뒤 [gapMs] 만큼 떨어진 곳에 7 semitone 점프를 두었을 때, 남은 옛 값의 비율 */
    private fun residualAfterGap(gapMs: Long): Double {
        val jumpSt = 7.0
        val points = userCurveDisplayPoints(gapThenJump(gapMs), WINDOW_MS).last()
        val onScreenSt = (0.5 - points.last().y) * USER_CURVE_SPAN_SEMITONE
        // 옛 값이 0 semitone(중심)이었으므로, 목표에 못 미친 몫이 곧 옛 값의 잔존 비율이다
        return (jumpSt - onScreenSt) / jumpSt
    }

    private fun gapThenJump(gapMs: Long): List<RecordingEngine.PitchFrame> =
        centerFrames() + listOf(frame(after(gapMs), semitone(7.0)))

    private companion object {
        const val FRAME_MS = 32L

        /**
         * 앞뒤 가장자리가 무성이고, 32ms(200Hz)와 224ms(800Hz) 사이에 192ms짜리 구멍이 있다.
         * 두 옥타브 차이라 한가운데의 기하평균이 400Hz로 딱 떨어진다.
         */
        val HOLE_192MS = listOf(
            RecordingEngine.PitchFrame(0L, null),
            RecordingEngine.PitchFrame(32L, 200f),
            RecordingEngine.PitchFrame(64L, null),
            RecordingEngine.PitchFrame(96L, null),
            RecordingEngine.PitchFrame(128L, null),
            RecordingEngine.PitchFrame(160L, null),
            RecordingEngine.PitchFrame(192L, null),
            RecordingEngine.PitchFrame(224L, 800f),
            RecordingEngine.PitchFrame(256L, null),
        )

        const val CENTER_HZ = 200f
        const val WINDOW_MS = 2000L
        const val LONG_GAP_MS = 500L
    }
}
