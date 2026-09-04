package app.accentury.backend.analysis;

/**
 * 분석 작업 상태 (API 명세서 §3.4).
 * <p>
 * 명세의 상태값 중 {@code NOT_SUBMITTED}는 "작업이 아직 없는 문항"을 뜻하므로
 * 작업 엔티티의 상태로는 존재하지 않는다 - 문항 단위 표현은
 * {@link AnalysisStatusResponse.Status}가 맡는다. PROCESSING 이후의 전이는 전부
 * {@link AnalysisJobTransitions}의 조건부 UPDATE를 거친다 (KAN-24).
 */
public enum AnalysisJobStatus {
    PROCESSING,
    COMPLETED,
    RETRYABLE_FAILED,
    FAILED
}
