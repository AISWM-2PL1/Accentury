package app.accentury.backend.analysis;

import app.accentury.backend.common.CorrelationIdFilter;
import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.task.TaskExecutor;

import java.util.UUID;

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
 */
class HttpAnalysisDispatcher implements AnalysisDispatcher {

    private static final Logger log = LoggerFactory.getLogger(HttpAnalysisDispatcher.class);

    /** 재전송 대기의 기본 단위 - n번째 재전송 전에 n배로 기다린다. 설정 검증({@link AnalysisDispatchConfig})이 참조한다 */
    static final long RETRY_BACKOFF_MS = 300;

    private final AiAnalysisClient client;
    private final TaskExecutor executor;
    private final AnalysisJobTransitions transitions;
    private final AnalysisBacklog backlog;
    private final int retries;
    private final long retryBackoffMs;

    HttpAnalysisDispatcher(AiAnalysisClient client, TaskExecutor executor,
                           AnalysisJobTransitions transitions, AnalysisBacklog backlog, int retries) {
        this(client, executor, transitions, backlog, retries, RETRY_BACKOFF_MS);
    }

    HttpAnalysisDispatcher(AiAnalysisClient client, TaskExecutor executor,
                           AnalysisJobTransitions transitions, AnalysisBacklog backlog,
                           int retries, long retryBackoffMs) {
        this.client = client;
        this.executor = executor;
        this.transitions = transitions;
        this.backlog = backlog;
        this.retries = retries;
        this.retryBackoffMs = retryBackoffMs;
    }

    @Override
    public void dispatch(AnalysisRequest request) {
        // 워커는 요청 스레드가 아니다 - 추적 ID(§2.2)를 여기서 붙잡아 워커 MDC로 넘긴다
        String requestScoped = MDC.get(CorrelationIdFilter.MDC_KEY);
        String correlationId = requestScoped != null ? requestScoped : "c_" + UUID.randomUUID();
        backlog.started();
        // 복귀는 finally로 - RuntimeException만 잡으면 스레드 생성 불가(OOM) 같은 Error에서
        // 카운터가 새고, 누적 30건이면 pollAfterMs가 3000에 영구 고정된다 (Codex 리뷰)
        boolean submitted = false;
        try {
            executor.execute(() -> run(request, correlationId));
            submitted = true;
        } finally {
            if (!submitted) {
                // 워커가 뜨지 못했으니 버퍼를 지울 주체도 여기뿐이다 (KAN-27 소유권 계약)
                request.wipeAudio();
                backlog.finished();
            }
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
            // 종결을 놓치면 사용자는 타임아웃 스위퍼까지 대기 화면에 묶인다 - 어떤 예외도 종결로 바꾼다
            log.error("분석 전달 워커 실패 jobId={}", request.analysisJobId(), e);
            try {
                transitions.fail(request.analysisJobId(), AnalysisJobStatus.RETRYABLE_FAILED,
                        ErrorCode.INTERNAL_ERROR.name());
            } catch (RuntimeException failure) {
                // 종결 저장까지 실패하면 삼키고 스위퍼에 맡긴다 - 여기서 던지면 인라인 실행기
                // 경로에서 dispatch()의 복귀와 겹쳐 백로그가 이중 감소한다 (Codex 리뷰)
                log.error("종결 저장 실패 - 타임아웃 스위퍼가 마무리한다 jobId={}",
                        request.analysisJobId(), failure);
            }
        } finally {
            // 종결 즉시 원본 음성을 지운다 (KAN-27) - 성공, 판정 실패, 예산 소진, 예외,
            // 이미 종결된 작업이라 AI를 부르지 않은 경우까지 전부 이 finally를 지난다.
            // 재전송은 analyzeWithRetry 안에서 이미 끝났으므로 여기서 지워도 늦지 않다
            request.wipeAudio();
            MDC.remove(CorrelationIdFilter.MDC_KEY);
            backlog.finished();
        }
    }

    /** 성공이나 판정 실패면 결과를, 재전송 예산까지 소진하면 null을 돌려주며 직접 종결한다 */
    private AiAnalysisClient.@Nullable Outcome analyzeWithRetry(
            AnalysisRequest request, String correlationId) {
        for (int attempt = 0; ; attempt++) {
            try {
                return client.analyze(request, correlationId);
            } catch (AiAnalysisClient.AiUnavailableException e) {
                if (attempt >= retries) {
                    log.warn("AI 일시 장애로 재전송 예산 소진 jobId={} 시도={} kind={}",
                            request.analysisJobId(), attempt + 1, e.kind(), e);
                    transitions.fail(request.analysisJobId(), AnalysisJobStatus.RETRYABLE_FAILED,
                            exhaustedErrorCode(e.kind()));
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
                // analyzeWithRetry가 이미 종결했다
            }
        }
    }

    /** 서버 종료 등으로 끊기면 false - 워커를 붙잡아두지 않고 종결로 넘어간다 */
    private boolean backoff(int attempt) {
        try {
            Thread.sleep(retryBackoffMs * attempt);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
