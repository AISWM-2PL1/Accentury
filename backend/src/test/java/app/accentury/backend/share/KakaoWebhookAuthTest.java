package app.accentury.backend.share;

import app.accentury.backend.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 키 검증의 기동 시 규칙 - 요청 시 판정은 {@link KakaoShareWebhookApiTest}가 API로 본다. */
class KakaoWebhookAuthTest {

    @Test
    void 빈_키는_기동_실패다() {
        // @ConditionalOnProperty는 값이 있는지만 보므로 빈 값도 통과시킨다 - 조건과 검증이 겹쳐야 한다.
        assertThrows(IllegalStateException.class, () -> KakaoWebhookAuth.requireKey(""));
        assertThrows(IllegalStateException.class, () -> KakaoWebhookAuth.requireKey("   "));
        assertThrows(IllegalStateException.class, () -> KakaoWebhookAuth.requireKey(null));
    }

    @Test
    void 자리_표시_값으로_뜨면_모든_요청을_거부한다() {
        // Terraform이 심는 값이라 레포를 읽은 누구나 안다 - 통과시키면 위조 콜백이 카운터를 올린다 (Claude 리뷰).
        KakaoWebhookAuth auth = new KakaoWebhookAuth(KakaoWebhookAuth.PLACEHOLDER);

        assertFalse(auth.configured());
        assertThrows(ApiException.class, () -> auth.authorize("KakaoAK " + KakaoWebhookAuth.PLACEHOLDER));
    }

    @Test
    void 설정된_키의_앞뒤_공백은_무시한다() {
        // 콘솔에서 복사해 put-parameter로 넣은 값에 공백이 섞여도 모든 콜백이 401이 되면 안 된다 - 로그는
        // 키를 가리므로 그 원인은 보이지 않는다 (Claude 리뷰).
        KakaoWebhookAuth auth = new KakaoWebhookAuth("  0123456789abcdef0123456789abcdef \n");

        assertTrue(auth.configured());
        assertDoesNotThrow(() -> auth.authorize("KakaoAK 0123456789abcdef0123456789abcdef"));
        assertThrows(ApiException.class, () -> auth.authorize("KakaoAK 0123456789abcdef0123456789abcdee"));
    }
}
