/**
 * 모든 API가 공유하는 공통 기반 - 오류 봉투·오류 사전·전역 예외 처리·correlation ID.
 * <p>
 * {@code @NullMarked}: 이 패키지 안의 모든 타입은 <b>기본이 null 불가</b>다.
 * null이 허용되는 자리만 {@code @Nullable}로 명시한다 (예: ErrorResponse.retryAfterMs).
 * <p>
 * 하위 패키지에 상속되지 않는다 - 새 패키지(session, analysis...)를 만들면
 * 그 패키지에도 이런 package-info.java를 하나 만들어야 적용된다.
 */
@NullMarked
package app.accentury.backend.common;

import org.jspecify.annotations.NullMarked;
