package app.accentury.backend.analysis;

/**
 * BE -> AI 분석 호출의 경계 (KAN-24, API 명세서 §4.1).
 * <p>
 * 결과는 세 갈래다: 분석 성공({@link Completed}), 분석 판정 실패({@link Rejected} -
 * 같은 오디오를 다시 보내도 결과가 같으므로 재전송하지 않는다), 일시 장애
 * ({@link AiUnavailableException} - 오디오가 메모리에 있는 동안만 재전송할 수 있다).
 */
public interface AiAnalysisClient {

    /**
     * 음성 1건을 동기로 분석한다 - 비동기화는 호출부({@link HttpAnalysisDispatcher}) 책임이다.
     *
     * @param correlationId BE 요청에서 전파된 추적 ID (§2.2) - AI까지 흘러간다
     * @throws AiUnavailableException 연결 실패, 타임아웃, 5xx - 재전송해볼 수 있는 장애
     */
    Outcome analyze(AnalysisDispatcher.AnalysisRequest request, String correlationId);

    sealed interface Outcome permits Completed, Rejected {
    }

    /**
     * 분석 성공 (§4.1 200 OK).
     *
     * @param intonationScore 0~100 원값 - 20점 만점 환산은 BE 집계(KAN-21·25)가 한다 (§4.3)
     */
    record Completed(int intonationScore, String qualityCode,
                     String modelVersion, String scoreVersion) implements Outcome {
    }

    /**
     * 분석 판정 실패 (§4.1 422) 또는 계약 위반 응답.
     *
     * @param errorCode 상태 응답의 error.code로 그대로 나간다 (§3.4, 예: AUDIO_TOO_QUIET)
     * @param retryable 재녹음(새 시도)이 도움이 되는가 - RETRYABLE_FAILED와 FAILED를 가른다
     */
    record Rejected(String errorCode, boolean retryable) implements Outcome {
    }

    /** 일시 장애 - 호출부가 재전송 예산({@code aiRetries}) 안에서 다시 시도한다 */
    class AiUnavailableException extends RuntimeException {

        /**
         * 장애 분류 - 요청이 AI에 도달했는지가 시도 예산(§2.5) 판정을 가른다
         * (Codex sol 리뷰 P2 - 도달한 5xx를 미도달과 묶으면 상한이 우회된다).
         */
        public enum Kind {
            /** 연결, 전송 실패 - AI에 도달하지 않았다. GPU 미소모라 예산에서 뺀다 */
            UNREACHED,
            /** 응답 대기 초과 - 도달했고 추론이 진행 중일 수 있다. 예산에 포함 */
            TIMED_OUT,
            /** 5xx 응답 - 도달했고 추론까지 했을 수 있다. 예산에 포함 */
            SERVER_ERROR
        }

        private final Kind kind;

        public AiUnavailableException(String message, Kind kind, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }
}
