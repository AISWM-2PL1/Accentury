package com.accentury.app.session

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- 재응시 (KAN-34 2단계, KAN-107) ---

    /** 세션을 확보한 상태 = 결과 화면에서 [다시 테스트하기]를 누를 수 있는 상태. */
    private fun readyController(): SessionGateController {
        val controller = SessionGateController()
        controller.onResult(SessionResult.Created(session))
        return controller
    }

    private val newSession = session.copy(sessionId = "s_def", sessionToken = "st_uvw")

    @Test
    fun `재응시는 지금 세션의 토큰을 폐기 대상으로 넘긴다 - 폐기와 발급이 한 요청이다`() {
        assertEquals("st_xyz", readyController().beginRetest())
    }

    @Test
    fun `재응시 중 두 번째 호출은 무시된다 - 더블탭이 만든 세션은 곧바로 고아가 된다`() {
        val controller = readyController()
        assertEquals("st_xyz", controller.beginRetest())

        assertNull(controller.beginRetest())
        assertTrue(controller.retestInFlight)
    }

    @Test
    fun `버릴 세션이 없으면 재응시를 걸지 않는다`() {
        val controller = SessionGateController()

        assertNull(controller.beginRetest())
        assertFalse(controller.retestInFlight)
    }

    @Test
    fun `재응시에 성공하면 새 세션으로 갈린다`() {
        val controller = readyController()
        controller.beginRetest()

        val outcome = controller.onRetestResult(SessionResult.Created(newSession))

        assertEquals(RetestOutcome.Replaced(newSession), outcome)
        assertEquals(newSession, controller.session)
        assertFalse(controller.retestInFlight)
    }

    @Test
    fun `재응시에 실패해도 이전 세션은 그대로다 - 서버도 안 지웠고 결과 화면이 아직 조회한다`() {
        val controller = readyController()
        controller.beginRetest()

        val outcome = controller.onRetestResult(SessionResult.TransportError("boom"))

        assertEquals(RetestOutcome.Failed(SessionFailureReason.Network, null, null), outcome)
        assertEquals(session, controller.session)
        assertEquals(SessionGateState.Ready(session), controller.state)
    }

    @Test
    fun `재응시 실패는 서버가 준 코드와 대기 시간을 그대로 싣는다`() {
        val controller = readyController()
        controller.beginRetest()

        val outcome = controller.onRetestResult(
            rejected(code = "RATE_LIMITED", retryable = true, retryAfterMs = 5_000L),
        )

        assertEquals(RetestOutcome.Failed(SessionFailureReason.RateLimited, "RATE_LIMITED", 5_000L), outcome)
    }

    @Test
    fun `재응시 실패는 최초 생성과 같은 판정 규칙을 쓴다`() {
        val controller = readyController()
        controller.beginRetest()

        val outcome = controller.onRetestResult(
            rejected(code = "VALIDATION_FAILED", retryable = false, retryAfterMs = null),
        )

        assertEquals(RetestOutcome.Failed(SessionFailureReason.Unsupported, "VALIDATION_FAILED", null), outcome)
    }

    @Test
    fun `실패한 재응시는 다시 걸 수 있다`() {
        val controller = readyController()
        controller.beginRetest()
        controller.onRetestResult(SessionResult.TransportError("boom"))

        assertEquals("st_xyz", controller.beginRetest())
    }

    @Test
    fun `성공한 재응시의 다음 재응시는 새 세션의 토큰을 넘긴다`() {
        val controller = readyController()
        controller.beginRetest()
        controller.onRetestResult(SessionResult.Created(newSession))

        assertEquals("st_uvw", controller.beginRetest())
    }

    // --- 폐기 대기 토큰 (KAN-34 2단계) ---

    @Test
    fun `최초 응시에는 폐기할 세션이 없다`() {
        assertNull(SessionGateController().pendingPreviousToken)
    }

    @Test
    fun `종료 후 재시작은 버린 세션의 토큰을 다음 생성에 넘긴다 - 재응시와 같은 폐기 경로다`() {
        val controller = readyController()

        controller.restart()

        assertEquals("st_xyz", controller.pendingPreviousToken)
    }

    @Test
    fun `새 세션을 받으면 폐기 대기가 풀린다 - 발급이 곧 폐기 완료 통지다`() {
        val controller = readyController()
        controller.restart()

        controller.onResult(SessionResult.Created(newSession))

        assertNull(controller.pendingPreviousToken)
    }

    @Test
    fun `생성에 실패하면 폐기 대기가 남는다 - 다음 시도가 다시 실어야 한다`() {
        val controller = readyController()
        controller.restart()

        controller.onResult(SessionResult.TransportError("boom"))

        assertEquals("st_xyz", controller.pendingPreviousToken)
    }

    @Test
    fun `버릴 세션이 없는 재시도는 앞서 적어 둔 폐기 대기를 지우지 않는다`() {
        val controller = readyController()
        controller.restart() // 종료: 세션을 버리며 토큰을 적어 둔다
        controller.onResult(SessionResult.TransportError("boom"))

        controller.restart() // 실패 화면의 [다시 시도]: 버릴 세션이 없다

        assertEquals("st_xyz", controller.pendingPreviousToken)
    }

    @Test
    fun `재응시 성공도 폐기 대기를 남기지 않는다 - 그 요청이 이미 폐기를 실어 보냈다`() {
        val controller = readyController()
        controller.beginRetest()
        controller.onRetestResult(SessionResult.Created(newSession))

        assertNull(controller.pendingPreviousToken)
    }

    @Test
    fun `폐기 대기 토큰은 회전을 넘기지 않는다 - 이미 버려진 익명 세션의 만료를 앞당길 뿐이다`() {
        val controller = readyController()
        controller.restart()

        assertNull(rotate(controller).pendingPreviousToken)
    }

    @Test
    fun `재응시 진행 중에 회전하면 진행 표시가 풀려 다시 누를 수 있다`() {
        val controller = readyController()
        controller.beginRetest()

        val restored = rotate(controller)

        assertFalse(restored.retestInFlight)
        assertEquals(session, restored.session)
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
