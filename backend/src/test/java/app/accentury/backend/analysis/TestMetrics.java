package app.accentury.backend.analysis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Duration;

/**
 * 분석 지표의 테스트용 조립 (KAN-38).
 * <p>
 * 계측은 동작을 바꾸지 않으므로 대부분의 테스트는 지표를 보지 않는다 - 그 테스트들이
 * 레지스트리와 게이지 원본까지 직접 엮지 않도록 한 줄로 줄인다. 지표 자체의 명세는
 * {@link AnalysisMetricsTest}가 본다.
 */
final class TestMetrics {

    private TestMetrics() {
    }

    static AnalysisMetrics analysisMetrics() {
        return analysisMetrics(new SimpleMeterRegistry());
    }

    static AnalysisMetrics analysisMetrics(SimpleMeterRegistry registry) {
        return new AnalysisMetrics(registry,
                new AnalysisCongestion(() -> 0L, Duration.ZERO, Clock.systemUTC()),
                new AnalysisBacklog());
    }
}
