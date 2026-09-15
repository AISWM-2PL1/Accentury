package app.accentury.backend.analysis;

import app.accentury.backend.common.AccenturyProperties;
import org.springframework.stereotype.Component;

/**
 * 상태 응답의 {@code pollAfterMs} 산출 (KAN-24, API 명세서 §5.3 규칙 1).
 * <p>
 * 모든 상태 응답은 이 값을 포함하고 클라이언트는 반드시 따른다. 분석이 밀리면 대기 화면
 * 체류가 길어져 폴링 요청이 증폭되므로(3초 체류가 60초가 되면 요청 20배), 전 인스턴스의
 * 진행 중 작업 수({@link AnalysisCongestion}, KAN-167)가 임계치를 넘으면 서버가 간격을 올려
 * 스스로 압력을 뺀다. 기준값과 혼잡값, 임계치 모두 설정으로 조정할 수 있다.
 * <p>
 * 판정 결과는 매번 지표로 샌다 ({@link AnalysisMetrics}, KAN-38) - 혼잡 간격이 실제 부하에서
 * 얼마나 자주 켜지는지가 임계치({@code congestion-threshold})가 적절한지의 유일한 증거다.
 * 늘 켜져 있으면 임계치가 낮거나 AI가 부족한 것이고, 한 번도 안 켜지면 임계치가 장식이다.
 */
@Component
public class PollIntervals {

    private final AnalysisCongestion congestion;
    private final AccenturyProperties properties;
    private final AnalysisMetrics metrics;

    public PollIntervals(AnalysisCongestion congestion, AccenturyProperties properties,
                         AnalysisMetrics metrics) {
        this.congestion = congestion;
        this.properties = properties;
        this.metrics = metrics;
    }

    public long pollAfterMs() {
        AccenturyProperties.Analysis analysis = properties.analysis();
        boolean congested = congestion.processingJobs() >= analysis.congestionThreshold();
        metrics.recordPollDecision(congested);
        return congested ? analysis.congestedPollAfterMs() : analysis.pollAfterMs();
    }
}
