package app.accentury.backend.upload;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.CorrelationIdFilter;
import app.accentury.backend.common.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 업로드 요청 제한을 multipart 해석 <b>전에</b> 집행하는 필터 (Codex sol 리뷰 P2).
 * <p>
 * 컨트롤러({@code @RequestPart}) 도달 시점에는 본문 전체가 이미 읽혀 버퍼링된 뒤라,
 * 서비스에서 검사하면 한도 초과 IP가 429를 받으면서도 요청마다 파싱과 메모리 할당을
 * 강제할 수 있다 - DispatcherServlet 앞에서 끊는다.
 * <p>
 * MVC 바깥이라 {@code GlobalExceptionHandler}를 타지 않으므로 오류 봉투(§2.3)를
 * 직접 쓴다. correlation ID는 {@code HIGHEST_PRECEDENCE}인
 * {@link CorrelationIdFilter}가 앞서 실행되어 이미 채워져 있다.
 * <p>
 * {@code @Component}가 아니라 {@link UploadRateLimitFilterConfig}가 등록한다 -
 * Filter 빈은 {@code @WebMvcTest} 슬라이스에도 포함돼 무관한 슬라이스 테스트가
 * 이 필터의 의존성까지 요구하게 되기 때문이다.
 */
class UploadRateLimitFilter extends OncePerRequestFilter {

    private static final Pattern RECORDING_PATH =
            Pattern.compile("/v0/sessions/[^/]+/voice-items/[^/]+/recording");

    private final UploadRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    UploadRateLimitFilter(UploadRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("POST".equals(request.getMethod())
                && RECORDING_PATH.matcher(request.getRequestURI()).matches()) {
            try {
                rateLimiter.check(ClientIps.from(request));
            } catch (ApiException e) {
                writeRateLimited(response, e);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /** 429 + Retry-After + 공통 오류 봉투 - GlobalExceptionHandler의 429 응답과 같은 형태다 */
    private void writeRateLimited(HttpServletResponse response, ApiException e) throws IOException {
        Long retryAfterMs = e.retryAfterMs();
        response.setStatus(e.code().status().value());
        if (retryAfterMs != null) {
            // 초 단위 올림 - GlobalExceptionHandler와 동일 규칙 (KAN-28, KAN-34)
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf((retryAfterMs + 999) / 1000));
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(
                e.code().name(), e.getMessage(), e.code().retryable(), retryAfterMs,
                CorrelationIdFilter.current()));
    }
}
