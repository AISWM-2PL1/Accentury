package app.accentury.backend.testdefinition;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * {@code PUT /admin/v0/active-version} 응답 (KAN-26, 명세서 §6).
 *
 * @param activeVersion   이 호출 뒤의 활성 버전
 * @param previousVersion 롤백하면 돌아갈 자리. 최초 발행 상태에서만 null이다.
 * @param activatedAt     현재 활성 버전이 활성이 된 시각
 * @param changed         이번 호출이 실제로 바꿨는지. 이미 그 버전이 활성이면 false이고,
 *                        그때는 감사 이력도 늘지 않는다 - 운영자가 재시도했는지 알 수 있게
 *                        200 안에서 구분해 준다.
 */
public record ActiveVersionResponse(String activeVersion,
                                    @Nullable String previousVersion,
                                    Instant activatedAt,
                                    boolean changed) {
}
