package app.accentury.backend.analysis;

import app.accentury.backend.SteppingClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 회로 차단기의 상태 전이 (KAN-28, API 명세서 §4.2).
 * <p>
 * 핵심은 넷이다: 연속 실패에만 열리고, 쿨다운마다 health 프로브가 한 번씩만 나가며,
 * 복구 판정은 <b>실제 분석 1건</b>이 하고(health는 시험 자격까지만), 시험이 실패하면
 * 곧바로 다시 열리며 쿨다운이 벌어진다.
 */
class AiCircuitBreakerTest {

    private static final Duration PROBE_INTERVAL = Duration.ofSeconds(5);
    private static final Duration TRIAL_TIMEOUT = Duration.ofSeconds(60);

    /** 시험으로 뽑힌 작업 */
    private static final String TRIAL = "a_trial";

    /** 회로가 열리기 전에 출발해 뒤늦게 결과를 들고 오는 작업 */
    private static final String STRAGGLER = "a_straggler";

    @Test
    void 연속_실패가_임계치에_닿으면_열린다() {
        AiCircuitBreaker breaker = breaker(3, new SteppingClock());

        breaker.recordFailure(STRAGGLER);
        breaker.recordFailure(STRAGGLER);
        assertTrue(breaker.admitsUpload(TRIAL), "임계치 전에는 닫혀 있어야 한다");

        breaker.recordFailure(STRAGGLER);

        assertFalse(breaker.admitsUpload(TRIAL));
        assertFalse(breaker.admitsDispatch(TRIAL), "큐에 남은 작업도 AI를 부르지 않는다");
    }

    @Test
    void 성공이_끼면_실패_카운터가_0으로_돌아간다() {
        // 정상 운영 중 드문드문 나는 타임아웃으로 회로가 열리면, 멀쩡한 AI를 두고
        // 업로드가 503으로 끊긴다.
        AiCircuitBreaker breaker = breaker(3, new SteppingClock());

        breaker.recordFailure(STRAGGLER);
        breaker.recordFailure(STRAGGLER);
        breaker.recordSuccess(STRAGGLER);
        breaker.recordFailure(STRAGGLER);
        breaker.recordFailure(STRAGGLER);

        assertTrue(breaker.admitsUpload(TRIAL));
    }

    @Test
    void 열린_뒤_진행_중이던_호출이_성공하면_닫힌다() {
        // 시험 중인 것이 없는 상태라 이 성공을 그대로 복구 증거로 받는다.
        AiCircuitBreaker breaker = openedBreaker(new SteppingClock());

        breaker.recordSuccess(STRAGGLER);

        assertTrue(breaker.admitsUpload(TRIAL));
    }

    @Test
    void 쿨다운_전에는_프로브를_던지지_않는다() {
        // 죽어 있는 AI를 매 틱 두드리면 복구 중인 서버에 부하만 얹는다.
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = openedBreaker(clock);

        assertTrue(breaker.claimProbe().isEmpty());

        clock.advance(PROBE_INTERVAL.minusSeconds(1));
        assertTrue(breaker.claimProbe().isEmpty());

        clock.advance(Duration.ofSeconds(1));
        assertTrue(breaker.claimProbe().isPresent());
    }

    @Test
    void 프로브_차례는_한_번만_소비된다() {
        // 판정 차례를 소비하지 않으면 스케줄 틱마다 프로브가 나가 간격 설정이 무의미해진다.
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = openedBreaker(clock);
        clock.advance(PROBE_INTERVAL);
        assertTrue(breaker.claimProbe().isPresent());

        assertTrue(breaker.claimProbe().isEmpty(), "같은 시각에 두 번째 차례는 없다");

        clock.advance(PROBE_INTERVAL);
        assertTrue(breaker.claimProbe().isPresent());
    }

    @Test
    void 닫혀_있으면_프로브_차례가_없다() {
        AiCircuitBreaker breaker = breaker(1, new SteppingClock());

        assertTrue(breaker.claimProbe().isEmpty());
    }

    @Test
    void health가_UP이면_시험_1건만_통과시킨다() {
        // health는 프로세스 생존만 알린다 (§4.2, 모델 상태는 KAN-22) - 그것만 믿고 회로를
        // 활짝 열면 추론만 죽은 장애에서 사용자 요청이 쿨다운마다 무더기로 타 없어진다
        // (Codex sol 리뷰 P1).
        AiCircuitBreaker breaker = halfOpenBreaker(new SteppingClock());

        assertTrue(breaker.admitsUpload(TRIAL), "시험 1건은 통과한다");
        assertFalse(breaker.admitsUpload(STRAGGLER), "그 결론이 나기 전까지 두 번째는 없다");
    }

    @Test
    void 반열림에서는_시험_작업_하나만_AI로_나간다() {
        // 회로가 열리기 전에 큐에 들어와 있던 작업까지 한꺼번에 통과하면, 아직 죽어 있는
        // 추론에 여러 건이 몰려 사용자 여럿의 시도가 함께 탄다 (Codex sol 리뷰 P1).
        AiCircuitBreaker breaker = halfOpenBreaker(new SteppingClock());

        assertTrue(breaker.admitsDispatch(TRIAL));
        assertFalse(breaker.admitsDispatch(STRAGGLER), "시험은 한 건뿐이다");
        assertTrue(breaker.admitsDispatch(TRIAL), "같은 작업의 재전송은 계속 통과한다");
    }

    @Test
    void 업로드가_잡은_시험_자리를_큐에_남은_작업이_채가지_못한다() {
        // 자리를 작업 ID까지 묶어 두지 않으면, 202를 받은 업로드가 분석도 못 해 보고
        // ANALYSIS_UNAVAILABLE로 끝난다 (Codex sol 리뷰 P2).
        AiCircuitBreaker breaker = halfOpenBreaker(new SteppingClock());
        assertTrue(breaker.admitsUpload(TRIAL));

        assertFalse(breaker.admitsDispatch(STRAGGLER), "큐에 남은 작업이 자리를 채가면 안 된다");
        assertTrue(breaker.admitsDispatch(TRIAL), "자리를 잡은 업로드의 작업은 통과한다");
    }

    @Test
    void 시험이_성공하면_닫힌다() {
        AiCircuitBreaker breaker = halfOpenBreaker(new SteppingClock());
        assertTrue(breaker.admitsDispatch(TRIAL));

        breaker.recordSuccess(TRIAL);

        assertTrue(breaker.admitsUpload(TRIAL));
        assertTrue(breaker.admitsUpload(TRIAL), "닫힌 뒤에는 시험 자리 제한이 없다");
    }

    @Test
    void 시험이_실패하면_임계치를_다시_채우지_않고_곧바로_다시_열린다() {
        AiCircuitBreaker breaker = halfOpenBreaker(new SteppingClock());
        assertTrue(breaker.admitsDispatch(TRIAL));

        breaker.recordFailure(TRIAL);

        assertFalse(breaker.admitsUpload(TRIAL));
    }

    @Test
    void 시험이_아닌_작업의_뒤늦은_실패는_회로를_다시_열지_않는다() {
        // 회로가 열리기 전에 출발한 호출은 그 판정이 이미 회로를 여는 데 쓰였다 -
        // 시험 실패로 오인하면 복구된 AI를 두고 회로가 다시 닫힌다 (Codex sol 리뷰 P2).
        AiCircuitBreaker breaker = halfOpenBreaker(new SteppingClock());
        assertTrue(breaker.admitsDispatch(TRIAL));

        breaker.recordFailure(STRAGGLER);

        assertTrue(breaker.admitsDispatch(TRIAL), "시험은 그대로 진행된다");
        breaker.recordSuccess(TRIAL);
        assertTrue(breaker.admitsUpload(TRIAL), "시험이 성공했으므로 닫혀야 한다");
    }

    @Test
    void 시험이_아닌_작업의_뒤늦은_성공은_복구로_보지_않는다() {
        // 복구 판정은 시험 결과가 한다 - 스트래글러 성공으로 먼저 닫으면 아직 죽어 있는
        // 추론에 트래픽이 그대로 쏟아진다.
        AiCircuitBreaker breaker = halfOpenBreaker(new SteppingClock());
        assertTrue(breaker.admitsDispatch(TRIAL));

        breaker.recordSuccess(STRAGGLER);

        assertFalse(breaker.admitsDispatch(STRAGGLER), "여전히 시험 1건만 통과한다");
    }

    @Test
    void 시험이_연속_실패하면_쿨다운이_두_배씩_벌어진다() {
        // 장애가 길수록 시험에 쓰는 사용자 요청이 드물어져야 한다.
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = halfOpenBreaker(clock);
        breaker.admitsDispatch(TRIAL);
        breaker.recordFailure(TRIAL);

        // 첫 재개방의 쿨다운은 기본 간격의 2배다.
        clock.advance(PROBE_INTERVAL);
        assertTrue(breaker.claimProbe().isEmpty());
        clock.advance(PROBE_INTERVAL);
        breaker.probeSucceeded(breaker.claimProbe().orElseThrow());

        breaker.admitsDispatch(TRIAL);
        breaker.recordFailure(TRIAL);

        // 두 번째 재개방은 4배
        clock.advance(PROBE_INTERVAL.multipliedBy(3));
        assertTrue(breaker.claimProbe().isEmpty());
        clock.advance(PROBE_INTERVAL);
        assertTrue(breaker.claimProbe().isPresent());
    }

    @Test
    void 복구하면_쿨다운_백오프가_초기화된다() {
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = halfOpenBreaker(clock);
        breaker.admitsDispatch(TRIAL);
        breaker.recordFailure(TRIAL);
        clock.advance(PROBE_INTERVAL.multipliedBy(2));
        breaker.probeSucceeded(breaker.claimProbe().orElseThrow());
        breaker.admitsDispatch(TRIAL);
        breaker.recordSuccess(TRIAL);

        // 새 장애의 첫 쿨다운은 다시 기본 간격이다.
        breaker.recordFailure(TRIAL);
        clock.advance(PROBE_INTERVAL);

        assertTrue(breaker.claimProbe().isPresent());
    }

    @Test
    void 결론이_없는_시험은_한도가_지나면_자리를_놓아준다() {
        // 시험 요청이 종결을 남기지 못하면(전달 거절 등) 회로가 영영 반열림에 갇혀
        // 모든 업로드가 503이 된다 - 그 자물쇠를 푸는 안전장치다.
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = halfOpenBreaker(clock);
        assertTrue(breaker.admitsUpload(TRIAL));
        assertFalse(breaker.admitsUpload(STRAGGLER));

        clock.advance(TRIAL_TIMEOUT.plusSeconds(1));

        assertTrue(breaker.admitsUpload(STRAGGLER));
    }

    @Test
    void 결론이_없는_시험_작업도_한도가_지나면_자리를_놓아준다() {
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = halfOpenBreaker(clock);
        assertTrue(breaker.admitsDispatch(TRIAL));
        assertFalse(breaker.admitsDispatch(STRAGGLER));

        clock.advance(TRIAL_TIMEOUT.plusSeconds(1));

        assertTrue(breaker.admitsDispatch(STRAGGLER));
    }

    @Test
    void 옛_세대의_프로브_결과는_버린다() {
        // 프로브는 네트워크 호출이라 결과가 늦게 온다 - 그 사이 회로가 닫혔다 다시 열렸다면
        // 그 판정은 이전 회로에 대한 것이다 (Codex sol 리뷰 P2).
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = openedBreaker(clock);
        clock.advance(PROBE_INTERVAL);
        OptionalLong stale = breaker.claimProbe();
        assertTrue(stale.isPresent());

        // 프로브가 도는 사이 다른 성공이 회로를 닫고, 새 장애가 다시 열었다.
        breaker.recordSuccess(STRAGGLER);
        breaker.recordFailure(STRAGGLER);
        assertFalse(breaker.admitsUpload(TRIAL));

        breaker.probeSucceeded(stale.getAsLong());

        assertFalse(breaker.admitsUpload(TRIAL), "옛 프로브가 방금 연 회로를 열어젖히면 안 된다");
    }

    @Test
    void 세대_번호는_열릴_때마다_바뀐다() {
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = openedBreaker(clock);
        clock.advance(PROBE_INTERVAL);
        long first = breaker.claimProbe().orElseThrow();

        breaker.recordSuccess(STRAGGLER);
        breaker.recordFailure(STRAGGLER);
        clock.advance(PROBE_INTERVAL);

        assertEquals(first + 1, breaker.claimProbe().orElseThrow());
    }

    @Test
    void 임계치가_0이하면_기동에_실패한다() {
        // 0이면 첫 요청부터 회로가 열려 서비스가 통째로 멎는다.
        assertThrows(IllegalArgumentException.class, () -> breaker(0, new SteppingClock()));
    }

    @Test
    void 프로브_간격이_0이하면_기동에_실패한다() {
        // cooldown()이 어떤 백오프 배수에도 0을 돌려줘, 열려 있는 내내 스케줄 틱마다(1초)
        // health가 나간다 - 지수 백오프가 통째로 무력화되고 복구 중인 AI를 두드린다.
        assertThrows(IllegalArgumentException.class, () -> new AiCircuitBreaker(
                1, Duration.ZERO, TRIAL_TIMEOUT, new SteppingClock()));
        assertThrows(IllegalArgumentException.class, () -> new AiCircuitBreaker(
                1, Duration.ofSeconds(-1), TRIAL_TIMEOUT, new SteppingClock()));
    }

    @Test
    void 시험_한도가_0이하면_기동에_실패한다() {
        // 자리를 잡는 즉시 만료로 보여 "시험 1건" 약속이 깨진다 - 반열림에서 큐에 남은
        // 작업까지 한꺼번에 AI로 나간다.
        assertThrows(IllegalArgumentException.class, () -> new AiCircuitBreaker(
                1, PROBE_INTERVAL, Duration.ZERO, new SteppingClock()));
    }

    // === 시험 자리 해제 (KAN-28) ===

    @Test
    void AI에_닿지_못한_시험은_자리를_놓아준다() {
        // 자리를 잡은 업로드가 저장 롤백이나 큐 거절로 끝나면 판정이 영영 안 온다 -
        // 한도(60초)를 기다리는 동안 나머지 업로드가 전부 503이고 AI는 이미 살아 있을 수 있다.
        AiCircuitBreaker breaker = halfOpenBreaker(new SteppingClock());
        assertTrue(breaker.admitsUpload(TRIAL));
        assertFalse(breaker.admitsUpload(STRAGGLER), "시험은 한 건뿐이다");

        breaker.releaseTrial(TRIAL);

        assertTrue(breaker.admitsUpload(STRAGGLER), "놓아준 자리는 다음 업로드가 가져간다");
    }

    @Test
    void 자리를_잡지_않은_작업은_시험을_놓아줄_수_없다() {
        // 종결 경로에서 무조건 부를 수 있어야 하므로, 남의 자리를 푸는 일은 없어야 한다.
        AiCircuitBreaker breaker = halfOpenBreaker(new SteppingClock());
        assertTrue(breaker.admitsUpload(TRIAL));

        breaker.releaseTrial(STRAGGLER);

        assertFalse(breaker.admitsUpload(STRAGGLER), "시험 자리는 그대로 TRIAL의 것이다");
    }

    @Test
    void 판정이_난_뒤의_해제는_회로를_되돌리지_않는다() {
        // 워커의 종결 finally가 성공 판정 뒤에도 부른다 - 여기서 상태가 흔들리면 안 된다.
        AiCircuitBreaker breaker = halfOpenBreaker(new SteppingClock());
        assertTrue(breaker.admitsDispatch(TRIAL));
        breaker.recordSuccess(TRIAL);

        breaker.releaseTrial(TRIAL);

        assertTrue(breaker.admitsUpload(STRAGGLER), "닫힌 회로는 닫힌 채로 남는다");
        assertTrue(breaker.admitsUpload(TRIAL), "시험 자리 제한도 없다");
    }

    @Test
    void 지표용_상태_값은_닫힘_0_반열림_1_열림_2다() {
        // CloudWatch 경보(ai-circuit-open, KAN-36)는 2(열림)에만 선다 - 반열림 1은 트래픽이 없으면
        // 시험 없이 오래 머무는 대기 상태라 경보 대상이 아니다. 매핑이 어긋나면 경보가 침묵하거나 밤새 운다.
        SteppingClock clock = new SteppingClock();
        AiCircuitBreaker breaker = breaker(1, clock);
        assertEquals(0, breaker.stateValue(), "닫힘");

        breaker.recordFailure(STRAGGLER);
        assertEquals(2, breaker.stateValue(), "열림");

        clock.advance(PROBE_INTERVAL);
        breaker.probeSucceeded(breaker.claimProbe().orElseThrow());
        assertEquals(1, breaker.stateValue(), "반열림 - 시험 대기");
        assertEquals(1, breaker.stateValue(), "요청이 없으면 반열림에 그대로 머문다");

        assertTrue(breaker.admitsUpload(TRIAL));
        breaker.recordFailure(TRIAL);
        assertEquals(2, breaker.stateValue(), "시험 실패는 곧바로 열림");

        clock.advance(PROBE_INTERVAL.multipliedBy(2));
        breaker.probeSucceeded(breaker.claimProbe().orElseThrow());
        assertTrue(breaker.admitsUpload(TRIAL));
        breaker.recordSuccess(TRIAL);
        assertEquals(0, breaker.stateValue(), "시험 성공은 닫힘");
    }

    private static AiCircuitBreaker breaker(int failureThreshold, SteppingClock clock) {
        return new AiCircuitBreaker(failureThreshold, PROBE_INTERVAL, TRIAL_TIMEOUT, clock);
    }

    /** 임계치 1로 열어 둔 회로 */
    private static AiCircuitBreaker openedBreaker(SteppingClock clock) {
        AiCircuitBreaker breaker = breaker(1, clock);
        breaker.recordFailure(STRAGGLER);
        return breaker;
    }

    /** health 프로브까지 통과해 시험 1건을 기다리는 회로 */
    private static AiCircuitBreaker halfOpenBreaker(SteppingClock clock) {
        AiCircuitBreaker breaker = openedBreaker(clock);
        clock.advance(PROBE_INTERVAL);
        breaker.probeSucceeded(breaker.claimProbe().orElseThrow());
        return breaker;
    }
}
