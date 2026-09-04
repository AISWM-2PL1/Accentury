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
 */
@Component
public class PollIntervals {

    private final AnalysisCongestion congestion;
    private final AccenturyProperties properties;

    public PollIntervals(AnalysisCongestion congestion, AccenturyProperties properties) {
        this.congestion = congestion;
        this.properties = properties;
    }

    public long pollAfterMs() {
        AccenturyProperties.Analysis analysis = properties.analysis();
        return congestion.processingJobs() >= analysis.congestionThreshold()
                ? analysis.congestedPollAfterMs()
                : analysis.pollAfterMs();
    }
}
