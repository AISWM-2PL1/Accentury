package com.accentury.app.bridge

import com.accentury.app.session.RetestOutcome
import com.accentury.app.session.SessionFailureReason
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// toJson마다 새로 만들면 직렬화기 캐시가 매번 버려진다. 파일 안에서 하나만 쓴다.
private val json = Json

/**
 * 재응시 실패를 결과 화면(웹)에 회신하는 계약 (KAN-34 2단계, KAN-107).
 *
 * 공통 오류 봉투(§2.3)의 부분집합이다. 봉투를 통째로 넘기지 않는 이유: `correlationId`처럼 웹이
 * 그릴 수 없는 값은 화면에 쓰이지도 않으면서 계약 표면만 넓힌다.
 *
 * **[code]는 서버가 준 값 그대로이고, 앱이 지어내지 않는다.** 봉투를 못 읽은 응답에서는 null이다 —
 * 네이티브의 4갈래 판정(망/서버/요청 제한/재시도 불가)을 코드처럼 실어 보내면 웹이 그것을 서버
 * 코드와 구분할 수 없게 된다. 웹이 화면을 가르는 데 필요한 것은 [retryable]과 [retryAfterMs] 둘이고,
 * 그 판정은 이미 여기서 끝나 있다. 갈래별 안내 문구는 [message]가 들고 간다.
 *
 * @property message 사용자에게 그대로 보여줄 수 있는 문구. 웹이 갈래별 문구를 따로 들면 같은
 *   판정에 두 벌의 카피가 생겨 앱과 웹이 다른 말을 하게 된다
 * @property retryable 다시 눌러 볼 값어치가 있는가. 서버가 재시도 불가로 못박은 거절만 false다
 * @property retryAfterMs 429가 알려준 대기 시간 (§2.5). 그 외에는 null
 */
@Serializable
data class RetestFailure(
    val code: String?,
    val message: String,
    val retryable: Boolean,
    val retryAfterMs: Long?,
) {
    /** 브리지가 JS로 넘길 payload. */
    fun toJson(): String = json.encodeToString(serializer(), this)
}

/**
 * 재응시 실패 갈래를 웹에 회신할 payload로 접는다.
 *
 * 문구가 세션 게이트의 실패 화면과 다른 이유: 사용자가 보고 있는 화면이 다르다. 게이트는 "테스트를
 * 시작하지 못했다"이지만 여기서는 이미 한 번 응시를 끝내고 결과를 보는 중이라, 무엇이 안 됐는지가
 * "다시 시작"이어야 말이 통한다.
 */
fun retestFailurePayload(failed: RetestOutcome.Failed): RetestFailure = RetestFailure(
    code = failed.code,
    message = when (failed.reason) {
        SessionFailureReason.RateLimited -> "접속이 몰리고 있어요 · 잠시 뒤에 다시 시도해 주세요"
        SessionFailureReason.Network -> "연결이 불안정해요 · 네트워크를 확인하고 다시 시도해 주세요"
        SessionFailureReason.Server -> "다시 시작하지 못했어요 · 잠시 뒤에 다시 시도해 주세요"
        SessionFailureReason.Unsupported -> "지금은 다시 시작할 수 없어요 · 앱을 최신 버전으로 업데이트해 주세요"
    },
    // 갈래가 곧 답이다 — 서버가 재시도 불가로 못박은 거절만 다시 눌러도 소용없다.
    retryable = failed.reason != SessionFailureReason.Unsupported,
    retryAfterMs = failed.retryAfterMs,
)

/**
 * 보상형 광고를 중도에 닫았을 때의 회신 (KAN-196, webview-bridge.md §8.2).
 *
 * [retestFailurePayload]와 달리 서버 응답이 없다 — 세션 요청은 아직 나가지도 않았다. 그래서 `code`는
 * 서버 코드가 아니라 **앱이 정한 값** `AD_DISMISSED`다. "앱이 코드를 지어내지 않는다"는 위 규칙의
 * 예외이고, 예외인 이유는 이 실패가 서버와 무관하게 앱 안에서 끝난 일이라서다: 웹이 이 코드로
 * 문구를 고르지는 않지만(문구는 [RetestFailure.message]가 든다) 계측·로그에서 서버 거절과 갈라
 * 읽을 수 있어야 한다.
 *
 * `retryable = true`, `retryAfterMs = null` — 다시 누르면 광고가 다시 뜨고, 기다릴 이유가 없다.
 * 문구는 여기가 정본이다. 웹은 `message`를 그대로 그리고 코드로 문구를 고르지 않는다 (§8.2).
 */
fun adDismissedRetestFailure(): RetestFailure = RetestFailure(
    code = CODE_AD_DISMISSED,
    message = "광고를 끝까지 보시면 다시 테스트할 수 있어요",
    retryable = true,
    retryAfterMs = null,
)

/** 웹 계약의 코드 문자열 (`bridge.ts`의 `RetestFailure` 주석과 같은 값). 4단계 iOS도 같은 값이다. */
const val CODE_AD_DISMISSED = "AD_DISMISSED"
