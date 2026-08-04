package app.accentury.backend.session;

import java.time.Instant;

/**
 * {@code POST /v0/sessions} 201 응답 (API 명세서 §3.1).
 * <p>
 * 정확히 이 5개 필드를 반환한다. {@code sessionToken}은 이 응답에서 딱 한 번 노출되고,
 * 서버에는 해시만 남는다 - 클라이언트가 잃어버리면 재발급이 아니라 새 세션이다.
 *
 * @param sessionId    {@code s_...} - 이후 API의 경로 파라미터
 * @param sessionToken {@code st_...} - {@code Authorization: Bearer}로 보낼 불투명 토큰
 * @param testVersion  이 세션에 고정된 테스트 정의 버전 (§5.4)
 * @param scoreVersion 이 세션에 고정된 점수 버전
 * @param expiresAt    토큰 만료 시각 (UTC)
 */
public record SessionResponse(
        String sessionId,
        String sessionToken,
        String testVersion,
        String scoreVersion,
        Instant expiresAt
) {
}
