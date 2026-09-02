package app.accentury.backend.analysis;

import app.accentury.backend.SteppingClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 혼잡 판정 입력의 캐시 명세 (KAN-167).
 * <p>
 * 판정이 DB의 PROCESSING 건수를 보되 폴링마다 세지는 않는다는 계약이다 - TTL 안에서는 마지막
 * 값을 쓰고, TTL이 지나면 다시 센다. 판정 지연의 상한이 TTL이라는 것과, 캐시가 판정을
 * 영구히 고정하지 않는다는 것을 둘 다 못 박는다.
 */
class AnalysisCongestionTest {

    private static final Duration TTL = Duration.ofSeconds(1);

    private final SteppingClock clock = new SteppingClock();
    private final AtomicLong processing = new AtomicLong();
    private final AtomicInteger counts = new AtomicInteger();
    private final AnalysisCongestion congestion = new AnalysisCongestion(() -> {
        counts.incrementAndGet();
        return processing.get();
    }, TTL, clock);

    @Test
    void TTL_안에서는_다시_세지_않고_마지막_값을_쓴다() {
        processing.set(5);
        assertEquals(5, congestion.processingJobs());

        processing.set(40);
        clock.advance(TTL.minusMillis(1));

        assertEquals(5, congestion.processingJobs(), "TTL 안에서는 DB를 다시 보지 않는다");
        assertEquals(1, counts.get(), "count는 한 번만 나가야 한다");
    }

    @Test
    void TTL이_지나면_다시_센다() {
        processing.set(5);
        congestion.processingJobs();

        processing.set(40);
        clock.advance(TTL);

        assertEquals(40, congestion.processingJobs(), "TTL이 판정 지연의 상한이다");
        assertEquals(2, counts.get());
    }

    @Test
    void TTL이_0이면_매번_센다() {
        AnalysisCongestion uncached = new AnalysisCongestion(() -> {
            counts.incrementAndGet();
            return processing.get();
        }, Duration.ZERO, clock);

        processing.set(1);
        assertEquals(1, uncached.processingJobs());
        processing.set(2);
        assertEquals(2, uncached.processingJobs());
        assertEquals(2, counts.get());
    }

    @Test
    void 비우면_TTL_안이라도_다시_센다() {
        processing.set(5);
        congestion.processingJobs();

        processing.set(40);
        congestion.invalidate();

        assertEquals(40, congestion.processingJobs());
    }

    @Test
    void 음수_TTL은_기동_시점에_거부한다() {
        assertThrows(IllegalStateException.class,
                () -> new AnalysisCongestion(processing::get, Duration.ofMillis(-1), clock));
    }
}
