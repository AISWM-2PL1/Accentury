package app.accentury.backend.training;

import org.jspecify.annotations.Nullable;

/**
 * 학습 샘플 1건 - WAV 1개와 메타 JSON 1개가 된다 (KAN-201 객체 규약).
 * <p>
 * 사용자 식별 정보는 없다 (세션은 익명, §2.1). 값은 전부 분석 요청({@code AnalysisRequest})과 AI 응답
 * (§4.1)에서 그대로 온 것이고, 점수는 KAN-200의 계수 전처리와 sv-0.3의 20점 환산 <b>이전</b>의
 * AI 원점수다 - {@code analysis_job.intonation_score}와 같은 값이다.
 *
 * @param region          출신 지역 코드 10개 중 하나 또는 {@code UNKNOWN} - 객체 키의 첫 조각과 같다.
 * @param scriptKey       문항 대본 키 - 더미 정의의 문항은 null이다.
 * @param durationMs      서버가 WAV에서 계산한 길이 (클라이언트 신고값이 아니다).
 * @param outcome         AI 응답으로 정한 종결 상태. 드물게 {@code analysis_job.status}와 다를 수 있다 - 스위퍼가
 *                        먼저 TIMED_OUT으로 종결한 작업의 늦은 응답은 조건부 UPDATE 0행으로 버려지지만 AI 원점수
 *                        자체는 유효해 그대로 남긴다 (Claude 검증자 리뷰, 의도).
 * @param intonationScore AI 원점수 0~100 - 성공에만 있다.
 * @param qualityCode     AI 응답 §4.1 값 - 성공에만 있다.
 * @param modelVersion    AI 응답 §4.1 값 - 성공에만 있다.
 * @param aiScoreVersion  AI 응답 §4.1의 scoreVersion - 성공에만 있다. 세션의 {@code scoreVersion}과 다른 값이다.
 * @param errorCode       판정 실패 코드(예: AUDIO_TOO_QUIET) - 판정 실패에만 있다.
 * @param correlationId   AI 호출 상관 ID - AI 로그와 대조용.
 * @param audio           업로드 받은 WAV 바이트 그대로 (16kHz Mono 16-bit PCM, 최대 1MB).
 */
public record TrainingSample(
        String analysisJobId,
        String sessionId,
        String itemId,
        String region,
        @Nullable String scriptKey,
        String testVersion,
        String scoreVersion,
        long durationMs,
        Outcome outcome,
        @Nullable Integer intonationScore,
        @Nullable String qualityCode,
        @Nullable String modelVersion,
        @Nullable String aiScoreVersion,
        @Nullable String errorCode,
        String correlationId,
        byte[] audio) {

    /** 분석 작업 종결 상태와 같은 세 값 ({@code AnalysisJobStatus}의 종결 상태). */
    public enum Outcome { COMPLETED, FAILED, RETRYABLE_FAILED }

    /** 객체 키 접두 - WAV와 JSON이 이 뒤에 확장자만 다르게 나란히 놓인다. */
    public String keyPrefix() {
        return region + "/" + testVersion + "/" + sessionId + "/" + itemId + "/" + analysisJobId;
    }
}
