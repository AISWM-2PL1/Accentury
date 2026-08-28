package com.accentury.app.recording

import com.accentury.app.audio.RecordingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 8초 경고의 경계 (KAN-161 4단계). 화면에서는 "경고가 안 떴다"나 "3초 남았다고 나온다"로만
 * 드러나는데, 그 증상을 만드는 것은 부등호 하나와 반올림 방향 하나다 — 사람이 확인하려면
 * 매번 스톱워치를 들고 녹음해야 하므로 경계를 여기서 고정한다.
 *
 * 웹 `WebVoiceRecorder.tsx`가 같은 규칙을 쓴다. 값이 갈리면 한 테스트 안에서 번갈아 나오는
 * 두 화면이 서로 다른 순간에 경고를 띄운다.
 */
class RecordingCountdownTest {

    private val max = RecordingEngine.MAX_DURATION_MS // 10_000

    @Test
    fun `8초에 닿는 순간부터 경고다 - 그 직전은 아니다`() {
        assertFalse(isCountdownWarning(elapsedMs = 7_999, maxDurationMs = max))
        assertTrue(isCountdownWarning(elapsedMs = 8_000, maxDurationMs = max))
        assertTrue(isCountdownWarning(elapsedMs = 8_001, maxDurationMs = max))
    }

    @Test
    fun `녹음 초반은 경고 구간이 아니다`() {
        assertFalse(isCountdownWarning(elapsedMs = 0, maxDurationMs = max))
        assertFalse(isCountdownWarning(elapsedMs = 4_000, maxDurationMs = max))
    }

    @Test
    fun `상한에 닿거나 넘겨도 경고다 - 남은 시간은 음수로 내려가지 않는다`() {
        assertTrue(isCountdownWarning(elapsedMs = 10_000, maxDurationMs = max))
        assertTrue(isCountdownWarning(elapsedMs = 12_000, maxDurationMs = max))
        assertEquals(0L, remainingMs(elapsedMs = 12_000, maxDurationMs = max))
    }

    @Test
    fun `상한이 달라지면 경고도 따라 움직인다 - 비율이 아니라 남은 시간이 기준이다`() {
        // 상한 5초짜리 문항: 3초에 경고가 시작한다. 비율(80%)이었다면 4초였다
        assertFalse(isCountdownWarning(elapsedMs = 2_999, maxDurationMs = 5_000))
        assertTrue(isCountdownWarning(elapsedMs = 3_000, maxDurationMs = 5_000))
    }

    @Test
    fun `캡슐의 초는 올림이다 - 남은 시간을 실제보다 짧게 말하지 않는다`() {
        assertEquals(2, remainingSeconds(elapsedMs = 8_000, maxDurationMs = max))
        // 1.999초 남았는데 1초라고 적으면 사용자가 1초 뒤를 끝으로 잡는다
        assertEquals(2, remainingSeconds(elapsedMs = 8_001, maxDurationMs = max))
        assertEquals(1, remainingSeconds(elapsedMs = 9_000, maxDurationMs = max))
        assertEquals(1, remainingSeconds(elapsedMs = 9_999, maxDurationMs = max))
        // 상한에 정확히 닿으면 0이다. 그 순간 엔진이 스스로 멈춘다
        assertEquals(0, remainingSeconds(elapsedMs = 10_000, maxDurationMs = max))
    }

    @Test
    fun `상태의 countdownActive가 같은 판정을 쓴다`() {
        assertFalse(RecordingUiState.Recording(elapsedMs = 7_999, rms = 0.0).countdownActive)
        assertTrue(RecordingUiState.Recording(elapsedMs = 8_000, rms = 0.0).countdownActive)
    }

    @Test
    fun `경과 표기는 시계꼴이고 버림이다`() {
        assertEquals("00:00", formatElapsed(0))
        // 0.9초에서 00:01이 뜨면 아직 1초가 안 됐는데 1초로 보인다 (품질 게이트가 1초 미만을 거절)
        assertEquals("00:00", formatElapsed(900))
        assertEquals("00:04", formatElapsed(4_000))
        assertEquals("00:04", formatElapsed(4_999))
        assertEquals("00:10", formatElapsed(10_000))
    }
}
