package app.accentury.backend.analysis;

import app.accentury.backend.common.CorrelationIdFilter;
import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 업로드된 오디오를 AI로 넘기고 결과로 작업을 종결하는 비동기 디스패처 (KAN-24).
 * <p>
 * {@code dispatch()}는 워커 큐 제출까지만 하고 즉시 돌아온다 - 업로드 요청(§3.3)은
 * 추론을 기다리지 않고 202를 반환한다. 큐가 가득 차 제출이 거절되면 그 예외가 업로드
 * 요청 스레드로 그대로 올라가고, 업로드 서비스가 RETRYABLE_FAILED + 503으로 처리한다.
 * <p>
 * 일시 장애(연결 실패, 타임아웃, 5xx)는 오디오가 아직 메모리에 있는 이 시점에만
 * 재전송할 수 있다 (FR-DP-01 - 저장이 없어 나중은 불가). 재전송 예산을 다 쓰면
 * RETRYABLE_FAILED로 종결해 재녹음(새 시도)을 유도한다. 분석 판정 실패
 * ({@link AiAnalysisClient.Rejected})는 같은 오디오에 같은 답이 올 것이므로 재전송하지 않는다 -
 * "재시도 가능한 실패만 다시 큐잉한다"(KAN-24 AC)의 구현이 이 구분이다.
 * <p>
 * 장애가 길어지면 재전송도 손해다 - 연속 실패가 임계치에 닿으면 {@link AiCircuitBreaker}가
 * 회로를 열어 이 경로를 통째로 끊는다 (KAN-28). 열린 동안 업로드는 작업조차 만들지 않고
 * 503으로 돌아가고({@link #accepts(String)}), 큐에 이미 들어와 있던 작업은 AI를 부르지 않고
 * RETRYABLE_FAILED로 종결한다.
 * <p>
 * 종료 신호(KAN-166)가 오면 {@link #refuseNew()}로 새 전달을 끊고, 아직 시작하지 않은 대기
 * 작업은 {@link #failQueued()}로 즉시 종결하며, 실행 중인 작업만 종료 예산 안에서 끝나기를
 * 기다린다 - 순서는 {@link AnalysisDrainLifecycle}이 잡는다. 그래서 제출한 작업을 큐 안에서도
 * 알아볼 수 있게 {@link Task}로 감싸 추적한다.
 */
class HttpAnalysisDispatcher implements AnalysisDispatcher {

    private static final Logger log = LoggerFactory.getLogger(HttpAnalysisDispatcher.class);

    /** 재전송 대기의 기본 단위 - n번째 재전송 전에 n배로 기다린다. 설정 검증({@link AnalysisDispatchConfig})이 참조한다. */
    static final long RETRY_BACKOFF_MS = 300;

    private final AiAnalysisClient client;
    private final TaskExecutor executor;
    private final AnalysisJobTransitions transitions;
    private final AnalysisBacklog backlog;
    private final AiCircuitBreaker circuitBreaker;
    private final int retries;
    private final long retryBackoffMs;

    /** 제출됐고 아직 종결되지 않은 작업 - 종료 시 대기와 실행 중을 가르는 근거 (KAN-166). */
    private final Set<Task> tasks = ConcurrentHashMap.newKeySet();

    /** 종료 신호 뒤 true - 새 전달을 거절하고 재전송을 시작하지 않는다 (KAN-166). */
    private volatile boolean refusing;

    HttpAnalysisDispatcher(AiAnalysisClient client, TaskExecutor executor,
                           AnalysisJobTransitions transitions, AnalysisBacklog backlog,
                           AiCircuitBreaker circuitBreaker, int retries) {
        this(client, executor, transitions, backlog, circuitBreaker, retries, RETRY_BACKOFF_MS);
    }

    HttpAnalysisDispatcher(AiAnalysisClient client, TaskExecutor executor,
                           AnalysisJobTransitions transitions, AnalysisBacklog backlog,
                           AiCircuitBreaker circuitBreaker, int retries, long retryBackoffMs) {
        this.client = client;
        this.executor = executor;
        this.transitions = transitions;
        this.backlog = backlog;
        this.circuitBreaker = circuitBreaker;
        this.retries = retries;
        this.retryBackoffMs = retryBackoffMs;
    }

    /**
     * 회로가 열려 있으면 새 업로드를 받지 않는다 (KAN-28). 반열림에서는 복구를 판정할
     * 시험 1건만 통과한다 - 판정 규칙은 {@link AiCircuitBreaker}에 있다.
     */
    @Override
    public boolean accepts(String analysisJobId) {
        return circuitBreaker.admitsUpload(analysisJobId);
    }

    /**
     * 업로드가 잡아 둔 복구 시험 자리를 놓아준다 (KAN-28) - 작업 저장이 롤백되거나 큐
     * 제출이 거절돼 이 작업이 AI에 닿지 못할 때 호출부가 부른다.
     */
    @Override
    public void abandon(String analysisJobId) {
        circuitBreaker.releaseTrial(analysisJobId);
    }

    /**
     * 열린 회로의 복구 프로브 (KAN-28, §4.2) - 차례일 때만 health를 던진다.
     * 프로브 결과는 그 프로브가 나간 회로 세대에만 적용된다.
     */
    @Override
    public void probeAvailability() {
        OptionalLong claimed = circuitBreaker.claimProbe();
        if (claimed.isEmpty()) {
            return;
        }
        long probeGeneration = claimed.getAsLong();
        if (client.healthy()) {
            circuitBreaker.probeSucceeded(probeGeneration);
        } else {
            circuitBreaker.probeFailed(probeGeneration);
        }
    }

    @Override
    public void dispatch(AnalysisRequest request) {
        if (refusing) {
            // 종료 중이다 (KAN-166). 큐에 넣어 봐야 failQueued()가 바로 되돌리므로 여기서 끊는다 -
            // 업로드 서비스는 큐 만원과 같은 경로(RETRYABLE_FAILED + 503)로 처리한다. 버퍼는
            // 소유권 계약대로 던지기 전에 지운다 (KAN-27) - 아직 backlog에 세지 않았으므로 복귀는 없다.
            request.wipeAudio();
            throw new TaskRejectedException(
                    "분석 전달이 종료 중이라 새 작업을 받지 않는다 jobId=" + request.analysisJobId());
        }
        // 워커는 요청 스레드가 아니다 - 추적 ID(§2.2)를 여기서 붙잡아 워커 MDC로 넘긴다.
        String requestScoped = MDC.get(CorrelationIdFilter.MDC_KEY);
        String correlationId = requestScoped != null ? requestScoped : "c_" + UUID.randomUUID();
        Task task = new Task(request, correlationId);
        backlog.started();
        tasks.add(task);
        // 복귀는 finally로 - RuntimeException만 잡으면 스레드 생성 불가(OOM) 같은 Error에서
        // 카운터가 새고, 누적 30건이면 pollAfterMs가 3000에 영구 고정된다 (Codex 리뷰).
        boolean submitted = false;
        try {
            executor.execute(task);
            submitted = true;
        } finally {
            if (!submitted) {
                // 워커가 뜨지 못했으니 버퍼를 지울 주체도 여기뿐이다 (KAN-27 소유권 계약).
                tasks.remove(task);
                request.wipeAudio();
                backlog.finished();
            }
        }
    }

    @Override
    public void refuseNew() {
        refusing = true;
    }

    @Override
    public void acceptNew() {
        refusing = false;
    }

    @Override
    public int failQueued() {
        // 취소 표시를 먼저 전부 한 뒤에 종결한다 (Codex sol 리뷰 P1) - 한 루프에서 DB 왕복을 섞으면
        // 그 사이 실행 중이던 워커가 끝나며 다음 대기 작업을 집어 AI를 부른다. 표시만 하는 루프는
        // DB 없이 끝나고, 그래도 남는 틈은 워커가 시작 시점에 refusing을 보고 스스로 취소해 막는다.
        List<Task> cancelled = new ArrayList<>();
        for (Task task : tasks) {
            if (task.cancel()) {
                cancelled.add(task);
            }
        }
        // 종결은 한 문장으로 (Codex sol 리뷰 P1) - 큐 용량(200)만큼 건별 왕복이면 종료 예산을 넘긴다.
        failAllQuietly(cancelled.stream().map(task -> task.request.analysisJobId()).toList(),
                ErrorCode.ANALYSIS_UNAVAILABLE);
        for (Task task : cancelled) {
            release(task);
        }
        return cancelled.size();
    }

    /**
     * 취소가 확정된 대기 작업의 DB 밖 뒷정리 - 워커가 그 작업을 만지지 않으므로 전부 여기서 한다.
     * 종결 저장은 호출부가 한다 (일괄이든 건별이든).
     */
    private void release(Task task) {
        tasks.remove(task);
        // AI에 닿지 않았으니 복구 시험 자리가 잡혀 있었다면 놓아준다 (KAN-28).
        circuitBreaker.releaseTrial(task.request.analysisJobId());
        task.request.wipeAudio();
        backlog.finished();
    }

    @Override
    public int failRunning() {
        List<String> running = new ArrayList<>();
        for (Task task : tasks) {
            if (task.state.get() == Task.State.RUNNING) {
                running.add(task.request.analysisJobId());
            }
        }
        // AI에 닿았을 수 있으므로 시도 예산에 포함되는 사유다 - 실행 잔류 스위퍼와 같은 판단
        // (AnalysisJobTimeout). 워커가 먼저 종결했으면 조건부 UPDATE가 그 행을 건너뛴다.
        failAllQuietly(running, ErrorCode.ANALYSIS_TIMEOUT);
        return running.size();
    }

    /** 종료 경로의 일괄 종결 - 저장 실패로 나머지 정리(버퍼, 백로그)까지 멈추지 않게 삼킨다. */
    private void failAllQuietly(List<String> jobIds, ErrorCode errorCode) {
        if (jobIds.isEmpty()) {
            return;
        }
        try {
            transitions.failAll(jobIds, AnalysisJobStatus.RETRYABLE_FAILED, errorCode.name());
        } catch (RuntimeException e) {
            log.error("종료 중 일괄 종결 저장 실패 {}건 - 타임아웃 스위퍼가 마무리한다", jobIds.size(), e);
        }
    }

    private void run(AnalysisRequest request, String correlationId) {
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        try {
            // 실행 시작을 원자적으로 선점한다 - 이미 종결된(타임아웃 등) 작업이면 그 결과는
            // 어차피 버려지므로 AI(GPU)를 호출하지 않는다 (Codex sol 리뷰 P1). 선점과 응답
            // 사이의 경합은 종결 쪽 조건부 UPDATE가 걸러낸다.
            if (!transitions.start(request.analysisJobId())) {
                log.info("이미 종결된 작업이라 AI 호출을 건너뛴다 jobId={}", request.analysisJobId());
                return;
            }
            apply(request.analysisJobId(), analyzeWithRetry(request, correlationId));
        } catch (RuntimeException e) {
            // 종결을 놓치면 사용자는 타임아웃 스위퍼까지 대기 화면에 묶인다 - 어떤 예외도 종결로 바꾼다.
            log.error("분석 전달 워커 실패 jobId={}", request.analysisJobId(), e);
            try {
                transitions.fail(request.analysisJobId(), AnalysisJobStatus.RETRYABLE_FAILED,
                        ErrorCode.INTERNAL_ERROR.name());
            } catch (RuntimeException failure) {
                // 종결 저장까지 실패하면 삼키고 스위퍼에 맡긴다 - 여기서 던지면 인라인 실행기
                // 경로에서 dispatch()의 복귀와 겹쳐 백로그가 이중 감소한다 (Codex 리뷰).
                log.error("종결 저장 실패 - 타임아웃 스위퍼가 마무리한다 jobId={}",
                        request.analysisJobId(), failure);
            }
        } finally {
            // 이 작업이 복구 시험 자리를 물고 있었다면 놓아준다 (KAN-28). 성공이나 실패로
            // 판정이 난 경우엔 회로가 이미 자리를 비웠으므로 아무 일도 일어나지 않고, AI에
            // 닿지도 못한 경우(이미 종결된 작업, 워커에서 터진 예상 밖 예외)에만 실제로
            // 풀린다 - 그대로 두면 trialTimeout(60초) 동안 다른 업로드가 전부 503이다.
            circuitBreaker.releaseTrial(request.analysisJobId());
            // 종결 즉시 원본 음성을 지운다 (KAN-27) - 성공, 판정 실패, 예산 소진, 예외,
            // 이미 종결된 작업이라 AI를 부르지 않은 경우까지 전부 이 finally를 지난다.
            // 재전송은 analyzeWithRetry 안에서 이미 끝났으므로 여기서 지워도 늦지 않다.
            request.wipeAudio();
            MDC.remove(CorrelationIdFilter.MDC_KEY);
            backlog.finished();
        }
    }

    /** 성공이나 판정 실패면 결과를, 재전송 예산까지 소진하면 null을 돌려주며 직접 종결한다. */
    private AiAnalysisClient.@Nullable Outcome analyzeWithRetry(
            AnalysisRequest request, String correlationId) {
        for (int attempt = 0; ; attempt++) {
            if (attempt > 0 && refusing) {
                // 재전송 대기(backoff) 중에 종료 신호가 왔다 - 대기 전 검사는 이미 지났으므로 호출
                // 직전에 한 번 더 본다 (Codex sol 리뷰 P2). 사유는 아래 대기 전 검사와 같다.
                log.info("종료 중이라 재전송을 시작하지 않는다 jobId={} 시도={}",
                        request.analysisJobId(), attempt + 1);
                transitions.fail(request.analysisJobId(), AnalysisJobStatus.RETRYABLE_FAILED,
                        ErrorCode.ANALYSIS_UNAVAILABLE.name());
                return null;
            }
            if (!circuitBreaker.admitsDispatch(request.analysisJobId())) {
                // 회로가 열려 있거나, 반열림에서 다른 작업이 이미 시험 중이다 - 접수와 실행
                // 사이에, 또는 재전송 대기 중에 상태가 바뀐 경우다.
                // 이 시도는 AI에 닿지 않았으므로 시도 예산에서 빠지는 ANALYSIS_UNAVAILABLE로
                // 종결한다 (§2.5 - 서버 사정으로 사용자의 문항별 상한이 깎이면 안 된다).
                // 재전송 중에 열렸다면 앞선 시도는 GPU를 썼을 수 있지만, 작업 하나에 사유는
                // 하나이고 장애 구간에서는 사용자에게 유리한 쪽으로 접는다.
                log.info("AI 회로가 전달을 허용하지 않아 건너뛴다 jobId={}", request.analysisJobId());
                transitions.fail(request.analysisJobId(), AnalysisJobStatus.RETRYABLE_FAILED,
                        ErrorCode.ANALYSIS_UNAVAILABLE.name());
                return null;
            }
            try {
                AiAnalysisClient.Outcome outcome = client.analyze(request, correlationId);
                if (contractViolation(outcome)) {
                    // 응답은 왔지만 계약(§4.1)과 다르다 - 같은 오디오에 같은 답이 올 것이므로
                    // 재전송하지는 않되, 회로에는 실패로 센다 (KAN-28). 성공으로 세면 응답만
                    // 하고 고장 난 AI 앞에서 회로가 영영 닫혀 있어 업로드마다 GPU 슬롯을
                    // 태우고, 섞여 오는 진짜 5xx의 연속 카운터까지 매번 0으로 되돌린다.
                    circuitBreaker.recordFailure(request.analysisJobId());
                } else {
                    // 판정 실패(§4.1 422)도 AI가 살아서 답한 것이다 - 가용성 실패가 아니다.
                    circuitBreaker.recordSuccess(request.analysisJobId());
                }
                return outcome;
            } catch (AiAnalysisClient.AiUnavailableException e) {
                circuitBreaker.recordFailure(request.analysisJobId());
                if (attempt >= retries) {
                    log.warn("AI 일시 장애로 재전송 예산 소진 jobId={} 시도={} kind={}",
                            request.analysisJobId(), attempt + 1, e.kind(), e);
                    transitions.fail(request.analysisJobId(), AnalysisJobStatus.RETRYABLE_FAILED,
                            exhaustedErrorCode(e.kind()));
                    return null;
                }
                if (refusing) {
                    // 종료 중에는 다음 시도를 시작하지 않는다 (KAN-166) - 재전송 대기와 호출이
                    // 종료 예산을 먹는다. 회로가 재전송 중에 열린 경우와 같은 이유로 예산에서
                    // 빠지는 사유로 접는다 (서버 사정이지 사용자 잘못이 아니다).
                    log.info("종료 중이라 재전송을 시작하지 않는다 jobId={} 시도={}",
                            request.analysisJobId(), attempt + 1);
                    transitions.fail(request.analysisJobId(), AnalysisJobStatus.RETRYABLE_FAILED,
                            ErrorCode.ANALYSIS_UNAVAILABLE.name());
                    return null;
                }
                log.info("AI 일시 장애 - 재전송 {}회차 jobId={}", attempt + 1, request.analysisJobId());
                if (!backoff(attempt + 1)) {
                    transitions.fail(request.analysisJobId(), AnalysisJobStatus.RETRYABLE_FAILED,
                            ErrorCode.ANALYSIS_UNAVAILABLE.name());
                    return null;
                }
            }
        }
    }

    /** AI가 답은 했지만 계약(§4.1)을 어겼는가 - 재전송 대상은 아니지만 가용성 실패다 (KAN-28). */
    private static boolean contractViolation(AiAnalysisClient.Outcome outcome) {
        return outcome instanceof AiAnalysisClient.Rejected rejected
                && rejected.cause() == AiAnalysisClient.Rejected.Cause.CONTRACT_VIOLATION;
    }

    /**
     * 장애 분류별 종결 사유 - AI에 도달했는지가 시도 예산(§2.5) 포함 여부를 가른다.
     * ANALYSIS_UNAVAILABLE만 예산에서 빠지므로(countAiConsumingAttempts) 미도달에만 쓴다
     * (Codex sol 리뷰 P2 - 도달한 5xx를 같은 코드로 접으면 상한이 우회된다).
     */
    private static String exhaustedErrorCode(AiAnalysisClient.AiUnavailableException.Kind kind) {
        return switch (kind) {
            case UNREACHED -> ErrorCode.ANALYSIS_UNAVAILABLE.name();
            case TIMED_OUT -> ErrorCode.ANALYSIS_TIMEOUT.name();
            case SERVER_ERROR -> ErrorCode.INTERNAL_ERROR.name();
        };
    }

    private void apply(String jobId, AiAnalysisClient.@Nullable Outcome outcome) {
        switch (outcome) {
            case AiAnalysisClient.Completed completed -> transitions.complete(jobId,
                    completed.intonationScore(), completed.qualityCode(),
                    completed.modelVersion(), completed.scoreVersion());
            case AiAnalysisClient.Rejected rejected -> transitions.fail(jobId,
                    rejected.retryable() ? AnalysisJobStatus.RETRYABLE_FAILED : AnalysisJobStatus.FAILED,
                    rejected.errorCode());
            case null -> {
                // analyzeWithRetry가 이미 종결했다.
            }
        }
    }

    /** 서버 종료 등으로 끊기면 false - 워커를 붙잡아두지 않고 종결로 넘어간다. */
    private boolean backoff(int attempt) {
        try {
            Thread.sleep(retryBackoffMs * attempt);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 워커 큐에 들어가는 단위 (KAN-166). 큐 안의 작업을 겉에서 알아보고 실행 전에 취소할 수
     * 있어야 종료 시 "대기는 즉시 실패, 실행 중은 완료 대기"를 나눌 수 있다. 상태 전이는
     * CAS 하나다 - 워커의 시작(QUEUED -> RUNNING)과 종료 경로의 취소(QUEUED -> CANCELLED)가
     * 동시에 와도 한쪽만 이긴다.
     */
    private final class Task implements Runnable {

        enum State { QUEUED, RUNNING, CANCELLED }

        final AnalysisRequest request;
        final String correlationId;
        final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);

        Task(AnalysisRequest request, String correlationId) {
            this.request = request;
            this.correlationId = correlationId;
        }

        /** @return true면 이 호출이 취소했고 뒷정리는 호출부 몫이다. false면 이미 실행 중이거나 취소됐다. */
        boolean cancel() {
            return state.compareAndSet(State.QUEUED, State.CANCELLED);
        }

        @Override
        public void run() {
            if (refusing) {
                // 종료 신호 뒤에 집힌 대기 작업이다 - failQueued()의 취소 표시보다 워커가 먼저
                // 왔거나(Codex sol 리뷰 P1), 접수 검사와 등록 사이에 신호가 끼어든 늦은 제출이다(P2).
                // 어느 쪽이든 AI를 부르지 않고 대기 작업과 같은 사유로 종결한다.
                if (cancel()) {
                    log.info("종료 중 집힌 대기 작업을 실패로 정리한다 jobId={}", request.analysisJobId());
                    failAllQuietly(List.of(request.analysisJobId()), ErrorCode.ANALYSIS_UNAVAILABLE);
                    release(this);
                }
                return;
            }
            if (!state.compareAndSet(State.QUEUED, State.RUNNING)) {
                // failQueued()가 먼저 취소했다 - 종결, 버퍼, 백로그 전부 그쪽이 끝냈다.
                return;
            }
            try {
                HttpAnalysisDispatcher.this.run(request, correlationId);
            } finally {
                tasks.remove(this);
            }
        }
    }
}
