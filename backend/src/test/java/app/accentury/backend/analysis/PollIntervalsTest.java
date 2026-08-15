package app.accentury.backend.analysis;

import app.accentury.backend.common.AccenturyProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * pollAfterMs 동적 조정의 명세 (KAN-24 AC, API 명세서 §5.3 규칙 1).
 * <p>
 * 진행 중 분석 전달이 임계치에 닿으면 서버가 간격을 올려 폴링 증폭을 막고,
 * 밀림이 풀리면 기준값으로 돌아온다.
 */
class PollIntervalsTest {

    private static final int THRESHOLD = 3;

    private final AnalysisBacklog backlog = new AnalysisBacklog();
    private final PollIntervals pollIntervals = new PollIntervals(backlog, properties());

    @Test
    void 임계치_미만이면_기준_간격이다() {
        backlog.started();
        backlog.started();

        assertEquals(800, pollIntervals.pollAfterMs());
    }

    @Test
    void 임계치에_닿으면_혼잡_간격으로_올린다() {
        for (int i = 0; i < THRESHOLD; i++) {
            backlog.started();
        }

        assertEquals(3000, pollIntervals.pollAfterMs());
    }

    @Test
    void 밀림이_풀리면_기준_간격으로_돌아온다() {
        for (int i = 0; i < THRESHOLD; i++) {
            backlog.started();
        }
        backlog.finished();

        assertEquals(800, pollIntervals.pollAfterMs());
    }

    private static AccenturyProperties properties() {
        return new AccenturyProperties("gn-2026.08.1", "sv-0.3",
                new AccenturyProperties.Session(Duration.ofMinutes(30)),
                new AccenturyProperties.Analysis(800, 3000, THRESHOLD, Duration.ofHours(24),
                        Duration.ofSeconds(60), Duration.ofMinutes(5), null, Duration.ofSeconds(10), 2, 4),
                new AccenturyProperties.Upload(30),
                new AccenturyProperties.Completion(60),
                new AccenturyProperties.Cors(List.of()),
                new AccenturyProperties.Result(null, Map.of()));
    }
}
