package app.accentury.backend.share;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 카카오 웹훅 요청의 검증 (KAN-164 AC "서명이 틀린 요청은 거부되고 카운터가 증가하지 않는다").
 * <p>
 * 카카오 문서(kakaotalk-share/callback)의 검증 수단은 서명이 아니라 헤더 하나다 -
 * {@code Authorization: KakaoAK {앱 Admin 키}}. Admin 키는 카카오디벨로퍼스 콘솔의 앱 키 중 서버
 * 전용 값이고, 이 값을 아는 쪽은 카카오와 우리뿐이다. 그래서 검증은 그 값의 일치 확인이 전부다.
 * <p>
 * <b>키({@code accentury.share.kakao-admin-key})를 설정해야만 이 빈이 생긴다.</b> 웹훅 컨트롤러도
 * 같은 조건이라, 설정을 빼먹으면 인증이 느슨해지는 것이 아니라 경로 자체가 없다(404) - 관리자
 * API({@code AdminAuth})와 같은 "안전한 기본값" 계열이다. 값을 문자열 {@code "false"}로 두면
 * 조건부 등록이 비활성으로 읽어 조용히 404가 되는 함정도 같다.
 * <p>
 * 길이 하한은 두지 않는다 - 키 형식은 카카오가 정하고 우리가 발급하지 않는다. 대신 빈 값은 기동
 * 실패다 ({@code @ConditionalOnProperty}는 값이 있는지만 본다).
 */
@Component
@ConditionalOnProperty(prefix = "accentury.share", name = "kakao-admin-key")
public class KakaoWebhookAuth {

    /** 카카오가 {@code Authorization} 헤더에 쓰는 스킴 - 뒤에 공백 하나와 Admin 키가 온다. */
    public static final String SCHEME = "KakaoAK";

    private final byte[] expectedKey;

    KakaoWebhookAuth(AccenturyProperties properties) {
        this.expectedKey = requireKey(properties.share().kakaoAdminKey());
    }

    /** @throws IllegalStateException 비어 있을 때 - 조건부 등록은 빈 값도 통과시키므로 여기서 세운다. */
    static byte[] requireKey(@Nullable String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("accentury.share.kakao-admin-key가 비어 있다");
        }
        return key.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * {@code Authorization} 헤더가 {@code KakaoAK {설정된 키}}인지 확인한다. 누락, 다른 스킴, 불일치를
     * 구분하지 않는다 - 호출자가 카카오가 아니면 무엇이 틀렸는지 알려 줄 이유가 없다.
     * <p>
     * 키 비교는 상수 시간이다 ({@link MessageDigest#isEqual}) - 길이 차이로도 새지 않게 한다.
     *
     * @throws ApiException 401 {@code SHARE_WEBHOOK_UNAUTHORIZED}
     */
    public void authorize(@Nullable String authorization) {
        if (authorization == null) {
            throw new ApiException(ErrorCode.SHARE_WEBHOOK_UNAUTHORIZED);
        }
        String value = authorization.strip();
        int schemeLength = SCHEME.length();
        boolean schemeMatches = value.length() > schemeLength + 1
                && value.regionMatches(true, 0, SCHEME, 0, schemeLength)
                && value.charAt(schemeLength) == ' ';
        if (!schemeMatches) {
            throw new ApiException(ErrorCode.SHARE_WEBHOOK_UNAUTHORIZED);
        }
        byte[] presented = value.substring(schemeLength + 1).strip().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(presented, expectedKey)) {
            throw new ApiException(ErrorCode.SHARE_WEBHOOK_UNAUTHORIZED);
        }
    }
}
