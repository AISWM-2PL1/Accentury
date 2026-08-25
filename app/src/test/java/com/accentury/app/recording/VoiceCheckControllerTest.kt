package com.accentury.app.recording

import com.accentury.app.audio.AudioQuality
import com.accentury.app.audio.RecordingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCheckControllerTest {

    /** 실제 엔진과 같은 32ms 간격으로 유성 프레임을 만든다 */
    private fun voiced(count: Int, hz: Float = CENTER_HZ, startMs: Long = 0L) =
        List(count) { RecordingEngine.PitchFrame(startMs + it * FRAME_MS, hz) }

    private fun unvoiced(count: Int, startMs: Long = 0L) =
        List(count) { RecordingEngine.PitchFrame(startMs + it * FRAME_MS, null) }

    private fun listening(state: VoiceCheckState) = state as VoiceCheckState.Listening

    @Test
    fun `말하기 전에는 안내가 말해 달라는 쪽이다`() {
        val controller = VoiceCheckController()

        val initial = listening(controller.state)
        assertEquals(VoiceCheckHint.SAY_IT, initial.hint)
        assertNull(initial.centerHz)

        // 무성 프레임만 들어와도 마찬가지다 - 소리는 났는데 목소리가 아니었던 경우다.
        assertFalse(controller.onProgress(rms = LOUD, newFrames = unvoiced(5)))
        assertEquals(VoiceCheckHint.SAY_IT, listening(controller.state).hint)
    }

    @Test
    fun `유성 프레임이 모자라면 계속 듣는다`() {
        val controller = VoiceCheckController()

        // 볼륨은 충분한데 중심을 잠글 만큼 말하지 않았다
        val stopRequested = controller.onProgress(rms = LOUD, newFrames = voiced(CENTER_MIN_VOICED_FRAMES - 1))

        assertFalse("아직 판정이 안 났으니 엔진을 세우지 않는다", stopRequested)
        val state = listening(controller.state)
        assertEquals(VoiceCheckHint.KEEP_GOING, state.hint)
        assertEquals(CENTER_MIN_VOICED_FRAMES - 1, state.voicedCount)
        assertNull("8개에 못 미치면 중심이 안 잠긴다", state.centerHz)
        assertTrue(state.loudEnough)
    }

    @Test
    fun `중심은 잡혔는데 볼륨이 모자라면 더 크게 말하라고 한다`() {
        val controller = VoiceCheckController()

        val stopRequested = controller.onProgress(rms = QUIET, newFrames = voiced(CENTER_MIN_VOICED_FRAMES))

        assertFalse("볼륨이 모자라면 아직 준비가 아니다", stopRequested)
        val state = listening(controller.state)
        assertEquals(VoiceCheckHint.TOO_QUIET, state.hint)
        assertEquals(CENTER_HZ, state.centerHz!!, 1e-3f)
        assertFalse(state.loudEnough)
    }

    @Test
    fun `조용히 잡은 중심은 뒤늦게 크게 말해도 그대로다`() {
        val controller = VoiceCheckController()

        controller.onProgress(rms = QUIET, newFrames = voiced(CENTER_MIN_VOICED_FRAMES))
        // 안내를 보고 크게 다시 말했다. 이번엔 훨씬 높은 음이지만 중심은 이미 잠겼다.
        val stopRequested = controller.onProgress(
            rms = LOUD,
            newFrames = voiced(8, hz = CENTER_HZ * 2f, startMs = CENTER_MIN_VOICED_FRAMES * FRAME_MS),
        )

        assertTrue("준비가 끝났으니 엔진을 세운다", stopRequested)
        val ready = controller.state as VoiceCheckState.Ready
        assertEquals("중심은 처음 8개의 중앙값이다", CENTER_HZ, ready.centerHz, 1e-3f)
        assertEquals(CENTER_MIN_VOICED_FRAMES + 8, ready.frames.size)
    }

    @Test
    fun `한 번 크게 말했으면 뒤에 조용해져도 통과한다`() {
        val controller = VoiceCheckController()

        // 크게 시작했지만 아직 프레임이 모자라고
        controller.onProgress(rms = LOUD, newFrames = voiced(4))
        // 말끝이 잦아들며 나머지 프레임이 채워졌다
        val stopRequested = controller.onProgress(
            rms = QUIET,
            newFrames = voiced(4, startMs = 4 * FRAME_MS),
        )

        assertTrue("볼륨 판정은 최댓값 기준이라 앞의 큰 소리가 근거로 남는다", stopRequested)
        assertTrue(controller.state is VoiceCheckState.Ready)
    }

    @Test
    fun `준비된 뒤 도착한 청크는 판정을 흔들지 못한다`() {
        val controller = VoiceCheckController()
        controller.onProgress(rms = LOUD, newFrames = voiced(CENTER_MIN_VOICED_FRAMES))
        val ready = controller.state as VoiceCheckState.Ready

        // 정지 요청과 실제 정지 사이에 청크가 한둘 더 온다
        val stopRequested = controller.onProgress(
            rms = 0.0,
            newFrames = voiced(4, startMs = CENTER_MIN_VOICED_FRAMES * FRAME_MS),
        )

        assertFalse(stopRequested)
        assertEquals(ready, controller.state)
    }

    @Test
    fun `듣기가 끝났는데 준비가 아니면 시간 초과다`() {
        val controller = VoiceCheckController()
        controller.onProgress(rms = QUIET, newFrames = voiced(CENTER_MIN_VOICED_FRAMES))

        controller.onStopped()

        val timedOut = controller.state as VoiceCheckState.TimedOut
        assertEquals("무엇이 모자랐는지가 남는다", VoiceCheckHint.TOO_QUIET, timedOut.hint)
        assertEquals(CENTER_MIN_VOICED_FRAMES, timedOut.frames.size)
    }

    @Test
    fun `준비된 뒤의 종료는 준비를 그대로 둔다`() {
        val controller = VoiceCheckController()
        controller.onProgress(rms = LOUD, newFrames = voiced(CENTER_MIN_VOICED_FRAMES))
        val ready = controller.state as VoiceCheckState.Ready

        // 준비가 되면 엔진 정지를 요청하므로 종료 통지는 늘 이 뒤에 온다
        controller.onStopped()

        assertEquals(ready, controller.state)
    }

    @Test
    fun `엔진 실패는 실패 상태가 되고 종료 통지가 덮지 않는다`() {
        val controller = VoiceCheckController()

        controller.onFailed("녹음 권한 없음")
        controller.onStopped()

        assertEquals("녹음 권한 없음", (controller.state as VoiceCheckState.Failed).reason)
    }

    @Test
    fun `다시 시도하면 전부 초기화된다`() {
        val controller = VoiceCheckController()
        controller.onProgress(rms = LOUD, newFrames = voiced(CENTER_MIN_VOICED_FRAMES))
        assertTrue(controller.state is VoiceCheckState.Ready)

        controller.restart()

        val state = listening(controller.state)
        assertEquals(emptyList<RecordingEngine.PitchFrame>(), state.frames)
        assertEquals(0, state.voicedCount)
        assertEquals(0.0, state.level, 0.0)
        assertFalse("볼륨 기록도 함께 비운다", state.loudEnough)
        assertNull(state.centerHz)
        assertEquals(VoiceCheckHint.SAY_IT, state.hint)
    }

    @Test
    fun `레벨은 최근 청크값이라 조용해지면 함께 내려간다`() {
        val controller = VoiceCheckController()

        controller.onProgress(rms = LOUD, newFrames = voiced(2))
        controller.onProgress(rms = QUIET, newFrames = voiced(2, startMs = 2 * FRAME_MS))

        val state = listening(controller.state)
        assertEquals(QUIET, state.level, 0.0)
        assertTrue("통과 판정은 최댓값이라 내려가지 않는다", state.loudEnough)
    }

    @Test
    fun `상태에 실린 프레임 목록은 복사본이라 다음 청크에 안 바뀐다`() {
        val controller = VoiceCheckController()

        controller.onProgress(rms = QUIET, newFrames = voiced(2))
        val first = listening(controller.state).frames
        controller.onProgress(rms = QUIET, newFrames = voiced(2, startMs = 2 * FRAME_MS))

        assertEquals(2, first.size)
    }

    private companion object {
        const val FRAME_MS = 32L
        const val CENTER_HZ = 220f

        /** 통과선을 넘는 청크 볼륨 */
        const val LOUD = AudioQuality.QUIET_RMS_THRESHOLD * 3
        /** 통과선에 못 미치는 청크 볼륨 */
        const val QUIET = AudioQuality.QUIET_RMS_THRESHOLD / 2
    }
}
