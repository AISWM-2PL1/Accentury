package app.accentury.backend.common;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 운영자 전용 API(§6)의 공유 시크릿 인증 (KAN-106, KAN-26).
 * <p>
 * 세션 토큰이 아니라 §6이 규정한 <b>별도 관리자 인증</b>이다. 관리자 엔드포인트가 여럿이므로
 * ({@code GET /admin/v0/analytics}, {@code PUT /admin/v0/active-version},
 * {@code GET /admin/v0/test-definitions}) 검사 자체를 한 곳에 둔다 - 컨트롤러마다 복사하면
 * 하나가 뒤처졌을 때 그 경로만 조용히 열린다.
 * <p>
 * <b>토큰({@code accentury.admin.token})을 설정해야만 이 빈이 생긴다.</b> 관리자 컨트롤러들도
 * 같은 조건을 달고 있어서, 설정을 빼먹으면 인증이 느슨해지는 것이 아니라 <b>경로 자체가 생기지
 * 않는다</b> (404). 신뢰 프록시 목록(§2.5, KAN-28)과 같은 "안전한 기본값" 계열이다.
 * <p>
 * 함정 하나: 토큰 값을 문자열 {@code "false"}로 두면 {@code @ConditionalOnProperty}가 비활성으로
 * 읽어 조용히 404가 된다 (빈 값은 기동 실패로 잡히는 것과 다르다, Fable 리뷰 P3). 토큰은
 * 무작위 시크릿이라 실제로 겹칠 일은 없지만, 404가 나면 이것부터 확인한다.
 */
@Component
@ConditionalOnProperty(prefix = "accentury.admin", name = "token")
public class AdminAuth {

    /** 관리자 토큰을 싣는 헤더 - 세 관리자 엔드포인트가 같은 이름을 쓴다. */
    public static final String TOKEN_HEADER = "X-Admin-Token";

    /**
     * 토큰 최소 길이 - 무작위로 발급한 시크릿이면 자연히 넘는 값이고, 사람이 지어낸 값
     * ({@code admin123})은 여기서 걸린다 (2026-08-17 확정).
     * <p>
     * 이 검사가 없으면 약한 토큰에 무제한 추측이 열린다 - 관리자 엔드포인트에는 요청 제한이
     * 없기 때문이다(미인증 요청은 DB에 닿지 않아 부하 경로는 아니지만, 시도 횟수는 무제한이다).
     * 제한을 거는 대신 <b>약한 토큰이 배포되지 못하게</b> 막는 쪽을 골랐다 - 근본이고,
     * 운영자의 정상 폴링을 막을 위험도 없다. 길이만 보는 것은 엔트로피의 하한일 뿐이라
     * 값 자체는 무작위로 발급해야 한다.
     */
    private static final int MIN_TOKEN_LENGTH = 32;

    private final byte[] expectedToken;

    AdminAuth(AccenturyProperties properties) {
        this.expectedToken = requireStrongToken(properties.admin().token());
    }

    /**
     * 기동 시 토큰 검증 - 통과한 값의 UTF-8 바이트를 돌려준다.
     * <p>
     * {@code @ConditionalOnProperty}는 값이 <b>있는지</b>만 보므로 빈 값도 통과시킨다.
     * 조건과 검증이 겹쳐야 하는 자리다.
     *
     * @throws IllegalStateException 비었거나 {@link #MIN_TOKEN_LENGTH}보다 짧을 때
     */
    static byte[] requireStrongToken(@Nullable String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("accentury.admin.token이 비어 있다");
        }
        if (token.length() < MIN_TOKEN_LENGTH) {
            // 길이만 알리고 값은 알리지 않는다 - 기동 로그도 로그다.
            throw new IllegalStateException("accentury.admin.token이 너무 짧다: "
                    + token.length() + "자 (최소 " + MIN_TOKEN_LENGTH + "자, 무작위 발급 권장)");
        }
        return token.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 헤더의 토큰이 설정값과 같은지 확인한다. 누락과 불일치를 구분하지 않는다 - 세션
     * 토큰(SESSION_EXPIRED)과 같은 이유다.
     * <p>
     * 길이 차이로도 새지 않게 상수 시간 비교를 쓴다 ({@link MessageDigest#isEqual}) -
     * 토큰을 한 글자씩 맞춰 보는 공격을 막는 관례다.
     *
     * @throws ApiException 401 {@code ADMIN_UNAUTHORIZED}
     */
    public void authorize(@Nullable String token) {
        if (token == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedToken)) {
            throw new ApiException(ErrorCode.ADMIN_UNAUTHORIZED);
        }
    }
}
