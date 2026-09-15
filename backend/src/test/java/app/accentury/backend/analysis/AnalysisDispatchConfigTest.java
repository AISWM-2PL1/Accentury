package app.accentury.backend.analysis;

import app.accentury.backend.PropertiesFixture;
import app.accentury.backend.common.AccenturyProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import app.accentury.backend.training.TrainingSampleStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 분석 전달 조립의 설정 검증 (KAN-24).
 * <p>
 * processing-timeout이 AI 재전송 최악 소요보다 짧으면 살아 있는 워커의 작업을 스위퍼가
 * 먼저 종결한다 - 관계가 설정 두 곳에 갈라져 있어 기동 시점 검증으로 고정한다.
 */
class AnalysisDispatchConfigTest {

    @Test
    void 실행_잔류_한도가_재전송_최악_소요_이하면_기동을_거부한다() {
        // ai-timeout 85s x (재시도 2 + 1) + 백오프 0.9s = 255.9s > processing-timeout 240s (KAN-172 값)
        AnalysisDispatchConfig config = new AnalysisDispatchConfig();
        // 검증이 조립보다 먼저 실행되므로 협력자는 쓰이지 않는다.
        assertThrows(IllegalStateException.class, () -> config.analysisDispatcher(
                props(Duration.ofSeconds(240)), null, null, null, null, null, null, noStore()));
    }

    @Test
    void 기본_설정_조합은_검증을_통과해_실제_디스패처를_조립한다() {
        // 기본값 300s > 255.9s - 기본 설정이 스스로 어긋나면 여기서 잡힌다 (KAN-172).
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AnalysisDispatcher dispatcher = new AnalysisDispatchConfig().analysisDispatcher(
                props(Duration.ofSeconds(300)), new ThreadPoolTaskExecutor(), null,
                new AnalysisBacklog(), TestMetrics.analysisMetrics(meterRegistry), new ObjectMapper(),
                meterRegistry, noStore());

        assertInstanceOf(HttpAnalysisDispatcher.class, dispatcher);
        // 회로 상태 게이지가 등록되고 닫힘(0)으로 시작한다 (KAN-36) - CloudWatch 경보 ai-circuit-open의 입력이다.
        assertEquals(0.0, meterRegistry.get(AnalysisDispatchConfig.CIRCUIT_STATE_METRIC).gauge().value());
    }

    @Test
    void 종료_예산이_ai_타임아웃_이하면_기동을_거부한다() {
        // shutdown-budget 85s <= ai-timeout 85s - 종료 때마다 실행 중 분석이 예산 초과로 실패한다 (KAN-166).
        // processing-timeout은 검증을 통과하는 300s로 둔다 - 그 검증이 먼저 돌아 이 검사를 가리면 안 된다.
        AccenturyProperties props = PropertiesFixture.withAnalysis(
                PropertiesFixture.analysis(6, "http://ai.test", Duration.ofSeconds(300), Duration.ofSeconds(85)));

        assertThrows(IllegalStateException.class, () -> new AnalysisDispatchConfig().analysisDispatcher(
                props, null, null, null, null, null, null, noStore()));
    }

    @Test
    void 기본_종료_예산은_ai_타임아웃과_컨테이너_유예_사이에_든다() {
        // KAN-172 정합: shutdown-budget 90s > ai-timeout 85s, 웹 유예 15s + 90s = 105s <= ECS stopTimeout 120s (KAN-166).
        AccenturyProperties.Analysis analysis = PropertiesFixture.analysis();
        assertTrue(analysis.shutdownBudget().compareTo(analysis.aiTimeout()) > 0);
        assertTrue(Duration.ofSeconds(15).plus(analysis.shutdownBudget()).compareTo(Duration.ofSeconds(120)) <= 0);
    }

    @Test
    void 기본_큐_용량은_큐_유실_한도_안에_소진되는_크기다() {
        // 워커 1개가 1건 10초(KAN-57)로 비우므로 큐 30건 = 5분 = queued-timeout. 그 뒤는 스위퍼가 정리하니
        // 접수 시점에 503으로 미는 편이 낫다 (KAN-172).
        AccenturyProperties.Analysis analysis = PropertiesFixture.analysis();
        ThreadPoolTaskExecutor executor = new AnalysisDispatchConfig().analysisExecutor(
                PropertiesFixture.withAnalysis(analysis));
        assertEquals(30, AnalysisDispatchConfig.QUEUE_CAPACITY);
        assertEquals(AnalysisDispatchConfig.QUEUE_CAPACITY, executor.getQueueCapacity());
        assertEquals(1, executor.getCorePoolSize());
        assertTrue(Duration.ofSeconds(10L * AnalysisDispatchConfig.QUEUE_CAPACITY)
                .compareTo(analysis.queuedTimeout()) <= 0);
    }

    @Test
    void 연결_실패는_읽기_타임아웃을_기다리지_않고_미도달로_끊긴다() {
        // 연결과 읽기 마감을 같은 값으로 두면 조용히 버려지는 연결 시도에서 어느 쪽이 먼저 만료될지 정해지지
        // 않아, 미도달이 읽기 타임아웃(재전송 없음, 예산 소모 - KAN-172)으로 접힐 수 있다 (Codex astra 리뷰 P2).
        // 실제 JDK 요청 팩토리로 라우팅되지 않는 주소를 부른다 - 연결 마감 안에 UNREACHED로 돌아와야 한다.
        RestClient restClient = AnalysisDispatchConfig.restClient("http://10.255.255.1:9",
                Duration.ofMillis(500), Duration.ofSeconds(30));
        RestAiAnalysisClient client = new RestAiAnalysisClient(restClient, restClient, new ObjectMapper(), null);
        AnalysisDispatcher.AnalysisRequest request = new AnalysisDispatcher.AnalysisRequest(
                "a_connect", "s_connect", "v1", null, "gn-2026.08.1", "sv-0.3", null, 3000, new byte[] {1, 2, 3});

        long started = System.nanoTime();
        AiAnalysisClient.AiUnavailableException e = assertThrows(AiAnalysisClient.AiUnavailableException.class,
                () -> client.analyze(request, "c_connect"));

        assertEquals(AiAnalysisClient.AiUnavailableException.Kind.UNREACHED, e.kind());
        // 읽기 마감(30초)이 아니라 연결 마감(0.5초) 또는 즉시 거절로 끝난다.
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(10)) < 0);
        assertTrue(AnalysisDispatchConfig.AI_CONNECT_TIMEOUT.compareTo(PropertiesFixture.analysis().aiTimeout()) < 0);
    }

    @Test
    void 개발_모드에서는_종료_예산_검증_없이_noop_디스패처를_조립한다() {
        // ai-base-url이 없으면 전달 자체가 없다 - 예산은 의미가 없고 검증도 돌지 않는다.
        AccenturyProperties props = PropertiesFixture.withAnalysis(
                PropertiesFixture.analysis(6, null, Duration.ofSeconds(300), Duration.ofSeconds(1)));

        AnalysisDispatcher dispatcher = new AnalysisDispatchConfig().analysisDispatcher(
                props, null, null, null, null, null, null, noStore());

        assertInstanceOf(NoopAnalysisDispatcher.class, dispatcher);
    }

    /** 기본값 조합에 ai-base-url만 지정한 설정 - processing-timeout만 시나리오별로 바꾼다. */
    private static AccenturyProperties props(Duration processingTimeout) {
        return PropertiesFixture.withAnalysis(
                PropertiesFixture.analysis(6, "http://ai.test", processingTimeout));
    }

    /** 학습 데이터 버킷 없는 배포의 자리 (KAN-201) - 빈이 없어 NONE으로 채워지는 경로다. */
    private static ObjectProvider<TrainingSampleStore> noStore() {
        return new ObjectProvider<>() {
            @Override
            public Stream<TrainingSampleStore> stream() {
                return Stream.empty();
            }
        };
    }
}
