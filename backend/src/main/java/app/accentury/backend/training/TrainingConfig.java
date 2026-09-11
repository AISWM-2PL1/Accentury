package app.accentury.backend.training;

import app.accentury.backend.common.AccenturyProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * 학습 샘플 저장의 배선 (KAN-201) - {@code accentury.training.bucket}이 있을 때만 전부 만들어진다.
 * <p>
 * 없으면(로컬, 테스트, prod) 이 클래스의 빈은 하나도 없다. S3 클라이언트도 없다 - 소비자
 * ({@code AnalysisDispatchConfig})가 {@link TrainingSampleStore#NONE}으로 자리를 채운다. 값이 있는데
 * 비어 있는 것("")은 설정 실수라 뜨지 않는다 - 조용히 no-op으로 접으면 staging에서 샘플이 안 쌓이는
 * 원인이 묻힌다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "accentury.training", name = "bucket")
class TrainingConfig {

    /**
     * 호출 1건(시도 재시도 포함)의 상한. 저장은 분석 워커(dispatch-concurrency 1)가 동기로 하므로 S3가 느리거나
     * 죽으면 그 시간만큼 뒤 작업의 큐 대기가 늘어난다 - SDK 기본값(소켓 30초 x 재시도 3회)이면 워커가 수 분
     * 멈춰 큐의 작업이 유실 한도(queued-timeout 5분)에 닿는다 (Codex astra 리뷰 P2). WAV 1MB 왕복은 수십 ms라
     * 시도 5초, 합계 10초면 넉넉하고, 넘기면 저장소가 실패로 삼킨다 - 분석 결과는 이미 확정된 뒤다.
     */
    static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(10);
    static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 자격 증명은 기본 제공자 체인이 태스크 역할에서 받는다 - 버킷 하나에 PutObject만 허용된 역할이다
     * (fargate 모듈). 리전은 설정({@code accentury.training.region})이 있으면 그 값이고, 없으면 SDK 기본
     * 체인(태스크의 {@code AWS_REGION})이다. 동기 클라이언트(apache)이고 타임아웃은 위 상수다.
     */
    @Bean
    S3Client trainingS3Client(AccenturyProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(CONNECTION_TIMEOUT)
                        .socketTimeout(API_CALL_ATTEMPT_TIMEOUT))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                        .build());
        String region = properties.training().region();
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region));
        }
        return builder.build();
    }

    @Bean
    TrainingSampleStore trainingSampleStore(S3Client trainingS3Client, AccenturyProperties properties,
                                            ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        String bucket = Objects.requireNonNull(properties.training().bucket());
        if (bucket.isBlank()) {
            throw new IllegalStateException("accentury.training.bucket이 비어 있다 - 학습 데이터 저장을 끄려면 "
                    + "값을 지우고(SSM ACCENTURY_TRAINING_BUCKET 없음), 켜려면 버킷 이름을 넣는다 (KAN-201)");
        }
        return new S3TrainingSampleStore(trainingS3Client, bucket, objectMapper, Clock.systemUTC(),
                meterRegistry);
    }
}
