package app.accentury.backend.analysis;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.SteppingClock;
import app.accentury.backend.TestSessions;
import app.accentury.backend.session.TestSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private TestSessionRepository sessionRepository;

    @Autowired
    private AnalysisJobTransitions transitions;

    /** 이 클래스의 시도 행이 매달리는 부모 세션은 하나다 - FK(KAN-123) 충족용 */
    @BeforeEach
    void ensureParentSession() {
        TestSessions.ensure(sessionRepository, "s_dispatch");
    }

    /** 스크립트된 응답을 차례로 내는 AI 클라이언트 - 호출 횟수가 재전송 검증의 근거다. */
    static class ScriptedClient implements AiAnalysisClient {

        final Deque<Object> script = new ArrayDeque<>();
        int calls;

        /** health 프로브 응답 - 회로 복구(KAN-28) 검증에서만 바꾼다. */
        volatile boolean healthy = true;
        int healthCalls;

        @Override
        public boolean healthy() {
            healthCalls++;
            return healthy;
        }

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
        // 같은 코드로 접으면 시도 상한이 우회된다 (Codex sol 리뷰 P2).
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
                .then(AiAnalysisClient.Rejected.judged("AUDIO_TOO_QUIET", true));

        dispatcher(client, 2).dispatch(request(job));

        AnalysisJob saved = repository.findById(job.id()).orElseThrow();
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, saved.status());
        assertEquals("AUDIO_TOO_QUIET", saved.errorCode());
        assertEquals(1, client.calls); // 같은 오디오에 같은 답 - 재시도 가능한 실패만 다시 큐잉한다 (AC).
    }

    @Test
    void 비재시도_판정_실패는_FAILED다() {
        AnalysisJob job = saveProcessingJob();
        ScriptedClient client = new ScriptedClient()
                .then(AiAnalysisClient.Rejected.judged("INTERNAL_ERROR", false));

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

        // 큐에서 기다리다 타임아웃 종결된 작업이 뒤늦게 GPU를 쓰면 안 된다 (Codex sol 리뷰 P1).
        assertEquals(0, client.calls);
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, repository.findById(job.id()).orElseThrow().status());
    }

    // === 회로 차단 (KAN-28) ===

    @Test
    void 연속_실패가_임계치에_닿으면_회로가_열려_새_업로드를_막는다() {
        // 회로가 열리면 업로드는 작업을 만들지 않고 503으로 끊긴다 (VoiceUploadService).
        AiCircuitBreaker breaker = new AiCircuitBreaker(2, Duration.ofSeconds(5),
                Duration.ofSeconds(60), Clock.systemUTC());
        HttpAnalysisDispatcher dispatcher = dispatcher(unavailableClient(), 1, breaker);
        assertTrue(dispatcher.accepts("a_probe"));

        // 최초 1회 + 재전송 1회 = 실패 2회로 임계치에 닿는다.
        dispatcher.dispatch(request(saveProcessingJob()));

        assertFalse(dispatcher.accepts("a_probe"));
    }

    @Test
    void 회로가_열려_있으면_AI를_부르지_않고_ANALYSIS_UNAVAILABLE로_종결한다() {
        // 큐에 이미 들어와 있던 작업들이다 - 여기서 AI를 부르면 장애가 끝날 때까지
        // 워커가 타임아웃마다 묶인다. 미도달이므로 시도 예산(§2.5)에서 빠지는 코드로 종결한다.
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = new AiCircuitBreaker(1, Duration.ofSeconds(5),
                Duration.ofSeconds(60), clock);
        breaker.recordFailure("a_setup");
        AnalysisJob job = saveProcessingJob();
        ScriptedClient client = new ScriptedClient()
                .then(new AiAnalysisClient.Completed(95, "OK", "rmvpe-0.2", "sv-0.3"));

        dispatcher(client, 2, breaker).dispatch(request(job));

        assertEquals(0, client.calls);
        AnalysisJob saved = repository.findById(job.id()).orElseThrow();
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, saved.status());
        assertEquals("ANALYSIS_UNAVAILABLE", saved.errorCode());
    }

    @Test
    void 반열림의_시험이_실패하면_뒤따르는_작업은_AI를_보지_못한다() {
        // health가 UP이어도 추론이 죽어 있으면 시험 1건만 태우고 곧바로 다시 닫는다 -
        // 큐에 남아 있던 작업까지 통과하면 사용자 여럿의 시도가 함께 탄다 (Codex sol 리뷰 P1).
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = new AiCircuitBreaker(1, Duration.ofSeconds(5),
                Duration.ofSeconds(60), clock);
        breaker.recordFailure("a_setup");
        clock.advance(Duration.ofSeconds(5));
        breaker.probeSucceeded(breaker.claimProbe().orElseThrow());
        AnalysisJob trial = saveProcessingJob();
        AnalysisJob queued = saveProcessingJob();
        ScriptedClient client = unavailableClient();
        HttpAnalysisDispatcher dispatcher = dispatcher(client, 0, breaker);

        dispatcher.dispatch(request(trial));
        dispatcher.dispatch(request(queued));

        assertEquals(1, client.calls, "시험 1건만 AI로 나가야 한다");
        for (AnalysisJob job : new AnalysisJob[] {trial, queued}) {
            AnalysisJob saved = repository.findById(job.id()).orElseThrow();
            assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, saved.status());
            // 둘 다 GPU 미소모로 종결해 시도 예산(§2.5)을 지킨다.
            assertEquals("ANALYSIS_UNAVAILABLE", saved.errorCode());
        }
    }

    @Test
    void 회로가_열려_전달을_건너뛰어도_오디오_버퍼는_지운다() {
        // 종결 경로가 하나 늘었다 - 여기가 새면 원본 음성이 힙에 남는다 (KAN-27 소유권 계약).
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = new AiCircuitBreaker(1, Duration.ofSeconds(5),
                Duration.ofSeconds(60), clock);
        breaker.recordFailure("a_setup");
        AnalysisDispatcher.AnalysisRequest request = request(saveProcessingJob());

        dispatcher(new ScriptedClient(), 0, breaker).dispatch(request);

        assertArrayEquals(new byte[] {0, 0, 0}, request.audio());
    }

    @Test
    void health_프로브가_성공하면_시험_요청_1건을_통과시킨다() {
        // health(§4.2)는 프로세스 생존만 알린다 - 그것만으로 회로를 닫지 않고 시험
        // 1건까지만 허용한다 (Codex sol 리뷰 P1). health가 응답하지 않는 동안에는
        // 사용자 요청을 한 건도 쓰지 않는다.
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = new AiCircuitBreaker(1, Duration.ofSeconds(5),
                Duration.ofSeconds(60), clock);
        breaker.recordFailure("a_setup");
        ScriptedClient client = new ScriptedClient();
        client.healthy = false;
        HttpAnalysisDispatcher dispatcher = dispatcher(client, 0, breaker);

        clock.advance(Duration.ofSeconds(5));
        dispatcher.probeAvailability();
        assertFalse(dispatcher.accepts("a_probe"), "health가 UP이 아니면 계속 열려 있다");

        client.healthy = true;
        clock.advance(Duration.ofSeconds(5));
        dispatcher.probeAvailability();

        assertTrue(dispatcher.accepts("a_trial"), "시험 1건은 통과한다");
        assertFalse(dispatcher.accepts("a_other"), "그 결론 전까지 두 번째는 없다");
        assertEquals(0, client.calls, "복구 프로브에 분석 호출을 쓰지 않는다");
    }

    @Test
    void 계약_위반_응답은_회로에_실패로_센다() {
        // 응답은 하면서 계약(§4.1)을 어기는 AI는 정상이 아니다 - 성공으로 세면 회로가 영영
        // 닫혀 있어 업로드마다 GPU 슬롯을 태우고 INTERNAL_ERROR만 돌려준다 (KAN-28).
        AiCircuitBreaker breaker = new AiCircuitBreaker(2, Duration.ofSeconds(5),
                Duration.ofSeconds(60), Clock.systemUTC());
        ScriptedClient client = new ScriptedClient()
                .then(AiAnalysisClient.Rejected.contractViolation())
                .then(AiAnalysisClient.Rejected.contractViolation());
        HttpAnalysisDispatcher dispatcher = dispatcher(client, 0, breaker);

        dispatcher.dispatch(request(saveProcessingJob()));
        assertTrue(dispatcher.accepts("a_probe"), "1회로는 임계치에 닿지 않는다");
        dispatcher.dispatch(request(saveProcessingJob()));

        assertFalse(dispatcher.accepts("a_probe"), "연속 2회로 회로가 열려야 한다");
    }

    @Test
    void 계약_위반_응답은_재전송하지_않는다() {
        // 회로에는 실패지만 같은 오디오에 같은 답이 온다 - 재전송은 GPU 낭비다.
        AnalysisJob job = saveProcessingJob();
        ScriptedClient client = new ScriptedClient()
                .then(AiAnalysisClient.Rejected.contractViolation());

        dispatcher(client, 2).dispatch(request(job));

        assertEquals(1, client.calls);
        AnalysisJob saved = repository.findById(job.id()).orElseThrow();
        assertEquals(AnalysisJobStatus.FAILED, saved.status());
        assertEquals("INTERNAL_ERROR", saved.errorCode());
    }

    @Test
    void 계약대로_온_판정_실패는_회로를_열지_않는다() {
        // 422 판정(AUDIO_TOO_QUIET 등)은 AI가 살아서 답한 것이다 - 조용한 녹음이 이어졌다고
        // 회로가 열리면 멀쩡한 AI를 두고 업로드가 503으로 끊긴다.
        AiCircuitBreaker breaker = new AiCircuitBreaker(2, Duration.ofSeconds(5),
                Duration.ofSeconds(60), Clock.systemUTC());
        ScriptedClient client = new ScriptedClient()
                .then(AiAnalysisClient.Rejected.judged("AUDIO_TOO_QUIET", true))
                .then(AiAnalysisClient.Rejected.judged("AUDIO_TOO_QUIET", true))
                .then(AiAnalysisClient.Rejected.judged("AUDIO_TOO_QUIET", true));
        HttpAnalysisDispatcher dispatcher = dispatcher(client, 0, breaker);

        for (int i = 0; i < 3; i++) {
            dispatcher.dispatch(request(saveProcessingJob()));
        }

        assertTrue(dispatcher.accepts("a_probe"));
    }

    @Test
    void 시험이_성공하면_회로가_닫히고_정상_트래픽이_재개된다() {
        // 복구의 끝까지 한 번에 본다 - 장애로 열리고, health가 UP이 되고, 시험 1건이
        // 성공해 닫히고, 그 뒤 업로드가 다시 통과한다 (KAN-28 §4.2).
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = new AiCircuitBreaker(1, Duration.ofSeconds(5),
                Duration.ofSeconds(60), clock);
        ScriptedClient client = new ScriptedClient();
        client.healthy = false;
        HttpAnalysisDispatcher dispatcher = dispatcher(client, 0, breaker);
        breaker.recordFailure("a_setup");
        assertFalse(dispatcher.accepts("a_blocked"), "장애로 열려 있다");

        client.healthy = true;
        clock.advance(Duration.ofSeconds(5));
        dispatcher.probeAvailability();

        AnalysisJob trial = saveProcessingJob();
        assertTrue(dispatcher.accepts(trial.id()), "health가 UP이면 시험 1건은 통과한다");
        client.then(new AiAnalysisClient.Completed(78, "OK", "rmvpe-0.2", "sv-0.3"));
        dispatcher.dispatch(request(trial));

        assertEquals(AnalysisJobStatus.COMPLETED,
                repository.findById(trial.id()).orElseThrow().status());
        assertTrue(dispatcher.accepts("a_after-1"), "닫힌 뒤에는 시험 자리 제한이 없다");
        assertTrue(dispatcher.accepts("a_after-2"), "정상 트래픽이 재개된다");
        assertEquals(1, client.healthCalls, "복구에 쓴 health는 한 번뿐이다");
    }

    @Test
    void 이미_종결된_시험_작업은_자리를_놓아준다() {
        // 스위퍼가 먼저 종결한 작업이 시험 자리를 물고 있으면, AI가 살아 있어도 나머지
        // 업로드가 한도(60초)만큼 503이다 - 한도 만료는 안전장치이지 정상 경로가 아니다.
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = halfOpen(clock);
        AnalysisJob trial = saveProcessingJob();
        transitions.fail(trial.id(), AnalysisJobStatus.RETRYABLE_FAILED, "ANALYSIS_TIMEOUT");
        ScriptedClient client = new ScriptedClient();
        HttpAnalysisDispatcher dispatcher = dispatcher(client, 0, breaker);
        assertTrue(dispatcher.accepts(trial.id()));

        dispatcher.dispatch(request(trial));

        assertEquals(0, client.calls, "종결된 작업에 GPU를 쓰지 않는다");
        assertTrue(dispatcher.accepts("a_next"), "시험 자리가 다음 업로드에게 넘어가야 한다");
    }

    @Test
    void 워커에서_예상_못_한_예외가_나도_시험_자리를_놓아준다() {
        // AiUnavailableException이 아닌 예외는 회로에 아무것도 기록하지 않는다 -
        // 놓아주는 코드가 없으면 자리가 한도까지 잠긴다.
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = halfOpen(clock);
        AnalysisJob trial = saveProcessingJob();
        AiAnalysisClient exploding = new ScriptedClient() {
            @Override
            public Outcome analyze(AnalysisDispatcher.AnalysisRequest request, String correlationId) {
                throw new IllegalStateException("워커에서 터진 예상 밖 예외");
            }
        };
        HttpAnalysisDispatcher dispatcher = dispatcher(exploding, 0, breaker);
        assertTrue(dispatcher.accepts(trial.id()));

        dispatcher.dispatch(request(trial));

        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED,
                repository.findById(trial.id()).orElseThrow().status());
        assertTrue(dispatcher.accepts("a_next"), "시험 자리가 풀려 있어야 한다");
    }

    @Test
    void 회로가_닫혀_있으면_프로브를_던지지_않는다() {
        // 정상 운영 중에 매 틱 health를 두드리면 의미 없는 트래픽만 늘어난다.
        ScriptedClient client = new ScriptedClient();
        HttpAnalysisDispatcher dispatcher = dispatcher(client, 0);

        dispatcher.probeAvailability();

        assertEquals(0, client.healthCalls);
    }

    @Test
    void 종결_후_늦게_도착한_결과는_조건부_전이가_버린다() {
        // 생존 확인과 AI 응답 사이에 스위퍼가 종결하는 경합 - 마지막 방어선은 조건부 UPDATE다.
        AnalysisJob job = saveProcessingJob();
        transitions.fail(job.id(), AnalysisJobStatus.RETRYABLE_FAILED, "ANALYSIS_TIMEOUT");

        transitions.complete(job.id(), 95, "OK", "rmvpe-0.2", "sv-0.3");

        // 타임아웃 종결이 그대로 남고 점수도 남지 않는다 - 사용자는 이미 재녹음으로 안내됐다.
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
        // 실행 중의 in-flight를 실행기 안에서 관측한다 - 시작 전 0, 종결 후 0만 보면
        // started() 누락(혼잡 감지 무력화)이 통과해 버린다 (Codex 리뷰).
        AtomicInteger duringRun = new AtomicInteger(-1);

        new HttpAnalysisDispatcher(client, task -> {
            duringRun.set(backlog.inFlight());
            task.run();
        }, transitions, backlog, openCircuitNever(), 0, 0).dispatch(request(job));

        assertEquals(1, duringRun.get());
        assertEquals(0, backlog.inFlight());
    }

    @Test
    void 제출이_거절돼도_백로그가_복귀한다() {
        // 큐 포화(RejectedExecutionException) 경로 - 여기서 카운터가 새면 실제 부하가
        // 빠진 뒤에도 inFlight가 임계치 위에 남아 pollAfterMs가 3000에 고정된다 (§5.3 규칙 1).
        AnalysisBacklog backlog = new AnalysisBacklog();
        AnalysisJob job = saveProcessingJob();
        AtomicInteger atRejection = new AtomicInteger(-1);
        HttpAnalysisDispatcher dispatcher = new HttpAnalysisDispatcher(new ScriptedClient(),
                task -> {
                    atRejection.set(backlog.inFlight());
                    throw new RejectedExecutionException("큐 포화 시뮬레이션");
                },
                transitions, backlog, openCircuitNever(), 0, 0);

        // 예외는 업로드 요청 스레드로 그대로 올라가야 업로드가 503으로 종결할 수 있다 (§3.3).
        assertThrows(RejectedExecutionException.class, () -> dispatcher.dispatch(request(job)));

        assertEquals(1, atRejection.get());
        assertEquals(0, backlog.inFlight());
    }

    // === 오디오 버퍼 파기 (KAN-27) ===

    @Test
    void 종결되면_오디오_버퍼를_0으로_지운다() {
        // "분석 응답 수신 즉시 삭제"(KAN-27)의 메모리 쪽 - 참조만 끊고 GC를 기다리면
        // 원본 음성이 힙 덤프와 스왑에 실린다.
        AnalysisJob job = saveProcessingJob();
        AnalysisDispatcher.AnalysisRequest request = request(job);
        ScriptedClient client = new ScriptedClient()
                .then(new AiAnalysisClient.Completed(78, "OK", "rmvpe-0.2", "sv-0.3"));

        dispatcher(client, 0).dispatch(request);

        assertArrayEquals(new byte[] {0, 0, 0}, request.audio());
    }

    @Test
    void 재전송_예산을_다_쓴_뒤에도_버퍼를_지운다() {
        // 재전송 중에는 같은 배열을 다시 보내야 하므로, 파기는 마지막 시도까지 끝난 뒤다 -
        // 여기서 이르게 지우면 재전송이 무음을 분석하게 된다 (그래서 성공 케이스와 따로 본다).
        AnalysisJob job = saveProcessingJob();
        AnalysisDispatcher.AnalysisRequest request = request(job);
        ScriptedClient client = new ScriptedClient()
                .then(new AiAnalysisClient.AiUnavailableException("연결 실패",
                        AiAnalysisClient.AiUnavailableException.Kind.UNREACHED, null))
                .then(new AiAnalysisClient.Completed(78, "OK", "rmvpe-0.2", "sv-0.3"));

        dispatcher(client, 1).dispatch(request);

        // 재전송이 실제로 일어났고(2회 호출), 그 뒤에 지워졌다.
        assertEquals(2, client.calls);
        assertArrayEquals(new byte[] {0, 0, 0}, request.audio());
    }

    @Test
    void 이미_종결된_작업이라_AI를_부르지_않아도_버퍼를_지운다() {
        AnalysisJob job = saveProcessingJob();
        transitions.fail(job.id(), AnalysisJobStatus.RETRYABLE_FAILED, "ANALYSIS_TIMEOUT");
        AnalysisDispatcher.AnalysisRequest request = request(job);

        dispatcher(new ScriptedClient(), 0).dispatch(request);

        assertArrayEquals(new byte[] {0, 0, 0}, request.audio());
    }

    @Test
    void 제출이_거절되면_요청_스레드가_버퍼를_지운다() {
        // 워커가 뜨지 못한 경로 - 지울 주체가 dispatch() 자신뿐이다.
        AnalysisJob job = saveProcessingJob();
        AnalysisDispatcher.AnalysisRequest request = request(job);
        HttpAnalysisDispatcher dispatcher = new HttpAnalysisDispatcher(new ScriptedClient(),
                task -> {
                    throw new RejectedExecutionException("큐 포화 시뮬레이션");
                },
                transitions, new AnalysisBacklog(), openCircuitNever(), 0, 0);

        assertThrows(RejectedExecutionException.class, () -> dispatcher.dispatch(request));

        assertArrayEquals(new byte[] {0, 0, 0}, request.audio());
    }

    private HttpAnalysisDispatcher dispatcher(AiAnalysisClient client, int retries) {
        return dispatcher(client, retries, openCircuitNever());
    }

    private HttpAnalysisDispatcher dispatcher(AiAnalysisClient client, int retries,
                                              AiCircuitBreaker circuitBreaker) {
        // 백오프 0ms - 테스트가 재전송 대기에 시간을 쓰지 않게 한다.
        return new HttpAnalysisDispatcher(client, new SyncTaskExecutor(), transitions,
                new AnalysisBacklog(), circuitBreaker, retries, 0);
    }

    /** health까지 통과해 시험 1건을 기다리는 회로 */
    private static AiCircuitBreaker halfOpen(SteppingClock clock) {
        AiCircuitBreaker breaker = new AiCircuitBreaker(1, Duration.ofSeconds(5),
                Duration.ofSeconds(60), clock);
        breaker.recordFailure("a_setup");
        clock.advance(Duration.ofSeconds(5));
        breaker.probeSucceeded(breaker.claimProbe().orElseThrow());
        return breaker;
    }

    /** 이 테스트의 관심사가 아닌 회로 - 한 번에 열리지 않을 만큼 임계치를 크게 둔다. */
    private static AiCircuitBreaker openCircuitNever() {
        return new AiCircuitBreaker(Integer.MAX_VALUE, Duration.ofSeconds(5),
                Duration.ofSeconds(60), Clock.systemUTC());
    }

    /** 부를 때마다 일시 장애를 내는 클라이언트 - 회로가 열리는 조건을 만든다. */
    private static ScriptedClient unavailableClient() {
        return new ScriptedClient() {
            @Override
            public Outcome analyze(AnalysisDispatcher.AnalysisRequest request, String correlationId) {
                calls++;
                throw new AiUnavailableException("연결 실패",
                        AiUnavailableException.Kind.UNREACHED, null);
            }
        };
    }

    private AnalysisJob saveProcessingJob() {
        return repository.save(new AnalysisJob("a_" + UUID.randomUUID(), "s_dispatch", "v1", 1,
                "idem-" + UUID.randomUUID(), AnalysisJobStatus.PROCESSING, Instant.now()));
    }

    private static AnalysisDispatcher.AnalysisRequest request(AnalysisJob job) {
        return new AnalysisDispatcher.AnalysisRequest(job.id(), job.sessionId(), job.itemId(), null,
                "gn-2026.08.1", "sv-0.3", 3000, new byte[] {1, 2, 3});
    }
}
