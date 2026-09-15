package app.accentury.backend.analysis;

import app.accentury.backend.observability.ServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 분석 파이프라인 지표의 명세 (KAN-38 AC - 진행 중 건수와 타임아웃 알림, 혼잡 발동 비율, P95).
 * <p>
 * 값이 어떻게 갈라지는지만 본다. 게이지가 <b>등록되어 있는가</b>도 함께 보는 이유는, 경보와
 * 대시보드가 이름을 문자열로 적어 두기 때문이다 - 등록이 빠지면 그래프가 조용히 비고
 * 경보는 결측 처리 규칙에 따라 울거나 영영 울지 않는다.
 */
class AnalysisMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicLong processing = new AtomicLong();
    private final AnalysisBacklog backlog = new AnalysisBacklog();
    private final AnalysisMetrics metrics = new AnalysisMetrics(registry,
            new AnalysisCongestion(processing::get, Duration.ZERO, Clock.systemUTC()), backlog);

    @Test
    void 진행_중_건수를_전_인스턴스와_이_인스턴스로_나눠_낸다() {
        // 태스크가 여럿이면 두 값이 갈라진다 - 경보는 전 인스턴스 값을 본다 (KAN-167).
        processing.set(7);
        backlog.started();
        backlog.started();

        assertEquals(7.0, gauge(ServiceMetrics.ANALYSIS_PROCESSING));
        assertEquals(2.0, gauge(ServiceMetrics.ANALYSIS_INFLIGHT));
    }

    @Test
    void 타임아웃을_사유별로_센다() {
        // 대응이 다르다 - 실행 잔류는 워커나 AI 쪽이고, 큐 유실은 프로세스가 죽은 흔적이다.
        metrics.recordTimeouts(2, 0);
        metrics.recordTimeouts(1, 3);

        assertEquals(3.0, counter(ServiceMetrics.ANALYSIS_TIMEOUTS, "reason", "stuck"));
        assertEquals(3.0, counter(ServiceMetrics.ANALYSIS_TIMEOUTS, "reason", "lost"));
    }

    @Test
    void 스위퍼가_0건이어도_카운터를_망가뜨리지_않는다() {
        metrics.recordTimeouts(0, 0);

        assertEquals(0.0, counter(ServiceMetrics.ANALYSIS_TIMEOUTS, "reason", "stuck"));
        assertNotNull(registry.find(ServiceMetrics.ANALYSIS_TIMEOUTS).tag("reason", "stuck").counter(),
                "0건이어도 카운터는 등록되어 있어야 한다 - 없으면 대시보드가 0과 모름을 구분 못 한다");
    }

    @Test
    void 혼잡_판정_회차를_분자와_분모로_센다() {
        // 혼잡 발동 비율 = congested(true) / 전체. 임계치가 적절한지의 유일한 증거다.
        metrics.recordPollDecision(true);
        metrics.recordPollDecision(false);
        metrics.recordPollDecision(false);

        assertEquals(1.0, counter(ServiceMetrics.ANALYSIS_POLL_DECISIONS, "congested", "true"));
        assertEquals(2.0, counter(ServiceMetrics.ANALYSIS_POLL_DECISIONS, "congested", "false"));
    }

    @Test
    void 성공한_분석의_지연을_백분위까지_낸다() {
        metrics.recordCompleted(Duration.ofMillis(1200).toNanos());

        assertEquals(1, registry.get(ServiceMetrics.ANALYSIS_DURATION).timer().count());
        assertNotNull(registry.find(ServiceMetrics.ANALYSIS_DURATION + ".percentile")
                        .tag("phi", String.valueOf(ServiceMetrics.PERCENTILE)).gauge(),
                "CloudWatch는 Timer의 백분위를 스스로 내보내지 않는다 - 게이지가 있어야 P95가 오른다");
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double counter(String name, String tagKey, String tagValue) {
        var counter = registry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? -1 : counter.count();
    }
}
