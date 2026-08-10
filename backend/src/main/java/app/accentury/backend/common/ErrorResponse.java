package app.accentury.backend.common;

import org.jspecify.annotations.Nullable;

/**
 * 모든 API 오류 응답의 공통 봉투.
 * <p>
 * App-Backend API 명세서 §2.3 - 어떤 API에서 어떤 예외가 나든
 * 클라이언트는 항상 이 형태의 JSON을 받는다.
 *
 * <pre>
 * {
 *   "code": "AUDIO_TOO_QUIET",
 *   "message": "녹음이 너무 조용합니다. 다시 시도해 주세요.",
 *   "retryable": true,
 *   "retryAfterMs": null,
 *   "correlationId": "c_8f2a..."
 * }
 * </pre>
 *
 * @param code          오류 코드 (§2.4 네임스페이스: SESSION_* / ITEM_* / AUDIO_* / ANALYSIS_* / RESULT_* / RATE_*).
 *                      클라이언트 분기는 HTTP 상태가 아니라 이 값으로 한다
 * @param message       사용자에게 보여줄 수 있는 한국어 설명
 * @param retryable     같은 요청을 다시 시도하면 성공할 가능성이 있는지
 * @param retryAfterMs  재시도 가능 시각까지 남은 시간(ms). 요청 제한(429)에서만 사용, 그 외에는 null
 * @param correlationId 요청 추적 ID - 서버 로그에서 같은 요청을 찾는 키. CorrelationIdFilter가 부여한다
 */
public record ErrorResponse(
        String code,

        String message,

        boolean retryable,

        // 429에서만 값이 있고 그 외 null - 유일한 null 허용 필드
        @Nullable Long retryAfterMs,

        String correlationId
) {

    /**
     * 일반 오류용 - retryAfterMs 없이 생성한다.
     */
    public static ErrorResponse of(String code, String message, boolean retryable, String correlationId) {
        return new ErrorResponse(code, message, retryable, null, correlationId);
    }

    /**
     * 요청 제한(429) 전용 - 재시도 가능 시간까지 담는다.
     */
    public static ErrorResponse rateLimited(String code, String message, long retryAfterMs, String correlationId) {
        return new ErrorResponse(code, message, true, retryAfterMs, correlationId);
    }
}
