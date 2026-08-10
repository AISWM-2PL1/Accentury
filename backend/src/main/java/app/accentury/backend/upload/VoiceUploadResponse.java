package app.accentury.backend.upload;

import app.accentury.backend.analysis.AnalysisJob;
import app.accentury.backend.analysis.AnalysisJobStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code POST .../recording}의 202 응답 (API 명세서 §3.3).
 * <p>
 * 점수나 품질 판정은 없다 - 결과는 상태 폴링(KAN-24)과 최종 결과(KAN-25)에서 제공한다.
 *
 * @param pollAfterMs 다음 상태 조회까지 기다릴 시간 - 서버가 통제한다 (§5.3)
 */
@Schema(description = "업로드 접수 결과. 점수와 품질 판정은 여기 없다.")
record VoiceUploadResponse(
        @Schema(description = "분석 작업 식별자. 상태 조회(KAN-24)에 쓴다.",
                example = "a_1b2c3d4e-5f60-7a8b-9c0d-1e2f3a4b5c6d")
        String analysisJobId,

        @Schema(description = "업로드한 문항 식별자", example = "v1")
        String itemId,

        @Schema(description = """
                이 문항의 몇 번째 업로드인지. 앱의 로컬 재녹음은 세지 않고 실제로 올라온 것만 센다.
                5를 넘기면 429 `RATE_RETAKE_EXCEEDED`다.""",
                example = "1")
        int attempt,

        @Schema(description = "접수 직후에는 항상 `PROCESSING`이다.", example = "PROCESSING")
        AnalysisJobStatus status,

        @Schema(description = "다음 상태 조회까지 기다릴 시간(ms). 폴링 간격은 서버가 통제한다.", example = "800")
        long pollAfterMs) {

    static VoiceUploadResponse from(AnalysisJob job, long pollAfterMs) {
        return new VoiceUploadResponse(job.id(), job.itemId(), job.attempt(), job.status(), pollAfterMs);
    }
}
