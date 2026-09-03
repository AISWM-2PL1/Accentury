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
 * @param voiceSet      응시할 음성 문항 세트 번호 (1부터, KAN-182). 생략 시 1. 세트 선택은
 *                      클라이언트가 한다 (2026-09-01 확정) - 웹은 세트 1 고정, 앱은 전체 세트
 *                      선택 UI. 활성 정의의 세트 수 밖이면 400 {@code VALIDATION_FAILED}이고,
 *                      그 검증은 {@link SessionService}가 활성 정의 스냅샷 하나에서 한다.
 *                      정수가 아닌 값은 프레임워크가 파싱 단계에서 400으로 끊는다.
 */
public record CreateSessionRequest(
        @Nullable
        @Pattern(regexp = "[A-Za-z0-9._-]{1,64}", message = "영숫자와 ._- 조합 최대 64자만 허용됩니다")
        String campaignToken,

        @Nullable @Valid Client client,

        @Nullable Integer voiceSet
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
