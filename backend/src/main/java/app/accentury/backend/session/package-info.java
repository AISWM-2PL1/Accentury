/**
 * 익명 테스트 세션 (KAN-9).
 * <p>
 * 계정과 회원 없이 한 번의 테스트를 추적하는 임시 세션을 만들고 인증한다.
 * 토큰은 불투명 토큰 + 서버 측 TTL(PostgreSQL 세션 저장소)로 관리한다 (API 명세서 §2.1, §3.1).
 */
@NullMarked
package app.accentury.backend.session;

import org.jspecify.annotations.NullMarked;
