package app.accentury.backend.analysis;

import app.accentury.backend.common.AccenturyProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

/**
 * 분석 전달 경로의 조립 (KAN-24).
 * <p>
 * {@code accentury.analysis.ai-base-url}이 있으면 실제 AI 호출 디스패처를, 없으면
 * 아무것도 전달하지 않는 {@link NoopAnalysisDispatcher}를 쓴다 - AI 서버(KAN-22·36) 없이
 * BE만 띄우는 개발 모드다. 조건부 애너테이션 대신 조립 지점의 분기 하나로 정한다.
 */
@Configuration(proxyBeanMethods = false)
class AnalysisDispatchConfig {

    /**
     * 워커 수를 넘는 전달 요청의 대기 한도. 넘치면 제출이 거절되고 업로드가 503으로
     * 끝난다 - 무한 큐로 받았다가 타임아웃으로 전부 버리는 것보다 일찍 미는 쪽을 택한다.
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
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.analysis().aiTimeout()).build());
        requestFactory.setReadTimeout(properties.analysis().aiTimeout());
        // Boot의 RestClient.Builder 자동 구성은 webmvc 스타터에 없다 - 내부 호출 하나라 정적 빌더로 충분하다
        RestClient restClient = RestClient.builder()
                .baseUrl(aiBaseUrl)
                .requestFactory(requestFactory)
                .build();
        return new HttpAnalysisDispatcher(new RestAiAnalysisClient(restClient, objectMapper),
                analysisExecutor, transitions, backlog, properties.analysis().aiRetries());
    }
}
