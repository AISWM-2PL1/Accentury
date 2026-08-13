package app.accentury.backend.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 문항 ID 목록 확장 필드가 붙은 오류 봉투 (API 명세서 §2.3, §3.6, §3.7).
 * <p>
 * 공통 봉투({@link ErrorResponse})의 다섯 필드에 {@code missingItems}/{@code retakeItems}/
 * {@code pendingItems} 중 하나가 더해진 형태다 - 셋 중 채워지는 것은 항상 하나이고
 * 나머지는 직렬화에서 빠진다. 클라이언트는 모르는 필드를 무시해야 하므로(§2.3)
 * 기본 봉투만 아는 클라이언트도 그대로 동작한다.
 */
public record ItemsErrorResponse(
        String code,

        String message,

        boolean retryable,

        // 공통 봉투와 같은 자리 - 이 확장이 붙는 409/422는 요청 제한이 아니라 항상 null이다
        @Nullable Long retryAfterMs,

        String correlationId,

        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<String> missingItems,

        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<String> retakeItems,

        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<String> pendingItems
) {

    public static ItemsErrorResponse of(ItemsApiException e, String correlationId) {
        return new ItemsErrorResponse(
                e.code().name(), e.getMessage(), e.code().retryable(), null, correlationId,
                e.field() == ItemsApiException.ItemsField.MISSING_ITEMS ? e.itemIds() : null,
                e.field() == ItemsApiException.ItemsField.RETAKE_ITEMS ? e.itemIds() : null,
                e.field() == ItemsApiException.ItemsField.PENDING_ITEMS ? e.itemIds() : null);
    }
}
