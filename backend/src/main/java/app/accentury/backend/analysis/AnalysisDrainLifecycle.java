package app.accentury.backend.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 종료 신호에 진행 중 분석을 마치고 나가는 순서 (KAN-166).
 * <p>
 * 원본 음성은 어디에도 저장되지 않으므로(FR-DP-01) 끊긴 분석은 복구할 수 없고 사용자가
 * 재녹음해야 한다. Spring의 graceful shutdown은 서블릿 요청만 기다리고 백그라운드 워커는
 * 그대로 끊으므로, 분석 워커의 배수는 여기서 따로 잡는다. 순서는 넷이다.
 * <ol>
 *   <li>readiness가 {@code REFUSING_TRAFFIC}으로 내려간다 - Boot가 컨텍스트 close 첫 줄에서
 *       발행한다 ({@code ServletWebServerApplicationContext.doClose}). 이 이벤트를 받아 새 분석
 *       접수를 끊는다. 집계 health가 503으로 바뀌므로 로드밸런서가 대상을 빼기 시작한다.</li>
 *   <li>웹 서버가 새 요청을 막고 진행 중 요청을 {@code spring.lifecycle.timeout-per-shutdown-phase}
 *       안에 마친 뒤 멎는다 (Boot의 {@code WebServerGracefulShutdownLifecycle}과
 *       {@code WebServerStartStopLifecycle}).</li>
 *   <li>이 빈이 멎는다 - 아직 시작하지 않은 대기 작업은 즉시 실패로 정리하고(2026-08-31 결정),
 *       실행 중 작업만 {@code accentury.analysis.shutdown-budget} 안에서 끝나기를 기다린다.
 *       예산을 넘긴 작업은 실패로 정리하고 워커를 중단시킨다.</li>
 *   <li>풀이 종료되고 컨텍스트가 빈을 파괴한다.</li>
 * </ol>
 * phase는 웹 서버 정지보다 낮게 둔다 - 낮은 phase가 나중에 멈추므로, 배수는 요청이 더
 * 들어오지 않는 상태에서 돈다. 워커 풀 자체의 lifecycle은 쓰지 않는다 -
 * {@code waitForTasksToCompleteOnShutdown=true}면 Spring이 close 이벤트에서 풀을 일시정지하거나
 * 조기 종료하지 않고(lateShutdown) 파괴 시점에만 shutdown하므로, 큐 취소와 예산 대기를 여기서
 * 온전히 통제할 수 있다 ({@code ExecutorConfigurationSupport.onApplicationEvent}). 풀의
 * awaitTermination은 걸지 않는다 - 여기서 예산을 다 쓴 뒤 파괴 시점에 같은 예산을 한 번 더
 * 기다리면 컨테이너 유예를 넘긴다 (Codex sol 리뷰 P1). 대기는 이 클래스의 마감시각 하나뿐이다.
 * <p>
 * 예산 산정: 대기 작업을 기다리지 않으므로 상한은 "실행 중인 워커 수만큼의 AI 호출 1회"다 -
 * 워커가 몇이든 병렬이라 AI 타임아웃 1회분이면 되고, 재전송은 종료 중 시작하지 않는다.
 * 웹 유예 15초 + 예산 90초 = 105초로 컨테이너 강제 종료 상한(compose stop_grace_period 110초,
 * ECS stopTimeout 120초) 안에 든다.
 */
class AnalysisDrainLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDrainLifecycle.class);

    /** 웹 서버가 완전히 멎은 뒤 - Boot의 시작/정지 phase보다 한 단계 낮다. */
    static final int PHASE = WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1024;

    private final AnalysisDispatcher dispatcher;
    private final ThreadPoolTaskExecutor executor;
    private final AnalysisBacklog backlog;
    private final Duration budget;
    private volatile boolean running;

    AnalysisDrainLifecycle(AnalysisDispatcher dispatcher, ThreadPoolTaskExecutor executor,
                           AnalysisBacklog backlog, Duration budget) {
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.backlog = backlog;
        this.budget = budget;
    }

    /**
     * 종료 순서 1 - readiness 하강. Boot가 close 첫 줄에서 발행하므로 웹 서버가 멎기 전이다.
     * 여기서 새 접수를 끊어야 유예 구간에 들어온 업로드가 큐에 들어갔다가 곧바로 실패로
     * 되돌아가는 대신 503으로 즉시 재녹음 안내를 받는다. 기동 완료의 ACCEPTING_TRAFFIC은
     * 반대로 연다 (테스트가 두 상태를 오갈 때도 같은 규칙).
     */
    @EventListener
    public void onReadinessChange(AvailabilityChangeEvent<ReadinessState> event) {
        if (event.getState() == ReadinessState.REFUSING_TRAFFIC) {
            dispatcher.refuseNew();
            log.info("종료 1/4 readiness REFUSING_TRAFFIC - 새 분석 접수를 끊는다. 진행 중 {}건",
                    backlog.inFlight());
        } else if (event.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
            dispatcher.acceptNew();
        }
    }

    /** readiness 이벤트 없이 닫히는 컨텍스트(테스트)까지 같은 규칙으로 - 두 번 불려도 안전하다. */
    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        dispatcher.refuseNew();
    }

    @Override
    public void start() {
        running = true;
        dispatcher.acceptNew();
    }

    /**
     * 컨텍스트 close의 lifecycle 정지. {@code context.stop()} 뒤 {@code start()}로 되살리는 운용은
     * 지원하지 않는다 - 여기서 워커 풀을 종료하므로 재개해도 분석이 돌지 않는다. 이 앱은 close만
     * 쓴다 (Codex sol 리뷰 P2 - 해당 없음으로 기록).
     */
    @Override
    public void stop() {
        running = false;
        drain();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /**
     * 종료 순서 2와 3 - 대기 작업 즉시 실패, 실행 중 작업 완료 대기, 예산 초과 정리, 풀 종료.
     * 동기적으로 돈다 - {@code timeout-per-shutdown-phase}는 비동기 stop(callback)에만 걸리므로
     * 여기의 대기는 웹 유예와 별개의 예산이다.
     */
    Drained drain() {
        long startedAt = System.nanoTime();
        // 마감은 하나다 - 대기 작업 정리(DB 왕복 최대 큐 용량만큼)도 같은 예산 안에서 센다
        // (Codex sol 리뷰 P1). 정리에 쓴 만큼 완료 대기가 짧아진다.
        long deadline = startedAt + budget.toNanos();
        dispatcher.refuseNew();
        int cancelled = dispatcher.failQueued();
        int inFlight = backlog.inFlight();
        log.info("종료 2/4 웹 서버 정지 - 대기 {}건 즉시 실패, 실행 중 {}건 완료 대기 (예산 {})",
                cancelled, inFlight, budget);
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        pool.shutdown();
        boolean terminated;
        try {
            long remaining = deadline - System.nanoTime();
            terminated = remaining > 0 && pool.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminated = false;
        }
        int overBudget = 0;
        if (!terminated) {
            // 먼저 종결을 남기고 그다음 워커를 중단한다 - 순서를 바꾸면 중단된 워커의 실패
            // 종결(INTERNAL_ERROR)이 먼저 저장돼 사유가 "예산 초과"가 아니게 된다.
            overBudget = dispatcher.failRunning();
            pool.shutdownNow();
            // shutdownNow는 큐에 남은 작업을 실행하지 않고 버린다 - 접수 검사와 등록 사이에 신호가
            // 끼어들어 늦게 들어온 작업이 있으면 여기서 종결과 버퍼 파기를 마무리한다 (Codex sol 리뷰 P2).
            cancelled += dispatcher.failQueued();
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("종료 3/4 워커 배수 {} - 실행 중 {}건 중 예산 안 종결 {}건, 예산 초과 실패 {}건, 소요 {}ms",
                terminated ? "완료" : "예산 초과", inFlight, inFlight - overBudget, overBudget, elapsedMs);
        log.info("종료 4/4 분석 전달 풀 종료 - 남은 진행 중 {}건", backlog.inFlight());
        return new Drained(cancelled, inFlight - overBudget, overBudget, terminated,
                Duration.ofMillis(elapsedMs));
    }

    /**
     * 배수 1회의 집계 - 로그와 테스트의 유일한 입력.
     *
     * @param cancelled  시작 전에 취소해 즉시 실패로 정리한 대기 작업 수
     * @param finished   예산 안에 워커가 스스로 종결한(성공이든 실패든) 실행 중 작업 수
     * @param overBudget 예산을 넘겨 실패로 정리한 실행 중 작업 수
     * @param terminated 풀이 예산 안에 스스로 멎었는가
     */
    record Drained(int cancelled, int finished, int overBudget, boolean terminated, Duration elapsed) {
    }
}
