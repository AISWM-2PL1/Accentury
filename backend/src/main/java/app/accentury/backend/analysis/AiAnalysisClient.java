package app.accentury.backend.analysis;

import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;

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

    /**
     * AI가 요청을 받을 수 있는 상태인가 - {@code GET /internal/v0/health} (§4.2).
     * 회로 차단기(KAN-28)의 복구 판정 전용이다. 추론을 태우지 않으므로 GPU도, 사용자의
     * 시도 예산(§2.5)도 쓰지 않는다.
     *
     * @return 응답이 없거나 UP이 아니면 false - 예외를 던지지 않는다
     */
    boolean healthy();

    sealed interface Outcome permits Completed, Rejected {
    }

    /**
     * 분석 성공 (§4.1 200 OK).
     *
     * @param intonationScore 0~100 원값 - 20점 만점 환산은 BE 집계(KAN-21, 25)가 한다 (§4.3)
     */
    record Completed(int intonationScore, String qualityCode,
                     String modelVersion, String scoreVersion) implements Outcome {
    }

    /**
     * 분석 판정 실패 (§4.1 422) 또는 계약 위반 응답.
     *
     * @param errorCode 상태 응답의 error.code로 그대로 나간다 (§3.4, 예: AUDIO_TOO_QUIET)
     * @param retryable 재녹음(새 시도)이 도움이 되는가 - RETRYABLE_FAILED와 FAILED를 가른다
     * @param cause     이 거절이 <b>AI의 상태</b>에 대해 말해주는 것 - 회로 차단기(KAN-28)가 읽는다
     */
    record Rejected(String errorCode, boolean retryable, Cause cause) implements Outcome {

        /**
         * 거절의 출처 - 재전송 여부가 아니라 <b>회로 판정</b>을 가른다 (KAN-28).
         * <p>
         * 둘 다 재전송하지 않는다는 점은 같다 (같은 오디오에 같은 답이 온다). 다른 것은
         * "AI가 정상인가"다 - 계약대로 온 판정은 서버가 멀쩡하다는 증거지만, 계약 위반은
         * 서버가 고장 났다는 증거다. 이 구분이 없으면 응답만 하고 계약을 어기는 AI 앞에서
         * 회로가 영영 닫혀 있는다.
         */
        enum Cause {
            /** AI가 계약대로 판정했다 (§4.1 422) - 서버는 정상이다 */
            JUDGED,
            /** 응답은 왔지만 계약(§4.1)과 다르다 - 답은 하지만 쓸 수 없는 상태다 */
            CONTRACT_VIOLATION
        }

        /** 계약대로 온 판정 실패 (§4.1 422) - AI는 정상이다 */
        static Rejected judged(String errorCode, boolean retryable) {
            return new Rejected(errorCode, retryable, Cause.JUDGED);
        }

        /**
         * 계약(§4.1) 위반 - 해석 불가 본문, 범위 밖 점수, 버전 불일치, 모르는 판정 코드.
         * 사용자에게는 재전송이 소용없는 INTERNAL_ERROR지만, 회로에는 실패로 센다.
         */
        static Rejected contractViolation() {
            return new Rejected(ErrorCode.INTERNAL_ERROR.name(), false, Cause.CONTRACT_VIOLATION);
        }
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

        public AiUnavailableException(String message, Kind kind, @Nullable Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }
}
