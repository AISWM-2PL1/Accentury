package app.accentury.backend.training;

import app.accentury.backend.common.AccenturyProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 버킷 설정 유무가 빈 존재를 가른다 (KAN-201 AC) - 없으면 S3 클라이언트도 저장 빈도 없다. */
class TrainingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Collaborators.class, TrainingConfig.class)
            .withPropertyValues("accentury.session.ttl=30m");

    @Test
    void 버킷이_없으면_S3_클라이언트도_저장_빈도_없다() {
        runner.run(context -> {
            assertFalse(context.containsBean("trainingS3Client"));
            assertEquals(0, context.getBeansOfType(TrainingSampleStore.class).size());
        });
    }

    @Test
    void 버킷이_있으면_S3_저장_빈이_뜬다() {
        runner.withPropertyValues("accentury.training.bucket=accentury-staging-training-123456789012",
                        "accentury.training.region=ap-northeast-2")
                .run(context -> {
                    assertTrue(context.containsBean("trainingS3Client"));
                    assertInstanceOf(S3TrainingSampleStore.class, context.getBean(TrainingSampleStore.class));
                });
    }

    @Test
    void 빈_문자열_버킷은_설정_실수라_기동을_세운다() {
        // 리전을 명시한다 - CI 러너처럼 AWS 리전이 없는 환경에서는 SDK의 리전 오류가 먼저 나와 원인을 가린다.
        runner.withPropertyValues("accentury.training.bucket=", "accentury.training.region=ap-northeast-2")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertTrue(failure != null && rootMessage(failure).contains("accentury.training.bucket"),
                            () -> "기동 실패 사유가 다르다: " + failure);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AccenturyProperties.class)
    static class Collaborators {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    /** 예외 사슬을 끝까지 따라가 마지막 원인의 메시지를 돌려준다. */
    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }
}
