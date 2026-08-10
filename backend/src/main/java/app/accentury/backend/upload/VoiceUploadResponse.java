package app.accentury.backend.upload;

import app.accentury.backend.analysis.AnalysisJob;
import app.accentury.backend.analysis.AnalysisJobStatus;

/**
 * {@code POST .../recording}의 202 응답 (API 명세서 §3.3).
 * <p>
 * 점수나 품질 판정은 없다 - 결과는 상태 폴링(KAN-24)과 최종 결과(KAN-25)에서 제공한다.
 *
 * @param analysisJobId 분석 작업 식별자 - 상태 조회(KAN-24)에 쓴다
 * @param itemId        업로드한 문항 식별자
 * @param attempt       이 문항의 몇 번째 업로드인지. 앱의 로컬 재녹음은 세지 않고 실제로 올라온 것만 센다 -
 *                      5를 넘기면 429 {@code RATE_RETAKE_EXCEEDED}다
 * @param status        접수 직후에는 항상 {@code PROCESSING}이다
 * @param pollAfterMs   다음 상태 조회까지 기다릴 시간(ms) - 서버가 통제한다 (§5.3)
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
