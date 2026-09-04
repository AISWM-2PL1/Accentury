package app.accentury.backend.common;

import org.jspecify.annotations.Nullable;

/**
 * {@code Idempotency-Key} 헤더 규칙 (API 명세서 §2.2 - 비용 발생 POST 필수: 업로드/답안/완료).
 * <p>
 * 검증을 한 곳에 두어 API마다 규칙이 갈라지지 않게 한다 (KAN-23 업로드, KAN-15 답안).
 */
public final class IdempotencyKeys {

    /** 저장 컬럼 길이와 같다 - 검증 없이 저장하면 400이 아니라 500이 된다. */
    public static final int MAX_LENGTH = 100;

    private IdempotencyKeys() {
    }

    /** 계약상 필수인 키의 존재와 길이를 검증해 돌려준다. 위반은 400 {@code VALIDATION_FAILED} */
    public static String require(@Nullable String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key 헤더가 필요합니다.");
        }
        if (idempotencyKey.length() > MAX_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key가 너무 깁니다. (최대 " + MAX_LENGTH + "자)");
        }
        return idempotencyKey;
    }
}
