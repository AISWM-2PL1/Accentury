package app.accentury.backend.share;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>
 * <b>Terraform이 심는 자리 표시 값({@link #PLACEHOLDER})은 키가 아니다.</b> 그 값으로 뜬 backend는 모든
 * 웹훅을 401로 거부한다 - 레포를 읽은 누구나 아는 값이라 통과시키면 위조 콜백이 카운터를 올린다
 * (Claude 리뷰). 값이 같아야 하므로 {@code SsmEnvironmentBindingTest}가 main.tf의 리터럴과 대조한다.
 */
@Component
@ConditionalOnProperty(prefix = "accentury.share", name = "kakao-admin-key")
public class KakaoWebhookAuth {

    /** 카카오가 {@code Authorization} 헤더에 쓰는 스킴 - 뒤에 공백 하나와 Admin 키가 온다. */
    public static final String SCHEME = "KakaoAK";

    /**
     * Terraform({@code infra/modules/config/main.tf})이 SSM 파라미터를 만들 때 넣는 자리 표시 값. 운영자가
     * 콘솔의 Admin 키로 덮어쓰기 전까지 backend는 이 값으로 뜨고, 그동안은 모든 웹훅이 401이다.
     */
    public static final String PLACEHOLDER = "unset-put-parameter-after-apply";

    private static final Logger log = LoggerFactory.getLogger(KakaoWebhookAuth.class);

    /** 대조할 키의 UTF-8 바이트. 자리 표시 값으로 떴으면 null이고, 그때는 아무 요청도 통과하지 않는다. */
    private final byte @Nullable [] expectedKey;

    @Autowired
    KakaoWebhookAuth(AccenturyProperties properties) {
        this(properties.share().kakaoAdminKey());
    }

    /** 테스트가 키 문자열만으로 만드는 자리. */
    KakaoWebhookAuth(@Nullable String key) {
        String stripped = requireKey(key);
        if (PLACEHOLDER.equals(stripped)) {
            log.warn("카카오 웹훅 검증 키가 자리 표시 값이다 - Admin 키를 넣기 전까지 모든 웹훅을 거부한다 "
                    + "(infra README \"카카오 공유 웹훅 검증 키\" 절)");
            this.expectedKey = null;
        } else {
            this.expectedKey = stripped.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 앞뒤 공백을 걷어낸 키. 콘솔에서 복사해 {@code put-parameter}로 넣은 값에 공백이 섞이면 모든 콜백이
     * 401이 되는데 로그는 키를 가리므로 원인이 보이지 않는다 (Claude 리뷰) - 헤더 쪽도 같은 기준으로 자른다.
     *
     * @throws IllegalStateException 비어 있을 때 - 조건부 등록은 빈 값도 통과시키므로 여기서 세운다.
     */
    static String requireKey(@Nullable String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("accentury.share.kakao-admin-key가 비어 있다.");
        }
        return key.strip();
    }

    /** 실제 키로 떴는가 - 자리 표시 값이면 false다. */
    public boolean configured() {
        return expectedKey != null;
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
        if (expectedKey == null || authorization == null) {
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
