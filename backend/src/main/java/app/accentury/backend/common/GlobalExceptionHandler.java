package app.accentury.backend.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 예외를 공통 오류 봉투({@link ErrorResponse})로 변환하는 전역 핸들러.
 * <p>
 * App-Backend API 명세서 §2.3 - 404든 500이든 검증 실패든,
 * 클라이언트는 항상 같은 형태의 JSON을 받는다.
 * <p>
 * {@link ResponseEntityExceptionHandler}를 상속하는 이유:
 * 깨진 JSON·미지원 메서드(405)·미지원 미디어 타입(415)·없는 경로(404) 등
 * 스프링 프레임워크가 던지는 예외 10여 종을 부모가 올바른 상태코드로 잡아주고,
 * 우리는 {@link #handleExceptionInternal} 하나만 재정의해서 응답 본문을
 * 우리 봉투로 바꾼다. (Codex 리뷰 P1 반영 - 클라이언트 실수가 500으로 새는 문제 차단)
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 비즈니스 예외 - ErrorCode가 상태·retryable을 이미 알고 있다 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        String correlationId = CorrelationIdFilter.current();
        log.warn("[{}] {} - {}", correlationId, e.code(), e.getMessage());

        HttpHeaders headers = new HttpHeaders();
        if (e.retryAfterMs() != null) {
            // 429 응답에 재시도 가능 시간 제공 (KAN-28·34 - 초 단위 올림)
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf((e.retryAfterMs() + 999) / 1000));
        }

        return ResponseEntity.status(e.code().status())
                .headers(headers)
                .body(new ErrorResponse(
                        e.code().name(), e.getMessage(), e.code().retryable(),
                        e.retryAfterMs(), correlationId));
    }

    /** 경로·쿼리 파라미터 타입 불일치 (예: 숫자 자리에 문자) → 400. 부모가 안 잡아주는 케이스 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(envelope(ErrorCode.VALIDATION_FAILED,
                        e.getName() + ": 값의 형식이 올바르지 않습니다."));
    }

    /** 그 외 전부 → 500. 내부 정보는 숨기고 로그에만 남긴다 (NFR-SC-07) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("[{}] 예상치 못한 오류", CorrelationIdFilter.current(), e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(envelope(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

    /**
     * 부모(ResponseEntityExceptionHandler)가 잡은 모든 프레임워크 예외가
     * 마지막에 이 메서드를 거친다 - 여기서 본문을 우리 봉투로 교체한다.
     * 상태코드는 프레임워크가 정한 값을 그대로 존중한다 (404·405·415·400...).
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = mapFrameworkStatus(statusCode);
        String message = (ex instanceof MethodArgumentNotValidException manv)
                ? firstFieldError(manv)
                : code.defaultMessage();
        return ResponseEntity.status(statusCode).headers(headers).body(envelope(code, message));
    }

    /** 프레임워크가 정한 상태코드 → 우리 오류 사전(ErrorCode)의 코드명 매핑 */
    private ErrorCode mapFrameworkStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.VALIDATION_FAILED;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.MEDIA_TYPE_UNSUPPORTED;
            case 500 -> ErrorCode.INTERNAL_ERROR;
            default -> status.is4xxClientError() ? ErrorCode.REQUEST_REJECTED : ErrorCode.INTERNAL_ERROR;
        };
    }

    /** @Valid 검증 실패 시 첫 번째 필드 오류를 메시지로 */
    private String firstFieldError(MethodArgumentNotValidException e) {
        return e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse(ErrorCode.VALIDATION_FAILED.defaultMessage());
    }

    private ErrorResponse envelope(ErrorCode code, String message) {
        return ErrorResponse.of(code.name(), message, code.retryable(), CorrelationIdFilter.current());
    }
}
