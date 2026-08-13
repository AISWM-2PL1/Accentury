package app.accentury.backend.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * {@code POST .../complete}의 200 응답 (API 명세서 §3.6).
 * <p>
 * READY는 {@code status} 하나뿐이다 - 점수와 등급은 {@code /result}(§3.7)가 공개하는
 * 유일한 곳이라 여기 싣지 않는다 (KAN-12 - 중간 점수 미노출과 같은 취지).
 *
 * @param status       PROCESSING(분석 대기) 또는 READY(결과 확정)
 * @param pendingItems PROCESSING일 때만 - 분석이 끝나기를 기다리는 문항, seq 순서
 * @param pollAfterMs  PROCESSING일 때만 - 다음 완료 시도까지 기다릴 시간(ms). 서버가
 *                     통제하고 혼잡 시 올린다 (§5.3 규칙 1, 상태 조회와 같은 산출)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompleteResponse(Status status, @Nullable List<String> pendingItems, @Nullable Long pollAfterMs) {

    public enum Status {
        PROCESSING,
        READY
    }

    static CompleteResponse ready() {
        return new CompleteResponse(Status.READY, null, null);
    }

    static CompleteResponse processing(List<String> pendingItems, long pollAfterMs) {
        return new CompleteResponse(Status.PROCESSING, List.copyOf(pendingItems), pollAfterMs);
    }
}
