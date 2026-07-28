package app.accentury.backend.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 예외를 공통 오류 봉투({@link ErrorResponse})로 변환하는 전역 핸들러.
 * <p>
 * App-Backend API 명세서 §2.3 - 404든 500이든 검증 실패든,
 * 클라이언트는 항상 같은 형태의 JSON을 받는다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    /** 존재하지 않는 경로 → 404 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
        return envelope(ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.defaultMessage());
    }

    /** @Valid 검증 실패 → 400 (첫 번째 필드 오류만 메시지로) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse(ErrorCode.VALIDATION_FAILED.defaultMessage());
        return envelope(ErrorCode.VALIDATION_FAILED, detail);
    }

    /** 그 외 전부 → 500. 내부 정보는 숨기고 로그에만 남긴다 (NFR-SC-07) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("[{}] 예상치 못한 오류", CorrelationIdFilter.current(), e);
        return envelope(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
    }

    private ResponseEntity<ErrorResponse> envelope(ErrorCode code, String message) {
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code.name(), message, code.retryable(), CorrelationIdFilter.current()));
    }
}
