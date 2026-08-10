package app.accentury.backend.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET .../analyses/{jobId}}의 200 응답 - 시도(작업) 1건의 상태 (API 명세서 §3.4).
 * <p>
 * 일괄 조회의 문항 항목과 같은 스키마에 {@code modelVersion}, {@code scoreVersion}을
 * 더한 것이다. 문항 대표 상태가 아니라 <b>이 작업 자체의 상태</b>를 반환한다.
 * 점수는 싣지 않는다 (KAN-12).
 *
 * @param modelVersion COMPLETED일 때만 - 분석에 쓰인 AI 모델 버전 (KAN-24 AC)
 * @param scoreVersion COMPLETED일 때만 - AI가 확인한 점수 버전
 * @param pollAfterMs  다음 조회까지 기다릴 시간(ms) - 모든 상태 응답 공통 (§5.3)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisJobStatusResponse(
        String itemId,
        AnalysisStatusResponse.Status status,
        @Nullable String quality,
        AnalysisStatusResponse.Item.@Nullable Error error,
        @Nullable String modelVersion,
        @Nullable String scoreVersion,
        long pollAfterMs) {

    static AnalysisJobStatusResponse from(AnalysisJob job, long pollAfterMs) {
        AnalysisStatusResponse.Item item = AnalysisStatusResponse.Item.from(job);
        return new AnalysisJobStatusResponse(job.itemId(), item.status(), item.quality(), item.error(),
                job.modelVersion(), job.scoreVersion(), pollAfterMs);
    }
}
