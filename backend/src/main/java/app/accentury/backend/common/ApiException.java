package app.accentury.backend.common;

import org.jspecify.annotations.Nullable;

/**
 * 비즈니스 로직에서 던지는 유일한 예외.
 * <p>
 * {@link ErrorCode}를 들고 다니며, {@link GlobalExceptionHandler}가 받아서
 * HTTP 상태와 오류 봉투({@link ErrorResponse})로 변환한다.
 *
 * <pre>
 * throw new ApiException(ErrorCode.SESSION_EXPIRED);                  // 기본 메시지 사용
 * throw new ApiException(ErrorCode.RESULT_INCOMPLETE, "음성 2번 문항이 누락되었습니다");  // 메시지 교체
 * throw ApiException.rateLimited(3000);                               // 429 + Retry-After
 * </pre>
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    /**
     * 요청 제한(429)일 때만 사용 - 재시도 가능 시각까지 남은 ms. 그 외 null
     */
    private final @Nullable Long retryAfterMs;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage(), null);
    }

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    private ApiException(ErrorCode code, String message, @Nullable Long retryAfterMs) {
        super(message);
        this.code = code;
        this.retryAfterMs = retryAfterMs;
    }

    /**
     * 요청 제한(KAN-28) 전용 팩토리
     */
    public static ApiException rateLimited(long retryAfterMs) {
        return new ApiException(ErrorCode.RATE_LIMITED, ErrorCode.RATE_LIMITED.defaultMessage(), retryAfterMs);
    }

    public ErrorCode code() {
        return code;
    }

    public @Nullable Long retryAfterMs() {
        return retryAfterMs;
    }
}
