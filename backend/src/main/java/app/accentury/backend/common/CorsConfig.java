package app.accentury.backend.common;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * 스탠드얼론 웹 테스트(KAN-31)를 위한 CORS 허용 (KAN-23, API 명세서 §2.5).
 * <p>
 * 오리진 allowlist는 설정({@code accentury.cors.allowed-origins})으로 관리한다 -
 * 비어 있으면 필터를 끄므로 교차 출처 요청이 허용되지 않는다.
 * 쿠키를 쓰지 않으므로(세션은 Bearer 토큰) credentials는 허용하지 않는다.
 * <p>
 * MVC 레벨({@code WebMvcConfigurer})이 아니라 서블릿 필터로 건다 - 업로드 요청 제한
 * 필터가 DispatcherServlet 앞에서 만드는 조기 429에도 CORS 헤더가 붙어야 브라우저가
 * 응답 본문과 Retry-After를 읽을 수 있다 (Codex sol 리뷰 P2).
 */
@Configuration
class CorsConfig {

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(AccenturyProperties properties) {
        List<String> origins = properties.cors().allowedOrigins();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        config.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After", "ETag"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v0/**", config);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        // correlation ID 필터(HIGHEST_PRECEDENCE) 다음, 요청 제한 필터(LOWEST)보다 앞
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.setEnabled(!origins.isEmpty());
        return registration;
    }
}
