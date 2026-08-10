package app.accentury.backend.common;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
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
 * 깨진 JSON, 미지원 메서드(405), 미지원 미디어 타입(415), 없는 경로(404) 등
 * 스프링 프레임워크가 던지는 예외 10여 종을 부모가 올바른 상태코드로 잡아주고,
 * 우리는 {@link #handleExceptionInternal} 하나만 재정의해서 응답 본문을
 * 우리 봉투로 바꾼다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 오류 응답은 어떤 캐시에도 저장하지 않는다.
     * <p>
     * 404, 405, 410은 캐시 지시자가 없으면 휴리스틱 캐싱 대상이다(RFC 9110 §15.1).
     * 정상 응답이 1년 immutable로 캐싱되는 API라(§3.2 - CDN 미도입 확정으로 private,
     * KAN-101 종결), 배포 중 잠깐 난 404가 캐시에 눌러앉으면 정상 사용자에게
     * 반복 재생될 수 있다 (Claude 리뷰 P2).
     */
    private static final CacheControl NO_STORE = CacheControl.noStore();

    /**
     * 비즈니스 예외 - ErrorCode가 상태와 retryable을 이미 알고 있다
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        String correlationId = CorrelationIdFilter.current();
        log.warn("[{}] {} - {}", correlationId, e.code(), e.getMessage());

        HttpHeaders headers = new HttpHeaders();
        if (e.retryAfterMs() != null) {
            // 429 응답에 재시도 가능 시간 제공 (KAN-28, 34 - 초 단위 올림)
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf((e.retryAfterMs() + 999) / 1000));
        }

        return ResponseEntity.status(e.code().status())
                .headers(headers)
                .cacheControl(NO_STORE)
                .body(new ErrorResponse(
                        e.code().name(), e.getMessage(), e.code().retryable(),
                        e.retryAfterMs(), correlationId));
    }

    /**
     * 경로와 쿼리 파라미터 타입 불일치 (예: 숫자 자리에 문자) → 400.
     * 부모도 상위 타입(TypeMismatchException)으로 잡아주지만,
     * 여기서 어떤 파라미터가 문제인지 담은 메시지로 바꾼다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .cacheControl(NO_STORE)
                .body(envelope(ErrorCode.VALIDATION_FAILED,
                        e.getName() + ": 값의 형식이 올바르지 않습니다."));
    }

    /**
     * 그 외 전부 → 500. 내부 정보는 숨기고 로그에만 남긴다 (NFR-SC-07)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("[{}] 예상치 못한 오류", CorrelationIdFilter.current(), e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .cacheControl(NO_STORE)
                .body(envelope(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

    /**
     * 부모(ResponseEntityExceptionHandler)가 잡은 모든 프레임워크 예외가
     * 마지막에 이 메서드를 거친다 -> 여기서 본문을 우리 봉투로 교체한다.
     * 상태코드는 프레임워크가 정한 값을 그대로 존중한다 (404, 405, 415, 400...).
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        // 응답이 이미 클라이언트로 나가기 시작했으면 새 응답을 만들지 않는다.
        if (request instanceof ServletWebRequest servletWebRequest) {
            HttpServletResponse response = servletWebRequest.getResponse();
            if (response != null && response.isCommitted()) {
                log.warn("[{}] 응답이 이미 커밋된 상태에서 예외 발생 - 무시함: {}",
                        CorrelationIdFilter.current(), ex.toString());
                return null;
            }
        }

        ErrorCode code = mapFrameworkStatus(statusCode);
        String message = (ex instanceof MethodArgumentNotValidException manv)
                ? firstFieldError(manv)
                : code.defaultMessage();
        // headers()는 빌더 내부로 복사하므로, 프레임워크가 넘긴 헤더를 건드리지 않고 지시자를 얹는다
        return ResponseEntity.status(statusCode).headers(headers).cacheControl(NO_STORE)
                .body(envelope(code, message));
    }

    /**
     * 프레임워크가 정한 상태코드 → 우리 오류 사전(ErrorCode)의 코드명 매핑
     */
    private ErrorCode mapFrameworkStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.VALIDATION_FAILED;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            // multipart 크기 초과는 컨트롤러 도달 전에 프레임워크가 MaxUploadSizeExceededException으로
            // 끊는다 - 이 API에서 413은 음성 업로드(§3.3)뿐이므로 AUDIO_TOO_LARGE로 매핑한다
            case 413 -> ErrorCode.AUDIO_TOO_LARGE;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.MEDIA_TYPE_UNSUPPORTED;
            case 500 -> ErrorCode.INTERNAL_ERROR;
            default -> status.is4xxClientError() ? ErrorCode.REQUEST_REJECTED : ErrorCode.INTERNAL_ERROR;
        };
    }

    /**
     * @Valid 검증 실패 시 첫 번째 필드 오류를 메시지로
     */
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
