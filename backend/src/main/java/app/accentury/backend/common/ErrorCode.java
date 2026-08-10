package app.accentury.backend.common;

import org.springframework.http.HttpStatus;

/**
 * API 오류 코드 정의서.
 * <p>
 * App-Backend API 명세서 §2.4 - 도메인별 네임스페이스로 나뉜다:
 * SESSION_* / ITEM_* / AUDIO_* / ANALYSIS_* / RESULT_* / RATE_* + 공통.
 * <p>
 * 각 코드는 자기의 HTTP 상태와 재시도 가능 여부, 기본 메시지를 스스로 알고 있어서,
 * 던지는 쪽은 {@code throw new ApiException(ErrorCode.SESSION_EXPIRED)} 한 줄이면 된다.
 * 지금은 각 네임스페이스의 대표 코드만 있고, API를 개발하면서 필요한 코드를 추가한다.
 */
public enum ErrorCode {

    // === SESSION_* : 익명 세션 (KAN-9) ===
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, false, "세션이 만료되었습니다. 테스트를 다시 시작해 주세요."),
    SESSION_FORBIDDEN(HttpStatus.FORBIDDEN, false, "이 세션에 접근할 수 없습니다."),
    SESSION_COMPLETED(HttpStatus.CONFLICT, false, "이미 완료된 테스트입니다."),

    // === ITEM_* : 문항 (KAN-10, 13, 15) ===
    ITEM_NOT_IN_VERSION(HttpStatus.UNPROCESSABLE_CONTENT, false, "이 테스트 버전에 없는 문항입니다."),
    ITEM_WRONG_TYPE(HttpStatus.CONFLICT, false, "문항 유형이 올바르지 않습니다."),

    // === AUDIO_* : 음성 업로드 (KAN-23) ===
    AUDIO_FORMAT_UNSUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, false, "지원하지 않는 오디오 형식입니다. (WAV 16kHz mono)"),
    AUDIO_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, false, "녹음 파일이 너무 큽니다. (최대 1MB)"),
    AUDIO_TOO_LONG(HttpStatus.UNPROCESSABLE_CONTENT, false, "녹음이 너무 깁니다. (최대 10초)"),
    AUDIO_TOO_QUIET(HttpStatus.UNPROCESSABLE_CONTENT, true, "녹음이 너무 조용합니다. 다시 시도해 주세요."),

    // === ANALYSIS_* : AI 분석 (KAN-22, 24) ===
    ANALYSIS_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, true, "분석이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."),
    ANALYSIS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "분석 서버에 연결할 수 없습니다."),
    ANALYSIS_MISREAD(HttpStatus.UNPROCESSABLE_CONTENT, true, "제시된 문장과 다른 내용이 녹음되었습니다."),

    // === RESULT_* : 결과 (KAN-25) ===
    RESULT_NOT_READY(HttpStatus.CONFLICT, true, "결과를 준비하고 있습니다."),
    RESULT_INCOMPLETE(HttpStatus.UNPROCESSABLE_CONTENT, false, "아직 완료하지 않은 문항이 있습니다."),
    RESULT_RETAKE_REQUIRED(HttpStatus.CONFLICT, true, "실패한 문항이 있습니다. 다시 녹음해 주세요."),
    RESULT_EXPIRED(HttpStatus.GONE, false, "결과 보관 기간(24시간)이 지났습니다. 다시 테스트해 주세요."),

    // === RATE_* : 요청 제한 (KAN-23, KAN-28) ===
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    // 시간이 지나도 풀리지 않는 상한이므로 retryable=false - RATE_LIMITED와 다르다
    RATE_RETAKE_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, false, "이 문항의 업로드 횟수 상한을 넘었습니다. (최대 5회)"),

    // === 공통 ===
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, false, "요청 값이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, false, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, false, "지원하지 않는 HTTP 메서드입니다."),
    MEDIA_TYPE_UNSUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, false, "지원하지 않는 요청 형식입니다."),
    REQUEST_REJECTED(HttpStatus.BAD_REQUEST, false, "요청을 처리할 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, true, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final boolean retryable;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, boolean retryable, String defaultMessage) {
        this.status = status;
        this.retryable = retryable;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
