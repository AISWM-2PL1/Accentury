package app.accentury.backend.common;

import java.util.List;

/**
 * 오류 봉투에 문항 ID 목록 확장 필드를 얹는 예외 (API 명세서 §2.3).
 * <p>
 * "어느 문항 때문인지"가 응답에 있어야 클라이언트가 해당 문항으로 안내할 수 있다 -
 * {@code /complete}의 누락(missingItems)과 재녹음 필요(retakeItems)가 그 경우다
 * (§3.6, KAN-16 AC - 누락 문항 식별). {@code /result}(KAN-25)의 pendingItems와
 * retakeItems도 같은 확장이다 (§3.7). {@link GlobalExceptionHandler}가
 * {@link ItemsErrorResponse}로 변환한다.
 */
public class ItemsApiException extends ApiException {

    /** 확장 필드 이름 - 오류 코드의 의미와 짝이 정해져 있다 (§3.6, §3.7). */
    public enum ItemsField {
        MISSING_ITEMS,
        RETAKE_ITEMS,
        PENDING_ITEMS
    }

    private final ItemsField field;
    private final List<String> itemIds;

    public ItemsApiException(ErrorCode code, ItemsField field, List<String> itemIds) {
        super(code);
        this.field = field;
        this.itemIds = List.copyOf(itemIds);
    }

    public ItemsField field() {
        return field;
    }

    public List<String> itemIds() {
        return itemIds;
    }
}
