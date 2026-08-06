package app.accentury.backend.upload;

import app.accentury.backend.analysis.AnalysisJob;
import app.accentury.backend.analysis.AnalysisJobStatus;

/**
 * {@code POST .../recording}의 202 응답 (API 명세서 §3.3).
 * <p>
 * 점수나 품질 판정은 없다 - 결과는 상태 폴링(KAN-24)과 최종 결과(KAN-25)에서 제공한다.
 *
 * @param pollAfterMs 다음 상태 조회까지 기다릴 시간 - 서버가 통제한다 (§5.3)
 */
record VoiceUploadResponse(
        String analysisJobId,
        String itemId,
        int attempt,
        AnalysisJobStatus status,
        long pollAfterMs) {

    static VoiceUploadResponse from(AnalysisJob job, long pollAfterMs) {
        return new VoiceUploadResponse(job.id(), job.itemId(), job.attempt(), job.status(), pollAfterMs);
    }
}
