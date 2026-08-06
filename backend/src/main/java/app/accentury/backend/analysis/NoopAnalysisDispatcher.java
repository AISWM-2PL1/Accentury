package app.accentury.backend.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * KAN-24(작업 관리)와 KAN-22(내부 AI 호출) 전까지의 자리 표시자.
 * <p>
 * 아무것도 전달하지 않으므로 작업은 PROCESSING으로 남는다 - 상태 전이는 KAN-24 범위다.
 * 받은 오디오는 메서드를 벗어나는 즉시 참조가 사라져 수거된다 (FR-DP-01).
 */
@Component
class NoopAnalysisDispatcher implements AnalysisDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NoopAnalysisDispatcher.class);

    @Override
    public void dispatch(AnalysisRequest request) {
        // 오디오 바이트는 로그에 남기지 않는다 (§2.6, NFR-SC-07)
        log.debug("분석 전달 대기 jobId={} itemId={} - AI 호출은 KAN-24에서 구현",
                request.analysisJobId(), request.itemId());
    }
}
