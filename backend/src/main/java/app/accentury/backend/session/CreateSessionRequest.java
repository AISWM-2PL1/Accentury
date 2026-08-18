package app.accentury.backend.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * {@code POST /v0/sessions} 요청 (API 명세서 §3.1).
 * <p>
 * 모든 필드가 선택이고 바디 자체를 생략해도 세션이 만들어진다 -
 * 권역 파라미터 없음(경남 고정), 개인 식별 정보 없음.
 *
 * @param campaignToken 공유 유입 계측 코드 (개인 식별 불가). 저장되므로 안전한 문자만 허용한다.
 * @param client        플랫폼과 앱 버전 - 익명 집계용
 */
public record CreateSessionRequest(
        @Nullable
        @Pattern(regexp = "[A-Za-z0-9._-]{1,64}", message = "영숫자와 ._- 조합 최대 64자만 허용됩니다")
        String campaignToken,

        @Nullable @Valid Client client
) {

    /**
     * 클라이언트 정보 - 익명 집계에만 쓴다.
     *
     * @param platform   플랫폼
     * @param appVersion 앱 버전 (예: {@code 0.1.0})
     */
    public record Client(
            @Nullable Platform platform,

            @Nullable
            @Size(max = 32, message = "최대 32자입니다")
            String appVersion
    ) {
    }

    public enum Platform { IOS, ANDROID, WEB }
}
