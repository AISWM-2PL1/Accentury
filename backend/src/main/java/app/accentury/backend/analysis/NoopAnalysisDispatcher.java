package app.accentury.backend.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI 서버 없이 BE만 띄우는 개발 모드의 디스패처 - {@code accentury.analysis.ai-base-url}
 * 미설정 시 {@link AnalysisDispatchConfig}가 이걸 조립한다 (실제 호출은 {@link HttpAnalysisDispatcher}).
 * <p>
 * 아무것도 전달하지 않으므로 작업은 PROCESSING으로 남다가 {@link AnalysisJobTimeout}이
 * RETRYABLE_FAILED(ANALYSIS_TIMEOUT)로 정리한다. 받은 오디오는 메서드를 벗어나는 즉시
 * 참조가 사라져 수거된다 (FR-DP-01).
 */
class NoopAnalysisDispatcher implements AnalysisDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NoopAnalysisDispatcher.class);

    @Override
    public void dispatch(AnalysisRequest request) {
        // 오디오 바이트는 로그에 남기지 않는다 (§2.6, NFR-SC-07)
        log.debug("분석 전달 생략 jobId={} itemId={} - ai-base-url 미설정 개발 모드",
                request.analysisJobId(), request.itemId());
    }
}
