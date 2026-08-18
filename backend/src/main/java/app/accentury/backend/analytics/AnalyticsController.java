package app.accentury.backend.analytics;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * {@code GET /admin/v0/analytics} - 익명 집계 조회 (KAN-106 AC, API 명세서 §6).
 * <p>
 * 운영자 전용이라 세션 토큰이 아닌 <b>별도 관리자 인증</b>을 쓴다 (§6의 규약). 경로도 정의
 * 발행(KAN-26)과 같은 {@code /admin/v0} 아래다 - {@code /internal/v0}는 BE에서 AI 서버로
 * 나가는 기계 간 호출의 접두사라(§4) 방향이 반대다.
 * <p>
 * <b>토큰({@code accentury.analytics.admin-token})을 설정해야만 생긴다</b> (2026-08-17 확정).
 * 미설정이 기본값이라 설정을 빼먹어도 열려 있는 경로가 만들어지지 않는다 - 신뢰 프록시
 * 목록(§2.5, KAN-28)과 같은 "안전한 기본값" 계열이다. 미설정 상태에서 이 경로는 다른 없는
 * 경로와 똑같은 404다.
 * <p>
 * 응답에 개인 식별 정보가 없는 집계값뿐이라도 공개 데이터는 아니다 - 등급 분포는 출시 게이트
 * 판단(KAN-20)에 쓰는 내부 지표다. 클라이언트 CORS allowlist는 {@code /v0/**}에만 걸려 있어
 * (CorsConfig) 브라우저에서 교차 출처로 읽을 수 없다.
 * <p>
 * 함정 하나: 토큰 값을 문자열 {@code "false"}로 두면 {@code @ConditionalOnProperty}가 비활성으로
 * 읽어 조용히 404가 된다 (빈 값은 기동 실패로 잡히는 것과 다르다, Fable 리뷰 P3). 토큰은
 * 무작위 시크릿이라 실제로 겹칠 일은 없지만, 404가 나면 이것부터 확인한다.
 */
@RestController
@RequestMapping("/admin/v0/analytics")
@ConditionalOnProperty(prefix = "accentury.analytics", name = "admin-token")
class AnalyticsController {

    static final String TOKEN_HEADER = "X-Admin-Token";

    /**
     * 토큰 최소 길이 - 무작위로 발급한 시크릿이면 자연히 넘는 값이고, 사람이 지어낸 값
     * ({@code admin123})은 여기서 걸린다 (2026-08-17 확정).
     * <p>
     * 이 검사가 없으면 약한 토큰에 무제한 추측이 열린다 - 이 엔드포인트에는 요청 제한이
     * 없기 때문이다(미인증 요청은 DB에 닿지 않아 부하 경로는 아니지만, 시도 횟수는 무제한이다).
     * 제한을 거는 대신 <b>약한 토큰이 배포되지 못하게</b> 막는 쪽을 골랐다 - 근본이고,
     * 운영자의 정상 폴링을 막을 위험도 없다. 길이만 보는 것은 엔트로피의 하한일 뿐이라
     * 값 자체는 무작위로 발급해야 한다.
     */
    private static final int MIN_TOKEN_LENGTH = 32;

    private final AnalyticsQueryService service;
    private final byte[] expectedToken;

    AnalyticsController(AnalyticsQueryService service, AccenturyProperties properties) {
        this.service = service;
        this.expectedToken = requireStrongToken(properties.analytics().adminToken());
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
            throw new IllegalStateException("accentury.analytics.admin-token이 비어 있다");
        }
        if (token.length() < MIN_TOKEN_LENGTH) {
            // 길이만 알리고 값은 알리지 않는다 - 기동 로그도 로그다.
            throw new IllegalStateException("accentury.analytics.admin-token이 너무 짧다: "
                    + token.length() + "자 (최소 " + MIN_TOKEN_LENGTH + "자, 무작위 발급 권장)");
        }
        return token.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 일자와 버전별 카운터, 그리고 기간 합산을 반환한다.
     * <p>
     * 200 조회 성공 / 400 형식 오류나 역전되거나 너무 긴 기간({@code VALIDATION_FAILED}) /
     * 401 토큰 누락이나 불일치({@code ADMIN_UNAUTHORIZED}).
     * <p>
     * 일자를 {@code String}으로 받는 것은 인증이 첫 관문이어야 해서다 (2026-08-17 리뷰) -
     * {@code LocalDate}로 받으면 바인딩이 {@link #authorize}보다 먼저 실행되어, 토큰 없는
     * 요청이 날짜 형식 오류에 401 대신 400을 받아 미인증 호출자에게 입력 검증 피드백이 샌다.
     *
     * @param from  시작 일자 (포함, {@code yyyy-MM-dd}). 생략하면 {@code to}와 같은 날
     * @param to    종료 일자 (포함). 생략하면 오늘
     * @param token {@code X-Admin-Token} 헤더 - 설정된 값과 같아야 한다.
     */
    @GetMapping
    ResponseEntity<AnalyticsResponse> query(
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestHeader(value = TOKEN_HEADER, required = false) @Nullable String token) {
        authorize(token);
        return ResponseEntity.ok()
                // 내부 지표라도 중간 캐시에 남기지 않는다. - 오류 응답과 같은 방침 (§2.3)
                .cacheControl(CacheControl.noStore())
                .body(service.query(parseDate("from", from), parseDate("to", to)));
    }

    /** {@code yyyy-MM-dd} 파싱 - 빈 값은 생략과 같고, 형식 오류는 파라미터 이름을 담아 400이다. */
    private static @Nullable LocalDate parseDate(String name, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    name + " 일자 형식이 올바르지 않습니다 (yyyy-MM-dd).");
        }
    }

    /**
     * 길이 차이로도 새지 않게 상수 시간 비교를 쓴다. ({@link MessageDigest#isEqual}) -
     * 토큰을 한 글자씩 맞춰 보는 공격을 막는 관례다.
     */
    private void authorize(@Nullable String token) {
        if (token == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedToken)) {
            throw new ApiException(ErrorCode.ADMIN_UNAUTHORIZED);
        }
    }
}
