/**
 * 카카오톡 공유 웹훅 수신 - "전송 완료"의 익명 집계 (KAN-164, SRS FR-SH-06).
 * <p>
 * 앱은 카톡으로 넘긴 것({@code share_launched})까지만 안다. 카카오 SDK는 사용자가 실제로
 * 보냈는지를 돌려주지 않으므로, 실제 전송 수는 카카오가 우리 서버로 되돌려 주는 웹훅
 * ({@code POST /v0/share/kakao/webhook}, API 명세서 §3.8)으로만 알 수 있다.
 * <p>
 * 남기는 것은 일자와 캠페인별 전송 수 한 줄과, 중복 콜백을 거르기 위한 카카오 리소스 ID
 * (보존 기간 뒤 삭제)뿐이다 - 세션 ID, 토큰, 점수, 채팅방 정보는 저장하지 않는다.
 * 집계 카운터(KAN-106)와 키가 달라 별도 테이블이고, 조회는 같은 관리자 API
 * ({@code GET /admin/v0/analytics}의 {@code shares})에 얹는다.
 */
@NullMarked
package app.accentury.backend.share;

import org.jspecify.annotations.NullMarked;
