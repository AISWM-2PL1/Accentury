package com.accentury.app.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json

// 저장/복원마다 새로 만들면 직렬화기 캐시가 매번 버려진다. 파일 안에서 하나만 쓴다.
private val json = Json

/** 봉투를 읽을 수 있을 때의 요청 제한 코드 (§2.5). */
private const val CODE_RATE_LIMITED = "RATE_LIMITED"

/** 서버가 준 밀리초를 사용자에게 읽어 줄 초로 올림한다 — 서버가 Retry-After를 만드는 규칙과 같다. */
private fun ceilSeconds(millis: Long): Long = (millis + 999) / 1_000

/** 시작 게이트의 세션 관문 상태 (KAN-34). */
sealed interface SessionGateState {

    /** 세션 생성 요청이 나가 있다. 화면은 준비 중 표시를 세운다. */
    data object Creating : SessionGateState

    /**
     * 세션을 받지 못했다. 사용자는 여기서 [SessionGateController.restart]로만 앞으로 갈 수 있다.
     *
     * @property retryAfterSeconds 429가 알려준 대기 시간(초). 그 외에는 null
     */
    data class Failed(val reason: SessionFailureReason, val retryAfterSeconds: Long?) : SessionGateState

    /** 세션을 확보했다. 이 값이 곧 테스트 진입이다. */
    data class Ready(val session: Session) : SessionGateState
}

/**
 * 실패 화면의 문구와 버튼을 가르는 갈래.
 *
 * 상태 코드를 그대로 들고 다니는 대신 갈래로 접는 이유: 화면이 물어보는 것은 "무슨 코드였나"가
 * 아니라 "사용자가 무엇을 할 수 있나"다. 기다리면 되는가([RateLimited]), 망을 확인하면 되는가
 * ([Network]), 다시 눌러 보면 되는가([Server]), 다시 눌러도 소용없는가([Unsupported]).
 */
enum class SessionFailureReason {
    /** 429 — 같은 IP에서 세션 생성이 몰렸다 (§2.5). 기다리면 풀린다. */
    RateLimited,

    /** 응답이 아예 오지 않았다 — 망 문제이거나 서버가 닿지 않는다. */
    Network,

    /** 서버가 거절했지만 재시도로 풀릴 수 있다 (5xx·408, 봉투가 retryable=true로 준 거절). */
    Server,

    /** 서버가 재시도 불가로 못박았다 (`VALIDATION_FAILED` 등) — 앱과 서버 계약이 어긋난 경우다. */
    Unsupported,
}

/**
 * 세션 확보 상태 머신 (KAN-34). 마이크 권한 게이트(KAN-98)를 지난 뒤, 테스트 진입 URL을 열기 전에 선다.
 *
 * 화면에서 분리한 이유는 [com.accentury.app.web.WebLoadController]·
 * [com.accentury.app.permission.MicPermissionController]와 같다 — 오류 응답을 어떤 복구 경로로
 * 접는지가 시작 UX의 정확성을 좌우하는데, OkHttp·Compose에 붙어 있으면 JVM 단위 테스트가 불가능하다.
 * Compose snapshot state라 화면은 그대로 따라온다.
 *
 * [session]이 이 앱에서 "테스트에 들어갔는가"의 정본이다. 예전의 `testEntered` 불리언을 대신한다 —
 * 진입 URL·업로드·브리지 토큰이 전부 세션에서 나오므로, 진입 여부와 세션을 따로 들면 둘이 어긋난
 * 상태(진입했는데 세션이 없다)를 표현할 수 있게 된다.
 */
class SessionGateController private constructor(initialState: SessionGateState) {

    constructor() : this(SessionGateState.Creating)

    var state: SessionGateState by mutableStateOf(initialState)
        private set

    /**
     * 생성 시도 횟수이자 요청 재실행 키 — 값이 바뀌면 화면이 세션 생성을 처음부터 다시 건다
     * (WebLoadController.attempt와 같은 역할).
     */
    var attempt: Int by mutableIntStateOf(0)
        private set

    /** 확보된 세션. null이면 아직 테스트에 들어갈 수 없다. */
    val session: Session? get() = (state as? SessionGateState.Ready)?.session

    /** 세션 생성 응답 한 건을 반영한다. */
    fun onResult(result: SessionResult) {
        state = when (result) {
            is SessionResult.Created -> SessionGateState.Ready(result.session)

            is SessionResult.TransportError ->
                SessionGateState.Failed(SessionFailureReason.Network, retryAfterSeconds = null)

            is SessionResult.Rejected -> SessionGateState.Failed(
                reason = when {
                    // 봉투를 읽었으면 코드가 정본이고, 못 읽었으면 대기 시간의 존재가 대신 말해 준다
                    // — retryAfterMs를 실어 보내는 거절은 요청 제한뿐이다 (§2.5).
                    result.code == CODE_RATE_LIMITED || result.retryAfterMs != null ->
                        SessionFailureReason.RateLimited

                    !result.retryable -> SessionFailureReason.Unsupported

                    else -> SessionFailureReason.Server
                },
                retryAfterSeconds = result.retryAfterMs?.let(::ceilSeconds),
            )
        }
    }

    /**
     * 새 세션 생성을 처음부터 다시 건다.
     *
     * 실패 화면의 [다시 시도]와 테스트 종료 후 인트로 복귀가 같은 자리를 쓴다 — 둘 다 "지금 들고
     * 있는 세션(또는 실패)을 버리고 다음 시작은 새 세션으로 한다"는 같은 뜻이기 때문이다.
     * 확보돼 있던 세션도 여기서 버려진다: 종료한 응시를 다음 시작이 이어받으면 안 된다.
     *
     * [attempt]를 올리는 것이 실제로 요청을 다시 내보내는 신호다. 상태만 [SessionGateState.Creating]으로
     * 되돌리면 이미 한 번 돈 이펙트가 다시 돌 이유가 없어 화면이 준비 중인 채로 멈춘다.
     */
    fun restart() {
        attempt += 1
        state = SessionGateState.Creating
    }

    companion object {
        /**
         * rememberSaveable 결선용.
         *
         * 저장하는 것은 확보된 세션뿐이다. 세션은 응답에서 한 번만 노출되는 토큰을 들고 있어
         * (Session KDoc) 회전·프로세스 복원에 증발하면 진행 중인 응시가 통째로 죽는다.
         *
         * 생성 중·실패는 저장하지 않는다 — 살려 봐야 그 요청은 프로세스와 함께 사라졌고, 저장된
         * 실패를 그대로 세우면 이미 풀린 429를 계속 보여준다. 복원 뒤에는 생성 중부터 다시 시작해
         * 화면이 곧바로 요청을 건다. 그 대가로 세션 생성 중에 회전하면 요청이 한 번 더 나가 아무
         * 데이터도 달리지 않은 세션이 서버에 하나 남는다 — 익명이고 30분 뒤 만료되므로 감수한다.
         */
        fun saver(): Saver<SessionGateController, String> = Saver(
            save = { controller ->
                val session = controller.session
                if (session == null) "" else json.encodeToString(Session.serializer(), session)
            },
            restore = ::restored,
        )

        /**
         * 저장값이 깨져 있으면(구버전 형식 등) 저장이 없었던 것으로 본다 — 새 세션을 만드는 편이
         * 반쯤 읽힌 세션으로 업로드를 거절당하는 것보다 낫다.
         */
        private fun restored(saved: String): SessionGateController {
            if (saved.isEmpty()) return SessionGateController()
            val session = try {
                json.decodeFromString(Session.serializer(), saved)
            } catch (_: Exception) {
                return SessionGateController()
            }
            return SessionGateController(SessionGateState.Ready(session))
        }
    }
}
