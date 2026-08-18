package app.accentury.backend.analysis;

import app.accentury.backend.common.AccenturyProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * 분석 전달 경로의 조립 (KAN-24).
 * <p>
 * {@code accentury.analysis.ai-base-url}이 있으면 실제 AI 호출 디스패처를, 없으면
 * 아무것도 전달하지 않는 {@link NoopAnalysisDispatcher}를 쓴다 - AI 서버(KAN-22, 36) 없이
 * BE만 띄우는 개발 모드다. 조건부 애너테이션 대신 조립 지점의 분기 하나로 정한다.
 */
@Configuration(proxyBeanMethods = false)
class AnalysisDispatchConfig {

    /**
     * 워커 수를 넘는 전달 요청의 대기 한도. 넘치면 제출이 거절되고 업로드가 503으로 끝난다.
     * 무한 큐로 받았다가 타임아웃으로 전부 버리는 것보다 일찍 미는 쪽을 택한다.
     * 동시 응시 1,000명의 GPU 대기(약 50슬롯, §5.3)를 여유 있게 덮는 크기다.
     */
    private static final int QUEUE_CAPACITY = 200;

    @Bean
    ThreadPoolTaskExecutor analysisExecutor(AccenturyProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("analysis-");
        executor.setCorePoolSize(properties.analysis().dispatchConcurrency());
        executor.setMaxPoolSize(properties.analysis().dispatchConcurrency());
        executor.setQueueCapacity(QUEUE_CAPACITY);
        return executor;
    }

    @Bean
    AnalysisDispatcher analysisDispatcher(AccenturyProperties properties,
                                          ThreadPoolTaskExecutor analysisExecutor,
                                          AnalysisJobTransitions transitions,
                                          AnalysisBacklog backlog,
                                          ObjectMapper objectMapper) {
        String aiBaseUrl = properties.analysis().aiBaseUrl();
        if (aiBaseUrl == null || aiBaseUrl.isBlank()) {
            return new NoopAnalysisDispatcher();
        }
        int retries = properties.analysis().aiRetries();
        // 실행 잔류 한도(processing-timeout)는 AI 재전송 최악 소요보다 길어야 한다 - 짧으면
        // 살아 있는 워커의 작업을 스위퍼가 먼저 종결해, 이미 버려진 작업에 GPU를 쓰고 성공
        // 결과까지 폐기한다. 문서(AccenturyProperties)로만 있던 관계를 기동 시점에 강제한다.
        long worstCaseMs = properties.analysis().aiTimeout().toMillis() * (retries + 1)
                + HttpAnalysisDispatcher.RETRY_BACKOFF_MS * retries * (retries + 1) / 2;
        if (properties.analysis().processingTimeout().toMillis() <= worstCaseMs) {
            throw new IllegalStateException("processing-timeout("
                    + properties.analysis().processingTimeout() + ")이 AI 재전송 최악 소요("
                    + worstCaseMs + "ms)보다 짧다 - ai-timeout, ai-retries와 함께 조정해야 한다");
        }
        // Boot의 RestClient.Builder 자동 구성은 webmvc 스타터에 없다 - 내부 호출 하나라 정적 빌더로 충분하다.
        RestClient restClient = restClient(aiBaseUrl, properties.analysis().aiTimeout());
        // 회로 복구 프로브는 추론을 태우지 않으므로 훨씬 짧게 기다린다 (KAN-28) -
        // 스케줄러 스레드를 오래 붙들면 같은 풀의 다른 잡이 밀린다.
        RestClient healthRestClient = restClient(aiBaseUrl, properties.analysis().aiHealthTimeout());
        AiCircuitBreaker circuitBreaker = new AiCircuitBreaker(
                properties.analysis().circuitFailureThreshold(),
                properties.analysis().circuitProbeInterval(),
                // 반열림 시험의 슬롯 해제 한도 - 분석 1건의 실행 잔류 한도와 같은 값이다.
                properties.analysis().processingTimeout(), Clock.systemUTC());
        return new HttpAnalysisDispatcher(
                new RestAiAnalysisClient(restClient, healthRestClient, objectMapper),
                analysisExecutor, transitions, backlog, circuitBreaker,
                properties.analysis().aiRetries());
    }

    private static RestClient restClient(String baseUrl, Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
