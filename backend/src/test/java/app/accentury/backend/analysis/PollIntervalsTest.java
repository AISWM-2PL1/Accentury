package app.accentury.backend.analysis;

import app.accentury.backend.PropertiesFixture;
import app.accentury.backend.SteppingClock;
import app.accentury.backend.common.AccenturyProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * pollAfterMs 동적 조정의 명세 (KAN-24 AC, API 명세서 §5.3 규칙 1).
 * <p>
 * 전 인스턴스의 진행 중 작업 수가 임계치에 닿으면 서버가 간격을 올려 폴링 증폭을 막고,
 * 밀림이 풀리면 기준값으로 돌아온다. 건수의 출처가 DB로 바뀐 뒤(KAN-167)에도 판정 규칙은
 * 그대로다 - 캐시 없이(TTL 0) 조립해 규칙만 본다. 캐시는 {@link AnalysisCongestionTest}가 본다.
 */
class PollIntervalsTest {

    private static final int THRESHOLD = 3;

    private final AtomicLong processing = new AtomicLong();
    private final PollIntervals pollIntervals = new PollIntervals(
            new AnalysisCongestion(processing::get, Duration.ZERO, new SteppingClock()), properties(),
            TestMetrics.analysisMetrics());

    @Test
    void 임계치_미만이면_기준_간격이다() {
        processing.set(THRESHOLD - 1);

        assertEquals(800, pollIntervals.pollAfterMs());
    }

    @Test
    void 임계치에_닿으면_혼잡_간격으로_올린다() {
        processing.set(THRESHOLD);

        assertEquals(3000, pollIntervals.pollAfterMs());
    }

    @Test
    void 밀림이_풀리면_기준_간격으로_돌아온다() {
        processing.set(THRESHOLD);
        assertEquals(3000, pollIntervals.pollAfterMs());

        processing.set(THRESHOLD - 1);

        assertEquals(800, pollIntervals.pollAfterMs());
    }

    private static AccenturyProperties properties() {
        return PropertiesFixture.withAnalysis(
                PropertiesFixture.analysis(THRESHOLD, null, Duration.ofSeconds(60)));
    }
}
