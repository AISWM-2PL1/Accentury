package app.accentury.backend.testdefinition;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;

/**
 * {@code PUT /admin/v0/active-version} 요청 본문 (KAN-26, 명세서 §6).
 * <p>
 * 활성 지정과 롤백이 한 엔드포인트인 것은 명세서 §6의 정의를 그대로 따른 것이다 - 둘 다
 * "활성 버전을 어디에 둘 것인가"라는 같은 상태를 바꾼다.
 *
 * @param action      {@code ACTIVATE}면 {@code testVersion}이 필수, {@code ROLLBACK}이면 있으면 안 된다.
 * @param testVersion 활성으로 올릴 버전 (ACTIVATE 전용)
 * @param reason      감사 이력에 남길 사유 - 선택 입력이다.
 */
public record ActiveVersionRequest(ActiveVersionAudit.@Nullable Action action,
                                   @Nullable String testVersion,
                                   @Nullable String reason) {

    /**
     * 요청 형식을 검증한다 - 위반은 전부 400 {@code VALIDATION_FAILED}다 (§2.3).
     * <p>
     * {@code ROLLBACK}에 {@code testVersion}이 실려 오면 조용히 무시하지 않고 거절한다. 목적지는
     * 서버가 정하는데(직전 활성 버전) 요청에도 버전이 적혀 있으면, 둘이 다를 때 운영자가 무엇을
     * 기대했는지 알 수 없다 - 활성 전환은 되돌리기 어려운 조작이라 애매한 요청을 실행하지 않는다.
     * <p>
     * 사유 길이를 여기서 자르는 것은 저장 컬럼이 {@code varchar(200)}이라서다. 막지 않으면
     * 검증과 잠금을 다 지난 뒤 INSERT가 값 잘림으로 터져 500이 된다 (발행 검증의 식별자 길이
     * 제한과 같은 이유).
     */
    public void validate() {
        if (action == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "action은 ACTIVATE 또는 ROLLBACK이어야 합니다.");
        }
        if (reason != null && reason.length() > ActiveVersionAudit.MAX_REASON_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "reason은 " + ActiveVersionAudit.MAX_REASON_LENGTH + "자를 넘을 수 없습니다.");
        }
        switch (action) {
            case ACTIVATE -> {
                if (testVersion == null || testVersion.isBlank()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "ACTIVATE에는 testVersion이 필요합니다.");
                }
            }
            case ROLLBACK -> {
                if (testVersion != null) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "ROLLBACK은 직전 활성 버전으로 되돌리므로 testVersion을 받지 않습니다.");
                }
            }
        }
    }
}
