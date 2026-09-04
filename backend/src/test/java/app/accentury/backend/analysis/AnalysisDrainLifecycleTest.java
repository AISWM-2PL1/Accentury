package app.accentury.backend.analysis;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.TestSessions;
import app.accentury.backend.session.TestSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 종료 시 분석 워커 배수의 명세 (KAN-166).
 * <p>
 * 실행 중 작업은 예산 안에 스스로 종결하고, 대기 작업은 즉시 실패로 정리하며, 예산을 넘긴
 * 작업은 실패로 남기고 워커를 중단한다. 실제 풀(워커 1)과 래치로 막히는 AI 클라이언트로
 * "실행 중"과 "대기"를 갈라 만든다.
 */
class AnalysisDrainLifecycleTest extends IntegrationTest {

    @Autowired
    private AnalysisJobRepository repository;

    @Autowired
    private TestSessionRepository sessionRepository;

    @Autowired
    private AnalysisJobTransitions transitions;

    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        TestSessions.ensure(sessionRepository, "s_drain");
        executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("drain-test-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();
    }

    @AfterEach
    void tearDown() {
        executor.getThreadPoolExecutor().shutdownNow();
    }

    /** 첫 호출에서 래치가 풀릴 때까지 막히는 클라이언트 - "실행 중" 작업을 만든다. */
    static class BlockingClient implements AiAnalysisClient {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean healthy() {
            return true;
        }

        @Override
        public Outcome analyze(AnalysisDispatcher.AnalysisRequest request, String correlationId) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("테스트가 래치를 풀지 않았다");
                }
            } catch (InterruptedException e) {
                // shutdownNow()의 중단 - 실제 HTTP 클라이언트가 끊기는 것과 같은 모양이다.
                Thread.currentThread().interrupt();
                throw new IllegalStateException("워커가 중단됐다", e);
            }
            return new Completed(70, "OK", "fake-0.1", "sv-0.3");
        }
    }

    @Test
    void 대기_작업은_즉시_실패하고_실행_중_작업은_예산_안에_종결된다() throws Exception {
        BlockingClient client = new BlockingClient();
        AnalysisBacklog backlog = new AnalysisBacklog();
        HttpAnalysisDispatcher dispatcher = dispatcher(client, executor, backlog);
        AnalysisJob running = saveProcessingJob();
        AnalysisJob queued1 = saveProcessingJob();
        AnalysisJob queued2 = saveProcessingJob();
        AnalysisDispatcher.AnalysisRequest queuedRequest1 = request(queued1);
        AnalysisDispatcher.AnalysisRequest queuedRequest2 = request(queued2);
        dispatcher.dispatch(request(running));
        assertTrue(client.entered.await(5, TimeUnit.SECONDS));
        dispatcher.dispatch(queuedRequest1);
        dispatcher.dispatch(queuedRequest2);
        assertEquals(3, backlog.inFlight());

        // 종료 신호가 온 뒤 잠시 뒤에 AI가 답한다 - 실행 중 작업이 예산 안에 끝나는 경우다.
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            client.release.countDown();
        });
        releaser.start();
        AnalysisDrainLifecycle.Drained drained =
                new AnalysisDrainLifecycle(dispatcher, executor, backlog, Duration.ofSeconds(10)).drain();
        releaser.join();

        assertEquals(2, drained.cancelled());
        assertEquals(1, drained.finished());
        assertEquals(0, drained.overBudget());
        assertTrue(drained.terminated());
        assertEquals(AnalysisJobStatus.COMPLETED, status(running));
        assertEquals(70, repository.findById(running.id()).orElseThrow().intonationScore());
        // 대기 작업은 AI에 닿지 않았으므로 시도 예산에서 빠지는 사유다.
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, status(queued1));
        assertEquals("ANALYSIS_UNAVAILABLE", repository.findById(queued1.id()).orElseThrow().errorCode());
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, status(queued2));
        // 취소된 작업의 버퍼는 배수가 지운다 (KAN-27 소유권 계약) - 워커는 그 작업을 만지지 않는다.
        assertArrayEquals(new byte[] {0, 0, 0}, queuedRequest1.audio());
        assertArrayEquals(new byte[] {0, 0, 0}, queuedRequest2.audio());
        assertEquals(1, client.calls.get());
        assertEquals(0, backlog.inFlight());
    }

    @Test
    void 예산을_넘긴_실행_중_작업은_ANALYSIS_TIMEOUT으로_정리되고_워커가_중단된다() throws Exception {
        BlockingClient client = new BlockingClient();
        AnalysisBacklog backlog = new AnalysisBacklog();
        HttpAnalysisDispatcher dispatcher = dispatcher(client, executor, backlog);
        AnalysisJob stuck = saveProcessingJob();
        AnalysisDispatcher.AnalysisRequest stuckRequest = request(stuck);
        dispatcher.dispatch(stuckRequest);
        assertTrue(client.entered.await(5, TimeUnit.SECONDS));

        AnalysisDrainLifecycle.Drained drained =
                new AnalysisDrainLifecycle(dispatcher, executor, backlog, Duration.ofMillis(300)).drain();

        assertFalse(drained.terminated());
        assertEquals(1, drained.overBudget());
        assertEquals(0, drained.finished());
        // AI에 닿았을 수 있으므로 시도 예산에 포함되는 사유 - 실행 잔류 스위퍼와 같은 판단이다.
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, status(stuck));
        assertEquals("ANALYSIS_TIMEOUT", repository.findById(stuck.id()).orElseThrow().errorCode());
        // 중단된 워커가 finally를 지나며 버퍼와 백로그를 정리한다 - 늦은 INTERNAL_ERROR 종결은 버려진다.
        assertTrue(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS));
        assertArrayEquals(new byte[] {0, 0, 0}, stuckRequest.audio());
        assertEquals(0, backlog.inFlight());
        assertEquals("ANALYSIS_TIMEOUT", repository.findById(stuck.id()).orElseThrow().errorCode());
    }

    @Test
    void 종료_신호_뒤_워커가_집은_대기_작업은_AI를_부르지_않는다() throws Exception {
        // failQueued()의 취소 표시보다 워커가 먼저 대기 작업을 집는 경우 (Codex sol 리뷰 P1) -
        // 신호만 주고 취소는 하지 않은 채 실행 중 작업을 끝내 워커가 다음 작업을 집게 한다.
        BlockingClient client = new BlockingClient();
        AnalysisBacklog backlog = new AnalysisBacklog();
        HttpAnalysisDispatcher dispatcher = dispatcher(client, executor, backlog);
        AnalysisJob running = saveProcessingJob();
        AnalysisJob queued = saveProcessingJob();
        AnalysisDispatcher.AnalysisRequest queuedRequest = request(queued);
        dispatcher.dispatch(request(running));
        assertTrue(client.entered.await(5, TimeUnit.SECONDS));
        dispatcher.dispatch(queuedRequest);

        dispatcher.refuseNew();
        client.release.countDown();
        executor.getThreadPoolExecutor().shutdown();
        assertTrue(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(AnalysisJobStatus.COMPLETED, status(running));
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, status(queued));
        assertEquals("ANALYSIS_UNAVAILABLE", repository.findById(queued.id()).orElseThrow().errorCode());
        assertArrayEquals(new byte[] {0, 0, 0}, queuedRequest.audio());
        assertEquals(1, client.calls.get());
        assertEquals(0, backlog.inFlight());
    }

    @Test
    void 종료_중_새_전달은_거절되고_버퍼는_지워진다() {
        AnalysisBacklog backlog = new AnalysisBacklog();
        HttpAnalysisDispatcher dispatcher = dispatcher(new BlockingClient(), executor, backlog);
        AnalysisJob job = saveProcessingJob();
        AnalysisDispatcher.AnalysisRequest late = request(job);
        dispatcher.refuseNew();

        // 큐 만원과 같은 예외 계층이다 - 업로드 서비스가 같은 경로(RETRYABLE_FAILED + 503)로 처리한다.
        assertThrows(TaskRejectedException.class, () -> dispatcher.dispatch(late));

        assertArrayEquals(new byte[] {0, 0, 0}, late.audio());
        assertEquals(0, backlog.inFlight());
        assertEquals(AnalysisJobStatus.PROCESSING, status(job));
    }

    @Test
    void 종료_중에는_재전송을_시작하지_않는다() {
        AnalysisJob job = saveProcessingJob();
        AtomicInteger calls = new AtomicInteger();
        HttpAnalysisDispatcher[] holder = new HttpAnalysisDispatcher[1];
        // 첫 호출 도중 종료 신호가 온 뒤 일시 장애가 난 경우 - 다음 시도는 예산만 먹는다.
        AiAnalysisClient client = new AiAnalysisClient() {
            @Override
            public boolean healthy() {
                return true;
            }

            @Override
            public Outcome analyze(AnalysisDispatcher.AnalysisRequest request, String correlationId) {
                calls.incrementAndGet();
                holder[0].refuseNew();
                throw new AiUnavailableException("연결 실패", AiUnavailableException.Kind.UNREACHED, null);
            }
        };
        holder[0] = new HttpAnalysisDispatcher(client, new SyncTaskExecutor(), transitions,
                new AnalysisBacklog(), openCircuitNever(), TestMetrics.analysisMetrics(), 2, 0);

        holder[0].dispatch(request(job));

        assertEquals(1, calls.get());
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, status(job));
        // 서버 사정이지 사용자 잘못이 아니다 - 시도 예산에서 빠지는 사유로 접는다.
        assertEquals("ANALYSIS_UNAVAILABLE", repository.findById(job.id()).orElseThrow().errorCode());
    }

    @Test
    void readiness_하강이_새_전달을_끊고_기동_완료가_다시_연다() {
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();
        AnalysisDispatcher dispatcher = new AnalysisDispatcher() {
            @Override
            public void dispatch(AnalysisRequest request) {
            }

            @Override
            public void refuseNew() {
                refused.incrementAndGet();
            }

            @Override
            public void acceptNew() {
                accepted.incrementAndGet();
            }
        };
        AnalysisDrainLifecycle lifecycle =
                new AnalysisDrainLifecycle(dispatcher, executor, new AnalysisBacklog(), Duration.ofSeconds(1));

        lifecycle.onReadinessChange(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));
        assertEquals(1, refused.get());
        assertEquals(0, accepted.get());

        lifecycle.onReadinessChange(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
        assertEquals(1, accepted.get());
    }

    @Test
    void 배수_phase는_웹_서버_정지보다_낮다() {
        // 낮은 phase가 나중에 멈춘다 - 요청이 더 들어오지 않는 상태에서 워커를 배수해야 한다.
        assertTrue(AnalysisDrainLifecycle.PHASE
                < org.springframework.boot.web.server.context.WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE);
    }

    // === 헬퍼 ===

    /** 재전송 0회, 백오프 0ms - 배수 규칙만 본다. */
    private HttpAnalysisDispatcher dispatcher(AiAnalysisClient client, ThreadPoolTaskExecutor executor,
                                              AnalysisBacklog backlog) {
        return new HttpAnalysisDispatcher(client, executor, transitions, backlog,
                openCircuitNever(), TestMetrics.analysisMetrics(), 0, 0);
    }

    private static AiCircuitBreaker openCircuitNever() {
        return new AiCircuitBreaker(Integer.MAX_VALUE, Duration.ofSeconds(5),
                Duration.ofSeconds(60), Clock.systemUTC());
    }

    private AnalysisJob saveProcessingJob() {
        return repository.save(new AnalysisJob("a_" + UUID.randomUUID(), "s_drain", "v1", 1,
                "idem-" + UUID.randomUUID(), AnalysisJobStatus.PROCESSING, Instant.now()));
    }

    private AnalysisJobStatus status(AnalysisJob job) {
        return repository.findById(job.id()).orElseThrow().status();
    }

    private static AnalysisDispatcher.AnalysisRequest request(AnalysisJob job) {
        return new AnalysisDispatcher.AnalysisRequest(job.id(), job.sessionId(), job.itemId(), null,
                "gn-2026.08.1", "sv-0.3", 3000, new byte[] {1, 2, 3});
    }
}
