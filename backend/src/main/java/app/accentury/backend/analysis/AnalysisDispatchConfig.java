package app.accentury.backend.analysis;

import app.accentury.backend.common.AccenturyProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
        // 워커 풀과 큐는 인스턴스별이고, 다중 인스턴스에서도 그대로 둔다 (KAN-167 결정). 원본 음성이
        // 요청 처리 중 메모리에만 있고 어디에도 저장되지 않아(FR-DP-01) 다른 프로세스의 워커에
        // 넘길 수 없다 - SQS는 메시지 상한 256KB에 최대 1MB WAV를 싣지 못하고, 넘기려면 S3 같은
        // 저장소가 필요해 "저장하지 않는다"는 결정이 깨진다. 오디오를 받은 인스턴스가 자기 워커로
        // AI를 부르고 결과를 DB에 쓰므로, 폴링은 어느 인스턴스가 받아도 DB만 보면 된다. 그래서
        // 큐 용량과 워커 수는 인스턴스 하나의 몫이고, 태스크 수만큼 AI 동시 호출이 늘어난다 -
        // 오토스케일링 상한(KAN-168, 최대 3)이 GPU 동시 슬롯을 넘지 않게 잡는 이유다.
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("analysis-");
        executor.setCorePoolSize(properties.analysis().dispatchConcurrency());
        executor.setMaxPoolSize(properties.analysis().dispatchConcurrency());
        executor.setQueueCapacity(QUEUE_CAPACITY);
        // 종료 처리 (KAN-166). 이 값이 true면 Spring은 컨텍스트 close 이벤트에서 풀을 일시정지하거나
        // 조기 종료하지 않고(lateShutdown) 빈 파괴 시점에 shutdown만 한다 - 그래서 웹 서버가 멎은
        // 뒤에 도는 AnalysisDrainLifecycle이 큐 취소와 예산 대기를 통제할 수 있다. awaitTermination은
        // 일부러 걸지 않는다 - 배수가 예산을 다 쓴 뒤 파괴 시점에 같은 예산을 또 기다리면 컨테이너
        // 유예(110초)를 넘긴다 (Codex sol 리뷰 P1). 대기는 AnalysisDrainLifecycle의 마감시각 하나다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }

    /** 회로 상태 게이지 이름 (KAN-36). CloudWatch에는 레지스트리가 .value를 붙여 내보내고 경보가 그 이름을 본다 (infra/modules/monitoring). */
    static final String CIRCUIT_STATE_METRIC = "accentury.ai.circuit.state";

    @Bean
    AnalysisDispatcher analysisDispatcher(AccenturyProperties properties,
                                          ThreadPoolTaskExecutor analysisExecutor,
                                          AnalysisJobTransitions transitions,
                                          AnalysisBacklog backlog,
                                          ObjectMapper objectMapper,
                                          MeterRegistry meterRegistry) {
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
        // 종료 예산(shutdown-budget)은 AI 호출 1회보다 길어야 한다 (KAN-166) - 짧으면 종료 때마다
        // 실행 중이던 분석이 예산 초과로 실패해 그 사용자는 매번 재녹음한다. 대기 작업은 기다리지
        // 않으므로 재전송 횟수는 여기 들어가지 않는다.
        if (properties.analysis().shutdownBudget().compareTo(properties.analysis().aiTimeout()) <= 0) {
            throw new IllegalStateException("shutdown-budget("
                    + properties.analysis().shutdownBudget() + ")이 ai-timeout("
                    + properties.analysis().aiTimeout() + ") 이하다 - 종료 때마다 실행 중 분석이 실패한다");
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
        // 회로 상태를 지표로 낸다 (KAN-36). 배포에서는 CloudWatch 레지스트리(application-deploy.yml)가 1분마다
        // 읽어 올리고 경보(ai-circuit-open)가 본다 - AI가 떠 있어도 추론만 죽은 장애는 이 지표만 잡는다.
        // 게이지는 약한 참조라 회로 차단기를 디스패처가 붙들고 있는 동안만 산다 - 그 수명이 곧 앱 수명이다.
        Gauge.builder(CIRCUIT_STATE_METRIC, circuitBreaker, AiCircuitBreaker::stateValue)
                .description("AI 회로 차단기 상태 - 0 닫힘, 1 반열림, 2 열림 (KAN-28, KAN-36)")
                .register(meterRegistry);
        return new HttpAnalysisDispatcher(
                new RestAiAnalysisClient(restClient, healthRestClient, objectMapper,
                        properties.analysis().aiToken()),
                analysisExecutor, transitions, backlog, circuitBreaker,
                properties.analysis().aiRetries());
    }

    /**
     * 종료 시 워커 배수 (KAN-166). 개발 모드({@link NoopAnalysisDispatcher})에서도 조립한다 -
     * 풀이 비어 있어 즉시 끝나지만 종료 순서 로그는 같은 모양으로 남는다.
     */
    @Bean
    AnalysisDrainLifecycle analysisDrainLifecycle(AnalysisDispatcher analysisDispatcher,
                                                  ThreadPoolTaskExecutor analysisExecutor,
                                                  AnalysisBacklog backlog,
                                                  AccenturyProperties properties) {
        return new AnalysisDrainLifecycle(analysisDispatcher, analysisExecutor, backlog,
                properties.analysis().shutdownBudget());
    }

    private static RestClient restClient(String baseUrl, Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
