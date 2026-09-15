package app.accentury.backend.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * {@link HttpMetrics} 등록 (KAN-38).
 * <p>
 * {@code @Component}가 아니라 등록 빈인 이유는 {@code UploadRateLimitFilterConfig}와 같다 -
 * Filter 빈은 {@code @WebMvcTest} 슬라이스에도 딸려 들어가, 지표와 무관한 슬라이스 테스트가
 * {@link MeterRegistry}까지 요구하게 된다.
 * <p>
 * 순서는 {@code CorrelationIdFilter}(HIGHEST_PRECEDENCE) 바로 다음이다. 요청 제한 필터보다
 * 바깥이어야 429도 세어지고, correlation ID 필터보다 안쪽이어야 계측이 도는 동안 로그에 추적
 * ID가 붙어 있다.
 */
@Configuration
class HttpMetricsConfig {

    @Bean
    FilterRegistrationBean<HttpMetrics> httpMetricsFilter(MeterRegistry meterRegistry) {
        FilterRegistrationBean<HttpMetrics> registration =
                new FilterRegistrationBean<>(new HttpMetrics(meterRegistry));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
