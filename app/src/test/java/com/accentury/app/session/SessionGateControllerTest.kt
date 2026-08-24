package com.accentury.app.session

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGateControllerTest {

    private val session = Session(
        sessionId = "s_abc",
        sessionToken = "st_xyz",
        testVersion = "gn-2026.08.1",
        scoreVersion = "sv-1",
        expiresAt = "2026-08-24T10:30:00Z",
    )

    @Test
    fun `처음에는 생성 중이고 아직 세션이 없다`() {
        val controller = SessionGateController()
        assertEquals(SessionGateState.Creating, controller.state)
        assertNull(controller.session)
        assertEquals(0, controller.attempt)
    }

    @Test
    fun `세션을 받으면 그 값이 곧 테스트 진입이다`() {
        val controller = SessionGateController()
        controller.onResult(SessionResult.Created(session))

        assertEquals(SessionGateState.Ready(session), controller.state)
        assertEquals(session, controller.session)
    }

    @Test
    fun `응답이 오지 않은 실패는 망 문제로 접는다`() {
        val controller = SessionGateController()
        controller.onResult(SessionResult.TransportError("timeout"))

        assertEquals(SessionGateState.Failed(SessionFailureReason.Network, null), controller.state)
        assertNull(controller.session)
    }

    @Test
    fun `RATE_LIMITED는 대기 시간을 초로 올려 보여준다 - 서버의 Retry-After와 같은 규칙`() {
        val controller = SessionGateController()
        controller.onResult(rejected(code = "RATE_LIMITED", retryable = true, retryAfterMs = 2_100L))

        assertEquals(SessionGateState.Failed(SessionFailureReason.RateLimited, 3L), controller.state)
    }

    @Test
    fun `봉투를 못 읽어도 대기 시간이 있으면 요청 제한으로 판정한다 - 그 값을 주는 거절은 그것뿐이다`() {
        val controller = SessionGateController()
        controller.onResult(rejected(code = null, retryable = true, retryAfterMs = 5_000L))

        assertEquals(SessionGateState.Failed(SessionFailureReason.RateLimited, 5L), controller.state)
    }

    @Test
    fun `재시도 가능한 거절은 서버 실패로 접고 대기 시간은 남지 않는다`() {
        val controller = SessionGateController()
        controller.onResult(rejected(code = null, retryable = true, retryAfterMs = null))

        assertEquals(SessionGateState.Failed(SessionFailureReason.Server, null), controller.state)
    }

    @Test
    fun `서버가 재시도 불가로 못박은 거절은 다시 눌러도 소용없는 갈래로 간다`() {
        val controller = SessionGateController()
        controller.onResult(rejected(code = "VALIDATION_FAILED", retryable = false, retryAfterMs = null))

        assertEquals(SessionGateState.Failed(SessionFailureReason.Unsupported, null), controller.state)
    }

    @Test
    fun `재시도는 attempt를 올려 요청을 다시 내보내게 한다`() {
        val controller = SessionGateController()
        controller.onResult(SessionResult.TransportError("boom"))

        controller.restart()

        assertEquals(1, controller.attempt)
        assertEquals(SessionGateState.Creating, controller.state)
    }

    @Test
    fun `재시도는 확보돼 있던 세션도 버린다 - 종료한 응시를 다음 시작이 이어받으면 안 된다`() {
        val controller = SessionGateController()
        controller.onResult(SessionResult.Created(session))

        controller.restart()

        assertNull(controller.session)
        assertEquals(SessionGateState.Creating, controller.state)
    }

    @Test
    fun `재시도 뒤에도 같은 판정 규칙이 적용된다`() {
        val controller = SessionGateController()
        controller.onResult(SessionResult.TransportError("boom"))
        controller.restart()
        controller.onResult(SessionResult.Created(session))

        assertEquals(session, controller.session)
        assertEquals(1, controller.attempt)
    }

    @Test
    fun `확보한 세션은 회전을 넘긴다 - 토큰은 응답에서 한 번만 오므로 잃으면 되찾을 수 없다`() {
        val controller = SessionGateController()
        controller.onResult(SessionResult.Created(session))

        assertEquals(session, rotate(controller).session)
    }

    @Test
    fun `생성 중은 저장하지 않고 복원 직후 다시 요청을 걸 상태로 돌아온다`() {
        val restored = rotate(SessionGateController())

        assertEquals(SessionGateState.Creating, restored.state)
        assertEquals(0, restored.attempt)
    }

    @Test
    fun `실패는 저장하지 않는다 - 이미 풀린 요청 제한을 복원 뒤에도 보여주면 안 된다`() {
        val controller = SessionGateController()
        controller.onResult(rejected(code = "RATE_LIMITED", retryable = true, retryAfterMs = 60_000L))

        assertEquals(SessionGateState.Creating, rotate(controller).state)
    }

    @Test
    fun `저장값이 깨져 있으면 새 세션부터 다시 시작한다`() {
        val restored = with(SessionGateController.saver()) { restore("{not json") }

        assertEquals(SessionGateState.Creating, checkNotNull(restored).state)
    }

    @Test
    fun `복원된 세션은 진입 URL에 쓸 값을 그대로 들고 있다`() {
        val controller = SessionGateController()
        controller.onResult(SessionResult.Created(session))

        val restored = checkNotNull(rotate(controller).session)
        assertEquals("s_abc", restored.sessionId)
        assertEquals("st_xyz", restored.sessionToken)
        assertEquals("gn-2026.08.1", restored.testVersion)
        assertTrue(restored.expiresAt.isNotBlank())
    }

    private fun rejected(code: String?, retryable: Boolean, retryAfterMs: Long?) = SessionResult.Rejected(
        code = code,
        message = "거절",
        retryable = retryable,
        retryAfterMs = retryAfterMs,
    )

    /** rememberSaveable이 구성 변경에서 하는 일(save → restore)을 그대로 흉내 낸다. */
    private fun rotate(controller: SessionGateController): SessionGateController {
        val saver = SessionGateController.saver()
        val saved = with(saver) { SaverScope { true }.save(controller) }
        return checkNotNull(saved?.let { with(saver) { restore(it) } })
    }
}
