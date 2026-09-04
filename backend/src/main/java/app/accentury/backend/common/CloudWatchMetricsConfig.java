package app.accentury.backend.common;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.config.MeterFilter;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

/**
 * CloudWatch 지표 내보내기 (KAN-36).
 * <p>
 * AI가 전용 호스트로 갈라지면서 "AI 회로가 열렸다"를 밖에서 볼 길이 필요해졌다 - backend는 ALB 뒤에
 * 있어도 회로 상태는 어떤 표준 지표에도 나오지 않는다. Micrometer의 CloudWatch 레지스트리가 1분마다
 * 지표를 올리고, Terraform {@code infra/modules/monitoring}의 경보 {@code ai-circuit-open}이 그것을 본다.
 * <p>
 * Spring Boot 4는 CloudWatch 레지스트리를 자동 구성하지 않으므로(export 자동 구성 목록에 없다, 2026-09-01
 * 확인) 여기서 조립한다. 프로퍼티 이름은 Boot의 다른 레지스트리와 같은 모양
 * ({@code management.cloudwatch.metrics.export.*})으로 맞춰 둔다 - 배포 프로파일에서만 켠다
 * (application-deploy.yml). 로컬과 테스트는 켜지 않는다 - AWS 자격 증명도, 올릴 곳도 없다.
 * <p>
 * <b>{@code accentury.*} 지표만 내보낸다.</b> JVM, Tomcat, HTTP 요청 지표까지 올리면 이름마다 CloudWatch
 * 커스텀 지표 요금(개당 월 0.30달러)이 붙고 태그 조합만큼 늘어난다. 올리는 이름의 정본은
 * {@code ServiceMetrics}이고(KAN-38), 회로 상태(KAN-36)와 임시파일 잔존(KAN-27)도 그 접두사 안에 있다.
 * 이름을 늘릴 때의 요금 계산과 태그 규칙은 그 클래스에 적어 두었다. 필터는 이 레지스트리에만 건다 -
 * {@link MeterFilter} 빈으로 두면 Boot가 모든 레지스트리에 적용해 로컬 simple 레지스트리까지 좁힌다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "management.cloudwatch.metrics.export", name = "enabled", havingValue = "true")
class CloudWatchMetricsConfig {

    static final String PROPERTY_PREFIX = "management.cloudwatch.metrics.export.";

    /** 내보내는 지표의 이름 접두사 - 서비스 지표만 올린다. */
    static final String EXPORTED_PREFIX = "accentury.";

    /**
     * 비동기 클라이언트. 리전은 프로퍼티로 고정한다 - SDK 기본 체인은 IMDS까지 내려가 조회하는데, 그 왕복을
     * 기동 경로에 두지 않는다. 자격 증명은 기본 체인(인스턴스 프로파일, IMDSv2 hop limit 2)이고 첫 publish
     * 시점에야 읽는다. 호스트 역할의 PutMetricData는 네임스페이스 조건으로 좁혀져 있다 (compute 모듈 IAM).
     */
    @Bean
    @ConditionalOnMissingBean
    CloudWatchAsyncClient cloudWatchAsyncClient(Environment environment) {
        return CloudWatchAsyncClient.builder()
                .region(Region.of(environment.getRequiredProperty(PROPERTY_PREFIX + "region")))
                .build();
    }

    @Bean
    CloudWatchMeterRegistry cloudWatchMeterRegistry(Environment environment, Clock clock,
                                                    CloudWatchAsyncClient cloudWatchAsyncClient) {
        return new CloudWatchMeterRegistry(config(environment), clock, cloudWatchAsyncClient);
    }

    /** 이 레지스트리에만 거는 필터 - 다른 레지스트리(로컬 simple 등)는 건드리지 않는다. */
    @Bean
    MeterRegistryCustomizer<CloudWatchMeterRegistry> cloudWatchOnlyServiceMetrics() {
        return registry -> registry.config().meterFilter(serviceMetricsOnly());
    }

    static MeterFilter serviceMetricsOnly() {
        return MeterFilter.denyUnless(id -> id.getName().startsWith(EXPORTED_PREFIX));
    }

    /**
     * Micrometer {@link CloudWatchConfig}를 Spring 프로퍼티에 잇는다. 레지스트리는 {@code cloudwatch.namespace},
     * {@code cloudwatch.step} 같은 키로 묻고, 여기서는 그 접두사를 {@code management.cloudwatch.metrics.export.}로
     * 바꿔 찾는다. namespace는 필수다 - 비면 레지스트리가 기동 시점에 거부한다.
     */
    static CloudWatchConfig config(Environment environment) {
        return new CloudWatchConfig() {
            @Override
            public @Nullable String get(String key) {
                String suffix = key.startsWith(prefix() + ".") ? key.substring(prefix().length() + 1) : key;
                return environment.getProperty(PROPERTY_PREFIX + suffix);
            }
        };
    }
}
