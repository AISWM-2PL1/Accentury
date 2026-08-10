package app.accentury.backend.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.1 문서 정의 (App-Backend API 명세서 §2).
 * <p>
 * 노션 명세서가 정본이고 이 문서는 <b>구현이 실제로 하는 일</b>을 그대로 노출한다.
 * 둘이 어긋나면 구현이나 명세서 중 하나가 틀린 것이므로, 리뷰에서 그 차이를 보는 것이 목적이다.
 * <p>
 * Swagger UI는 {@code /swagger-ui.html}, 원본 JSON은 {@code /v3/api-docs}다.
 */
@Configuration
public class OpenApiConfig {

    /**
     * 세션 토큰 보안 스킴 이름.
     * <p>
     * 인증이 필요한 엔드포인트는 {@code @SecurityRequirement(name = OpenApiConfig.SESSION_TOKEN)}으로
     * 이 이름을 참조한다. 문자열을 직접 쓰면 오타가 나도 Swagger UI에서 조용히 자물쇠만 사라진다.
     */
    public static final String SESSION_TOKEN = "sessionToken";

    /**
     * 설명에 목록을 쓸 때는 <b>항목 사이에 빈 줄을 넣는다.</b>
     * <p>
     * Swagger UI의 마크다운 엔진(Remarkable)은 빈 줄이 없는 목록을 tight list로 보고 항목을 {@code <p>}로
     * 감싸지 않는다. 그러면 여백이 줄 간격뿐인데, 인라인 코드에는 위아래 {@code padding: 5px}가 붙어 있어
     * 배경 상자가 줄 간격보다 커진다. 결과적으로 코드 조각이 위아래 줄을 침범해 글자가 겹쳐 보인다.
     * 빈 줄을 넣으면 loose list가 되어 {@code <li><p>}가 만들어지고 {@code margin: 1em}이 붙는다.
     * <p>
     * 같은 이유로 줄을 {@code -}로 시작하면 안 된다. 이어지는 설명이 아니라 중첩 목록이 된다.
     */
    @Bean
    OpenAPI accenturyOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Accentury App-Backend API")
                        .version("v0")
                        .description("""
                                경남 사투리 레벨테스트 프로토타입의 앱-백엔드 API다.

                                ## 호출 순서

                                1. `POST /v0/sessions` 로 익명 세션을 만들고 `sessionToken`을 받는다.
                                   토큰은 이 응답에서 딱 한 번만 노출되고 서버에는 해시만 남는다.

                                2. 우측 상단 **Authorize**에 그 `sessionToken`을 붙여 넣는다.

                                3. `GET /v0/tests/{testVersion}` 로 1번 응답의 `testVersion`에 해당하는 문항을 받는다.

                                4. `POST /v0/sessions/{sessionId}/voice-items/{itemId}/recording` 로 음성 문항
                                   하나를 업로드한다. 분석은 비동기라 즉시 202가 떨어진다.

                                ## 공통 오류 형식

                                어떤 엔드포인트에서 어떤 예외가 나든 응답 본문은 항상 `ErrorResponse` 한 가지다.
                                `retryable`이 같은 요청을 다시 보내도 되는지를 알려주고,
                                `correlationId`는 서버 로그에서 같은 요청을 찾는 키다.

                                오류 코드는 도메인별 네임스페이스로 나뉜다.
                                `SESSION_*` / `ITEM_*` / `AUDIO_*` / `ANALYSIS_*` / `RESULT_*` / `RATE_*`.

                                ## 아직 없는 것

                                분석 상태 폴링(KAN-24)과 최종 결과(KAN-25)는 미구현이다.
                                여기 보이는 엔드포인트가 현재 동작하는 전부다.
                                """))
                .components(new Components()
                        .addSecuritySchemes(SESSION_TOKEN, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("""
                                        `POST /v0/sessions` 응답의 `sessionToken` 값(`st_`로 시작)을 그대로 넣는다.
                                        `Bearer ` 접두사는 Swagger UI가 붙이므로 토큰만 입력한다.
                                        기본 유효 기간은 30분이고, 만료되면 401 `SESSION_EXPIRED`다.
                                        """)));
    }
}
