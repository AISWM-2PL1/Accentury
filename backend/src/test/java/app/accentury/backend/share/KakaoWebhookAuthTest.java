package app.accentury.backend.share;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 키 검증의 기동 시 규칙 - 요청 시 판정은 {@link KakaoShareWebhookApiTest}가 API로 본다. */
class KakaoWebhookAuthTest {

    @Test
    void 빈_키는_기동_실패다() {
        // @ConditionalOnProperty는 값이 있는지만 보므로 빈 값도 통과시킨다 - 조건과 검증이 겹쳐야 한다.
        assertThrows(IllegalStateException.class, () -> KakaoWebhookAuth.requireKey(""));
        assertThrows(IllegalStateException.class, () -> KakaoWebhookAuth.requireKey("   "));
        assertThrows(IllegalStateException.class, () -> KakaoWebhookAuth.requireKey(null));
    }
}
