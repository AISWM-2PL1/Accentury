package app.accentury.backend.session;

import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(description = "세션 생성 결과. `sessionToken`은 이 응답에서만 볼 수 있다.")
public record SessionResponse(
        @Schema(description = "세션 식별자. 이후 API의 경로 파라미터로 쓴다.", example = "s_3f9a2c1e-8b7d-4a60-9e21-5c4b7a8d0f13")
        String sessionId,

        @Schema(description = """
                세션 토큰. 서버에는 해시만 남으므로 이 응답에서 딱 한 번 노출된다.
                잃어버리면 재발급이 아니라 새 세션을 만들어야 한다.""",
                example = "st_CzBVep_E6Q4zWH2ix-wRNluApcrvFDleg6jN8hc8YYY")
        String sessionToken,

        @Schema(description = "이 세션에 고정된 테스트 정의 버전. `GET /v0/tests/{testVersion}`에 그대로 넣는다.",
                example = "gn-2026.08.1")
        String testVersion,

        @Schema(description = "이 세션에 고정된 점수 산정 버전", example = "sv-0.3")
        String scoreVersion,

        @Schema(description = "토큰 만료 시각(UTC). 기본 30분이다.", example = "2026-08-10T04:30:00Z")
        Instant expiresAt
) {
}
