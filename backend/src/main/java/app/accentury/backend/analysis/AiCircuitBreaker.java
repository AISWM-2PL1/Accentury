package app.accentury.backend.analysis;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * AI 호출의 회로 차단기 (KAN-28 - "AI 타임아웃, 재시도, 회로 차단 정책", API 명세서 §4.2).
 *
 * <h4>왜 필요한가</h4>
 * 재전송만으로는 장애가 길어질 때 손해가 커진다. AI가 죽어 있으면 업로드 1건이 워커
 * 하나를 {@code ai-timeout x (재전송 + 1)}만큼 붙들고, 워커 4개가 다 차면 전달 큐가 밀려
 * 뒤에 온 업로드까지 503으로 떨어진다. 사용자는 그동안 시도 상한(§2.5)만 쓴다.
 * 회로를 열면 이 낭비가 사라진다 - AI가 죽었다는 것을 이미 알고 있으므로 즉시 끊는다.
 *
 * <h4>상태</h4>
 * <ul>
 *   <li><b>닫힘</b> - 정상. 연속 실패가 임계치에 닿으면 연다.</li>
 *   <li><b>열림</b> - 업로드는 GPU를 쓰지 않은 503 {@code ANALYSIS_UNAVAILABLE}로 즉시
 *       끊기고(시도 예산 미소모), 큐에 남아 있던 작업도 AI를 부르지 않고 종결한다.
 *       쿨다운마다 {@code GET /internal/v0/health}(§4.2) 프로브를 한 번 던진다.</li>
 *   <li><b>반열림</b> - health가 UP이라 <b>시험 요청 1건</b>만 통과시키는 상태. 성공하면
 *       닫고, 실패하면 임계치를 다시 채울 것 없이 곧바로 다시 연다.</li>
 * </ul>
 *
 * <h4>왜 health만으로 닫지 않는가 (Codex sol 리뷰 P1)</h4>
 * {@code /internal/v0/health}는 프로세스 생존만 알린다 (모델 적재 상태는 KAN-22가 채운다).
 * health 성공을 곧바로 "복구"로 읽으면, 프로세스는 떠 있는데 추론만 죽은 장애에서 회로가
 * 쿨다운마다 활짝 열려 사용자 5명분 시도가 매번 타 없어진다. 그래서 health는 <b>시험을
 * 해볼 자격</b>까지만 주고, 복구 판정은 실제 분석 1건이 한다. 반대로 health가 응답하지
 * 않으면 사용자 요청은 한 건도 쓰지 않는다.
 * <p>
 * 시험이 연속으로 실패하면 쿨다운을 두 배씩 늘린다 - 장애가 길어질수록 시험에 쓰는
 * 사용자 요청이 드물어진다.
 * <p>
 * 실패 카운터는 <b>연속</b> 기준이다 - 성공이 하나라도 끼면 0으로 돌아간다. 정상 운영 중
 * 드문드문 나는 타임아웃으로 회로가 열리지 않게 하려는 것이다.
 */
class AiCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(AiCircuitBreaker.class);

    /** 쿨다운 배수 상한 - 기본 5초 기준 최대 80초. 그 이상 벌리면 복구가 사용자에게 늦게 보인다. */
    private static final int MAX_BACKOFF_SHIFT = 4;

    private enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final int failureThreshold;
    private final Duration probeInterval;
    private final Duration trialTimeout;
    private final Clock clock;

    private State state = State.CLOSED;

    /** 닫힘 상태의 연속 실패 - 임계치에 닿으면 연다. */
    private int consecutiveFailures;

    /** 연속으로 실패한 시험 횟수 - 쿨다운 백오프의 지수다. */
    private int recoveryFailures;

    /** 마지막 health 프로브 시각. 여는 순간에도 채워 첫 프로브까지 쿨다운을 준다. */
    private @Nullable Instant lastProbeAt;

    /** 시험 자리를 잡은 시각 - 결론이 나지 않아도 이 시간이 지나면 자리를 놓아준다. */
    private @Nullable Instant trialStartedAt;

    /**
     * 시험을 실제로 수행 중인 분석 작업 - 반열림에서 AI로 나가는 유일한 작업이다
     * (Codex sol 리뷰 P1). 회로가 열리기 전에 큐에 들어와 있던 작업까지 한꺼번에
     * 통과하면 "시험 1건"이라는 약속이 깨져, 아직 죽어 있는 추론에 여러 건이 몰린다.
     */
    private @Nullable String trialJobId;

    /**
     * 회로를 열 때마다 증가하는 세대 번호 (Codex sol 리뷰 P2).
     * <p>
     * 프로브는 네트워크 호출이라 결과가 늦게 돌아온다. 그 사이 회로가 닫혔다 다시 열렸다면
     * 그 결과는 <b>이전 회로에 대한 판정</b>이므로 적용하면 안 된다 - 방금 연 회로를
     * 옛 프로브가 열어젖히는 일을 막는다.
     */
    private long generation;

    /**
     * @param failureThreshold 닫힘 상태에서 회로를 여는 연속 실패 횟수
     * @param probeInterval    열림 상태의 기본 쿨다운이자 health 프로브 간격
     * @param trialTimeout     반열림 시험이 결론을 내지 못했을 때 슬롯을 놓아주는 한도.
     *                         분석 1건의 실행 잔류 한도(§3.4)와 같은 값을 쓴다 - 그보다
     *                         짧게 잡으면 살아 있는 시험을 두고 두 번째 시험이 나간다.
     */
    AiCircuitBreaker(int failureThreshold, Duration probeInterval, Duration trialTimeout, Clock clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException(
                    "circuit-failure-threshold는 1 이상이어야 한다: " + failureThreshold);
        }
        // 0이면 cooldown()이 어떤 백오프 배수에도 0을 돌려줘, 열려 있는 내내 스케줄 틱마다
        // (1초) health 프로브가 나간다 - 지수 백오프가 통째로 무력화되고 복구 중인 AI를 두드린다.
        if (probeInterval.isZero() || probeInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "circuit-probe-interval은 0보다 커야 한다: " + probeInterval);
        }
        // 0이면 시험 자리가 잡히는 즉시 만료로 보여 "시험 1건" 약속이 깨진다 - 반열림에서
        // 큐에 남은 작업까지 한꺼번에 AI로 나간다.
        if (trialTimeout.isZero() || trialTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "복구 시험 한도(processing-timeout)는 0보다 커야 한다: " + trialTimeout);
        }
        this.failureThreshold = failureThreshold;
        this.probeInterval = probeInterval;
        this.trialTimeout = trialTimeout;
        this.clock = clock;
    }

    /**
     * 이 작업으로 새 업로드를 받아도 되는가 (§3.3의 503 판정).
     * <p>
     * 반열림에서는 <b>한 건만</b> 통과하고, 그 한 건이 복구 판정용 시험이다. 자리를 잡는
     * 순간 <b>작업 ID까지 함께</b> 묶는다 - 자리만 잡아 두면 그 사이 큐에 남아 있던 옛
     * 작업이 자리를 채가고, 202를 받은 업로드가 분석도 못 해 보고 실패한다
     * (Codex sol 리뷰 P2). 시험이 결론을 못 내고 {@code trialTimeout}이 지나면 다음 요청이
     * 새 시험이 된다 - 결론을 놓쳐 회로가 영영 반열림에 갇히는 것을 막는 안전장치다.
     */
    synchronized boolean admitsUpload(String jobId) {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> false;
            case HALF_OPEN -> {
                Instant now = clock.instant();
                if (trialBusy(now) && !jobId.equals(trialJobId)) {
                    yield false;
                }
                startTrial(now, jobId);
                yield true;
            }
        };
    }

    /**
     * 이 작업을 AI로 넘겨도 되는가.
     * <p>
     * 열려 있으면 전부 막고, 닫혀 있으면 전부 통과시킨다. 반열림에서는 <b>시험 작업
     * 하나만</b> 통과한다 - 업로드가 잡아 둔 시험이면 그대로 통과하고(재전송도 마찬가지),
     * 자리가 비어 있으면 큐에 남아 있던 작업이 시험을 가져간다.
     */
    synchronized boolean admitsDispatch(String jobId) {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> false;
            case HALF_OPEN -> {
                if (jobId.equals(trialJobId)) {
                    yield true;
                }
                Instant now = clock.instant();
                if (trialBusy(now)) {
                    yield false;
                }
                startTrial(now, jobId);
                yield true;
            }
        };
    }

    /** 시험 자리가 아직 살아 있는가 - 한도를 넘긴 자리는 비어 있는 것으로 본다. */
    private boolean trialBusy(Instant now) {
        return trialStartedAt != null && now.isBefore(trialStartedAt.plus(trialTimeout));
    }

    private void startTrial(Instant now, @Nullable String jobId) {
        trialStartedAt = now;
        trialJobId = jobId;
    }

    /**
     * AI가 응답했다 - 판정 실패(§4.1 422)도 서버가 살아 있다는 증거이므로 성공이다.
     *
     * @param jobId 이 결과를 낸 분석 작업 - 반열림에서 <b>시험 작업의 결과인지</b> 가른다.
     */
    synchronized void recordSuccess(String jobId) {
        consecutiveFailures = 0;
        switch (state) {
            case CLOSED -> { }
            // 열린 뒤에 도착한 성공이다 - 시험 중인 것도 없으므로 이 증거를 그대로 받는다.
            // 반열림의 복구 절차(쿨다운 -> health -> 시험 1건)를 건너뛰는 유일한 경로이고,
            // 그래서 recordFailure의 OPEN 처리(뒤늦은 실패는 무시)와 비대칭이다. AI가
            // 오르내리는 구간에서는 이 성공 하나로 닫혔다가 다시 열릴 수 있다는 뜻이지만,
            // "실제 분석이 성공했다"는 시험과 같은 종류의 증거라 그대로 받기로 했다
            // (2026-08-16 확인). 진동이 문제가 되면 여기부터 좁힌다.
            case OPEN -> {
                log.info("AI 회로 닫힘 - 열린 뒤 도착한 분석이 성공했다");
                close();
            }
            case HALF_OPEN -> {
                if (jobId.equals(trialJobId)) {
                    log.info("AI 회로 닫힘 - 복구 시험이 성공했다");
                    close();
                }
                // 시험이 아닌 작업(회로가 열리기 전에 출발한 호출)의 뒤늦은 성공은
                // 판정에 쓰지 않는다 - 복구 여부는 시험 결과가 정한다.
            }
        }
    }

    /**
     * AI 일시 장애(연결 실패, 타임아웃, 5xx)다.
     *
     * @param jobId 이 실패를 낸 분석 작업 - 반열림에서 시험 작업의 실패만 회로를 다시 연다.
     */
    synchronized void recordFailure(String jobId) {
        switch (state) {
            case CLOSED -> {
                consecutiveFailures++;
                if (consecutiveFailures >= failureThreshold) {
                    recoveryFailures = 0;
                    open("연속 실패 " + consecutiveFailures + "회");
                }
            }
            case HALF_OPEN -> {
                if (!jobId.equals(trialJobId)) {
                    // 회로가 열리기 전에 출발한 호출의 뒤늦은 실패다 - 그 판정은 이미 회로를
                    // 여는 데 쓰였다. 시험 실패로 오인하면 복구된 AI를 두고 회로가 다시 열리고
                    // 쿨다운까지 두 배로 벌어진다 (Codex sol 리뷰 P2).
                    return;
                }
                // 시험이 실패했다 - health는 UP인데 추론이 안 되는 상태다. 임계치를 다시
                // 채울 것 없이 곧바로 열고, 다음 시험까지의 쿨다운을 두 배로 벌린다.
                recoveryFailures++;
                open("복구 시험 실패 " + recoveryFailures + "회");
            }
            // 이미 열려 있다 - 열리기 전에 출발한 호출의 뒤늦은 실패다.
            case OPEN -> { }
        }
    }

    /**
     * 시험 자리를 잡은 작업이 AI에 닿지 못하고 끝났다 - 판정 없이 자리만 놓아준다 (KAN-28).
     * <p>
     * {@link #admitsUpload(String)}이 자리를 잡은 뒤에도 그 작업이 AI까지 못 가는 길이
     * 여럿이다: 작업 저장 트랜잭션 롤백, 전달 큐 제출 거절, 스위퍼가 먼저 종결한 작업,
     * 워커에서 터진 예상 밖 예외. 이 경로들은 성공도 실패도 기록하지 않으므로, 놓아주지
     * 않으면 {@code trialTimeout}(60초)이 지날 때까지 다른 업로드가 전부 503이 된다 -
     * 그동안 AI는 이미 살아 있을 수 있다. 한도 만료는 최후의 안전장치이지 정상 경로가 아니다.
     * <p>
     * 자리를 잡은 그 작업만 놓을 수 있다 - 판정이 이미 난 뒤(닫힘, 재개방)나 다른 작업이
     * 시험 중이면 아무 일도 하지 않으므로, 종결 경로에서 무조건 불러도 안전하다.
     */
    synchronized void releaseTrial(String jobId) {
        if (state == State.HALF_OPEN && jobId.equals(trialJobId)) {
            log.info("복구 시험이 AI에 닿지 못해 자리를 놓아준다 jobId={}", jobId);
            clearTrial();
        }
    }

    /**
     * health 프로브를 던질 차례면 그 회로의 세대 번호를 준다.
     * <p>
     * 호출과 동시에 그 차례를 소비한다(다음 판정은 다시 쿨다운을 기다린다).
     * 돌려받은 세대 번호는 {@link #probeSucceeded(long)}에 그대로 넘겨야 한다.
     */
    synchronized OptionalLong claimProbe() {
        if (state != State.OPEN || lastProbeAt == null) {
            return OptionalLong.empty();
        }
        Instant now = clock.instant();
        if (now.isBefore(lastProbeAt.plus(cooldown()))) {
            return OptionalLong.empty();
        }
        lastProbeAt = now;
        return OptionalLong.of(generation);
    }

    /**
     * health가 UP이다 - 시험 요청 1건을 통과시키는 반열림으로 간다.
     * 회로를 곧바로 닫지 않는 이유는 클래스 주석 참조.
     */
    synchronized void probeSucceeded(long probeGeneration) {
        if (state != State.OPEN || probeGeneration != generation) {
            // 이 프로브가 나간 뒤 회로가 닫혔거나 다시 열렸다 - 옛 판정은 버린다.
            return;
        }
        log.info("AI health 프로브 성공 - 분석 1건으로 복구를 시험한다");
        state = State.HALF_OPEN;
        clearTrial();
    }

    /** health가 응답하지 않는다 - 열린 채로 다음 주기를 기다린다. 사용자 요청은 한 건도 쓰지 않는다. */
    synchronized void probeFailed(long probeGeneration) {
        if (state == State.OPEN && probeGeneration == generation) {
            log.debug("AI health 프로브 실패 - 회로를 계속 열어 둔다");
        }
    }

    private void open(String reason) {
        Instant now = clock.instant();
        state = State.OPEN;
        generation++;
        lastProbeAt = now;
        clearTrial();
        consecutiveFailures = 0;
        log.warn("AI 회로 열림 ({}) - 업로드를 즉시 503으로 끊고 {}초 뒤 health 프로브로 복구를 확인한다",
                reason, cooldown().toSeconds());
    }

    private void close() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        recoveryFailures = 0;
        lastProbeAt = null;
        clearTrial();
    }

    private void clearTrial() {
        trialStartedAt = null;
        trialJobId = null;
    }

    /** 시험이 연속 실패할수록 두 배씩 벌어지는 쿨다운 - 장애가 길수록 시험을 덜 낭비한다. */
    private Duration cooldown() {
        return probeInterval.multipliedBy(1L << Math.min(recoveryFailures, MAX_BACKOFF_SHIFT));
    }
}
