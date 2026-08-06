package app.accentury.backend.upload;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link UploadRateLimitFilter} 등록 (KAN-23).
 * <p>
 * 서블릿 URL 패턴은 경로 중간 와일드카드를 지원하지 않으므로 {@code /v0/sessions/*}로
 * 걸고 정확한 매칭(voice-items recording POST)은 필터 내부 정규식이 한다.
 */
@Configuration
class UploadRateLimitFilterConfig {

    @Bean
    FilterRegistrationBean<UploadRateLimitFilter> uploadRateLimitFilter(
            UploadRateLimiter rateLimiter, ObjectMapper objectMapper) {
        FilterRegistrationBean<UploadRateLimitFilter> registration =
                new FilterRegistrationBean<>(new UploadRateLimitFilter(rateLimiter, objectMapper));
        registration.addUrlPatterns("/v0/sessions/*");
        return registration;
    }
}
