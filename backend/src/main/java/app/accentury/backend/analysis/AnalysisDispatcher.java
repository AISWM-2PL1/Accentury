package app.accentury.backend.analysis;

/**
 * 검증이 끝난 업로드를 AI 분석으로 넘기는 경계 (KAN-23 정의, KAN-24 구현).
 * <p>
 * 오디오 바이트는 이 경계를 지나 즉시 소멸해야 한다 - 구현은 AI 전달(§4.1) 외에
 * 어떤 저장소에도 오디오를 남기지 않는다 (FR-DP-01, §5.5).
 */
public interface AnalysisDispatcher {

    void dispatch(AnalysisRequest request);

    /**
     * AI 분석 1건에 필요한 전부 (§4.1 meta 파트와 대응).
     *
     * @param audio WAV 원본 - 클라이언트 업로드를 그대로 패스스루한다 (§4.1)
     */
    record AnalysisRequest(
            String analysisJobId,
            String sessionId,
            String itemId,
            String testVersion,
            String scoreVersion,
            long durationMs,
            byte[] audio) {
    }
}
