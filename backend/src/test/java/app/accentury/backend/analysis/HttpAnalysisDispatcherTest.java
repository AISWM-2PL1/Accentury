package app.accentury.backend.analysis;

import app.accentury.backend.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AI 전달 워커의 종결 규칙 (KAN-24).
 * <p>
 * 핵심은 "재시도 가능한 실패만 다시 큐잉한다"(AC)의 구분이다 - 일시 장애
 * (연결 실패, 타임아웃, 5xx)만 재전송하고, 분석 판정 실패는 같은 오디오에 같은 답이
 * 오므로 한 번으로 끝낸다. 동기 실행기로 돌려 결과를 바로 검증한다.
 */
class HttpAnalysisDispatcherTest extends IntegrationTest {

    @Autowired
    private AnalysisJobRepository repository;

    @Autowired
    private AnalysisJobTransitions transitions;

    /** 스크립트된 응답을 차례로 내는 AI 클라이언트 - 호출 횟수가 재전송 검증의 근거다 */
    static class ScriptedClient implements AiAnalysisClient {

        final Deque<Object> script = new ArrayDeque<>();
        int calls;

        ScriptedClient then(Object outcomeOrException) {
            script.add(outcomeOrException);
            return this;
        }

        @Override
        public Outcome analyze(AnalysisDispatcher.AnalysisRequest request, String correlationId) {
            calls++;
            Object next = script.pop();
            if (next instanceof AiUnavailableException e) {
                throw e;
            }
            return (Outcome) next;
        }
    }

    @Test
    void 성공_응답은_COMPLETED와_결과_필드로_종결된다() {
        AnalysisJob job = saveProcessingJob();
        ScriptedClient client = new ScriptedClient()
                .then(new AiAnalysisClient.Completed(78, "OK", "rmvpe-0.2+dtw-0.1", "sv-0.3"));

        dispatcher(client, 2).dispatch(request(job));

        AnalysisJob saved = repository.findById(job.id()).orElseThrow();
        assertEquals(AnalysisJobStatus.COMPLETED, saved.status());
        assertEquals(78, saved.intonationScore());
        assertEquals("OK", saved.qualityCode());
        assertEquals("rmvpe-0.2+dtw-0.1", saved.modelVersion());
        assertEquals("sv-0.3", saved.scoreVersion());
        assertEquals(1, client.calls);
    }

    @Test
    void 일시_장애는_재전송_후_성공할_수_있다() {
        AnalysisJob job = saveProcessingJob();
        ScriptedClient client = new ScriptedClient()
                .then(new AiAnalysisClient.AiUnavailableException("연결 실패",
                        AiAnalysisClient.AiUnavailableException.Kind.UNREACHED, null))
                .then(new AiAnalysisClient.Completed(60, "OK", "rmvpe-0.2", "sv-0.3"));

        dispatcher(client, 2).dispatch(request(job));

        assertEquals(AnalysisJobStatus.COMPLETED, repository.findById(job.id()).orElseThrow().status());
        assertEquals(2, client.calls);
    }

    @Test
    void 재전송_예산을_다_쓰면_RETRYABLE_FAILED_ANALYSIS_UNAVAILABLE이다() {
        AnalysisJob job = saveProcessingJob();
        ScriptedClient client = new ScriptedClient()
                .then(new AiAnalysisClient.AiUnavailableException("연결 실패",
                        AiAnalysisClient.AiUnavailableException.Kind.UNREACHED, null))
                .then(new AiAnalysisClient.AiUnavailableException("연결 실패",
                        AiAnalysisClient.AiUnavailableException.Kind.UNREACHED, null))
                .then(new AiAnalysisClient.AiUnavailableException("연결 실패",
                        AiAnalysisClient.AiUnavailableException.Kind.UNREACHED, null));

        dispatcher(client, 2).dispatch(request(job));

        AnalysisJob saved = repository.findById(job.id()).orElseThrow();
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, saved.status());
        assertEquals("ANALYSIS_UNAVAILABLE", saved.errorCode());
        assertEquals(3, client.calls); // 최초 1회 + 재전송 2회
    }

    @Test
    void 타임아웃_소진은_ANALYSIS_TIMEOUT으로_구분된다() {
        AnalysisJob job = saveProcessingJob();
        ScriptedClient client = new ScriptedClient()
                .then(new AiAnalysisClient.AiUnavailableException("읽기 타임아웃",
                        AiAnalysisClient.AiUnavailableException.Kind.TIMED_OUT, null));

        dispatcher(client, 0).dispatch(request(job));

        assertEquals("ANALYSIS_TIMEOUT", repository.findById(job.id()).orElseThrow().errorCode());
    }

    @Test
    void AI_5xx_소진은_예산에_포함되는_INTERNAL_ERROR로_종결된다() {
        // 요청이 AI에 도달해 추론까지 했을 수 있다 - 미도달(ANALYSIS_UNAVAILABLE, 예산 제외)과
        // 같은 코드로 접으면 시도 상한이 우회된다 (Codex sol 리뷰 P2)
        AnalysisJob job = saveProcessingJob();
        ScriptedClient client = new ScriptedClient()
                .then(new AiAnalysisClient.AiUnavailableException("AI 5xx",
                        AiAnalysisClient.AiUnavailableException.Kind.SERVER_ERROR, null));

        dispatcher(client, 0).dispatch(request(job));

        AnalysisJob saved = repository.findById(job.id()).orElseThrow();
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, saved.status());
        assertEquals("INTERNAL_ERROR", saved.errorCode());
    }

    @Test
    void 판정_실패는_재전송_없이_한_번으로_끝난다() {
        AnalysisJob job = saveProcessingJob();
        ScriptedClient client = new ScriptedClient()
                .then(new AiAnalysisClient.Rejected("AUDIO_TOO_QUIET", true));

        dispatcher(client, 2).dispatch(request(job));

        AnalysisJob saved = repository.findById(job.id()).orElseThrow();
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, saved.status());
        assertEquals("AUDIO_TOO_QUIET", saved.errorCode());
        assertEquals(1, client.calls); // 같은 오디오에 같은 답 - 재시도 가능한 실패만 다시 큐잉한다 (AC)
    }

    @Test
    void 비재시도_판정_실패는_FAILED다() {
        AnalysisJob job = saveProcessingJob();
        ScriptedClient client = new ScriptedClient()
                .then(new AiAnalysisClient.Rejected("INTERNAL_ERROR", false));

        dispatcher(client, 2).dispatch(request(job));

        AnalysisJob saved = repository.findById(job.id()).orElseThrow();
        assertEquals(AnalysisJobStatus.FAILED, saved.status());
        assertEquals("INTERNAL_ERROR", saved.errorCode());
    }

    @Test
    void 이미_종결된_작업은_AI를_호출하지_않는다() {
        AnalysisJob job = saveProcessingJob();
        transitions.fail(job.id(), AnalysisJobStatus.RETRYABLE_FAILED, "ANALYSIS_TIMEOUT");
        ScriptedClient client = new ScriptedClient()
                .then(new AiAnalysisClient.Completed(95, "OK", "rmvpe-0.2", "sv-0.3"));

        dispatcher(client, 0).dispatch(request(job));

        // 큐에서 기다리다 타임아웃 종결된 작업이 뒤늦게 GPU를 쓰면 안 된다 (Codex sol 리뷰 P1)
        assertEquals(0, client.calls);
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, repository.findById(job.id()).orElseThrow().status());
    }

    @Test
    void 종결_후_늦게_도착한_결과는_조건부_전이가_버린다() {
        // 생존 확인과 AI 응답 사이에 스위퍼가 종결하는 경합 - 마지막 방어선은 조건부 UPDATE다
        AnalysisJob job = saveProcessingJob();
        transitions.fail(job.id(), AnalysisJobStatus.RETRYABLE_FAILED, "ANALYSIS_TIMEOUT");

        transitions.complete(job.id(), 95, "OK", "rmvpe-0.2", "sv-0.3");

        // 타임아웃 종결이 그대로 남고 점수도 남지 않는다 - 사용자는 이미 재녹음으로 안내됐다
        AnalysisJob saved = repository.findById(job.id()).orElseThrow();
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, saved.status());
        assertNull(saved.intonationScore());
    }

    @Test
    void 종결마다_백로그가_복귀해_혼잡_판정이_남지_않는다() {
        AnalysisBacklog backlog = new AnalysisBacklog();
        AnalysisJob job = saveProcessingJob();
        ScriptedClient client = new ScriptedClient()
                .then(new AiAnalysisClient.Completed(78, "OK", "rmvpe-0.2", "sv-0.3"));

        new HttpAnalysisDispatcher(client, new SyncTaskExecutor(), transitions, backlog, 0, 0)
                .dispatch(request(job));

        assertEquals(0, backlog.inFlight());
    }

    private HttpAnalysisDispatcher dispatcher(AiAnalysisClient client, int retries) {
        // 백오프 0ms - 테스트가 재전송 대기에 시간을 쓰지 않게 한다
        return new HttpAnalysisDispatcher(client, new SyncTaskExecutor(), transitions,
                new AnalysisBacklog(), retries, 0);
    }

    private AnalysisJob saveProcessingJob() {
        return repository.save(new AnalysisJob("a_" + UUID.randomUUID(), "s_dispatch", "v1", 1,
                "idem-" + UUID.randomUUID(), AnalysisJobStatus.PROCESSING, Instant.now()));
    }

    private static AnalysisDispatcher.AnalysisRequest request(AnalysisJob job) {
        return new AnalysisDispatcher.AnalysisRequest(job.id(), job.sessionId(), job.itemId(),
                "gn-2026.08.1", "sv-0.3", 3000, new byte[] {1, 2, 3});
    }
}
