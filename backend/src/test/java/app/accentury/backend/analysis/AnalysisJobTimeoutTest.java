package app.accentury.backend.analysis;

import app.accentury.backend.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PROCESSING 잔류 작업 타임아웃 처리의 명세 (KAN-24, Codex sol 리뷰 P1 반영).
 * <p>
 * 실행 잔류(startedAt 경과)와 큐 유실(startedAt 없이 createdAt 경과)은 한도와 사유가
 * 다르다 - 큐에서 정상 대기 중인 작업을 실행 잔류로 오인해 폐기하면 안 된다.
 */
class AnalysisJobTimeoutTest extends IntegrationTest {

    @Autowired
    private AnalysisJobRepository repository;

    @Autowired
    private AnalysisJobTransitions transitions;

    @Autowired
    private AnalysisJobTimeout timeout;

    @Test
    void 실행_잔류만_ANALYSIS_TIMEOUT으로_종결된다() {
        Instant now = Instant.now();
        AnalysisJob stuck = save("a_to-stuck", "v1", now.minus(Duration.ofMinutes(5)));
        repository.markStartedIfProcessing(stuck.id(), now.minus(Duration.ofMinutes(2)));
        AnalysisJob running = save("a_to-running", "v2", now.minus(Duration.ofSeconds(50)));
        repository.markStartedIfProcessing(running.id(), now.minus(Duration.ofSeconds(10)));

        timeout.failStuckJobs();

        AnalysisJob failed = repository.findById("a_to-stuck").orElseThrow();
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, failed.status());
        // AI에 닿았을 수 있으므로 시도 예산에 포함되는 사유다
        assertEquals("ANALYSIS_TIMEOUT", failed.errorCode());
        assertEquals(AnalysisJobStatus.PROCESSING, repository.findById("a_to-running").orElseThrow().status());
    }

    @Test
    void 큐_대기는_오래돼도_실행_잔류_한도로_폐기되지_않는다() {
        // 접수 후 2분이 지났지만 실행을 시작하지 않았다 = 큐에서 기다리는 중이라 정상이다
        save("a_to-queued", "v1", Instant.now().minus(Duration.ofMinutes(2)));

        timeout.failStuckJobs();

        assertEquals(AnalysisJobStatus.PROCESSING, repository.findById("a_to-queued").orElseThrow().status());
    }

    @Test
    void 큐_유실은_예산에서_빠지는_ANALYSIS_UNAVAILABLE로_종결된다() {
        // 30분 넘게 실행을 시작하지 못했다 = 접수와 실행 사이 프로세스 사망으로 큐가 유실된 것
        save("a_to-lost", "v1", Instant.now().minus(Duration.ofMinutes(31)));

        timeout.failStuckJobs();

        AnalysisJob lost = repository.findById("a_to-lost").orElseThrow();
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, lost.status());
        // AI에 닿지 않았으므로 시도 예산에서 빠지는 사유여야 한다
        assertEquals("ANALYSIS_UNAVAILABLE", lost.errorCode());
    }

    @Test
    void 이미_종결된_작업은_건드리지_않는다() {
        Instant now = Instant.now();
        AnalysisJob done = save("a_to-done", "v3", now.minus(Duration.ofMinutes(40)));
        repository.markStartedIfProcessing(done.id(), now.minus(Duration.ofMinutes(39)));
        transitions.complete(done.id(), 70, "OK", "rmvpe-0.2", "sv-0.3");

        timeout.failStuckJobs();

        assertEquals(AnalysisJobStatus.COMPLETED, repository.findById("a_to-done").orElseThrow().status());
    }

    private AnalysisJob save(String id, String itemId, Instant createdAt) {
        return repository.save(new AnalysisJob(id, "s_timeout", itemId, 1, id + "-key",
                AnalysisJobStatus.PROCESSING, createdAt));
    }
}
