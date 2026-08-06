package app.accentury.backend.common;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 스탠드얼론 웹 테스트(KAN-31)를 위한 CORS 허용 (KAN-23, API 명세서 §2.5).
 * <p>
 * 오리진 allowlist는 설정({@code accentury.cors.allowed-origins})으로 관리한다 -
 * 비어 있으면 아무 매핑도 등록하지 않아 교차 출처 요청이 차단된다.
 * 쿠키를 쓰지 않으므로(세션은 Bearer 토큰) credentials는 허용하지 않는다.
 * <p>
 * {@code @EnableConfigurationProperties}: WebMvcConfigurer는 {@code @WebMvcTest}
 * 슬라이스에도 포함되므로, 슬라이스가 설정 빈을 따로 몰라도 되게 여기서 활성화한다.
 */
@Configuration
@EnableConfigurationProperties(AccenturyProperties.class)
class CorsConfig implements WebMvcConfigurer {

    private final AccenturyProperties properties;

    CorsConfig(AccenturyProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = properties.cors().allowedOrigins();
        if (origins.isEmpty()) {
            return;
        }
        registry.addMapping("/v0/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id")
                .exposedHeaders("X-Correlation-Id", "Retry-After", "ETag")
                .maxAge(3600);
    }
}
