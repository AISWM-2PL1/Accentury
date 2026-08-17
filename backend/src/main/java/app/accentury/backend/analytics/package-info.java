/**
 * 익명 집계 카운터 (KAN-106, SRS FR-AN-10, NFR-PR-03).
 * <p>
 * 개인 결과는 24시간 뒤 파기되지만(§5.5) "몇 명이 응시했고 등급 분포가 어떤가"는 남아야
 * 한다. 이 패키지가 남기는 것은 <b>영속 데이터로 허용된 유일한 것</b>인 일자별 카운터
 * 한 줄뿐이다 - 세션 ID도, 토큰도, IP도, 개별 점수 행도 저장하지 않는다.
 * <p>
 * 증가 지점은 둘이다: {@code POST /v0/sessions} 성공(응시 시도)과 {@code /complete}의
 * 결과 확정(완주, 등급, 점수 합계). 두 곳 모두 <b>사용자 트랜잭션이 커밋된 뒤</b>
 * 별도 트랜잭션으로 증가하고, 실패는 로그로만 남는다 (2026-08-17 확정) - 통계가
 * 세션 생성이나 결과 반환을 막으면 안 된다는 것이 이 티켓의 제약이다.
 */
@NullMarked
package app.accentury.backend.analytics;

import org.jspecify.annotations.NullMarked;
