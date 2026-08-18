package app.accentury.backend.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 관리자 토큰의 기동 검증 (KAN-106, 2026-08-17 확정).
 * <p>
 * 이 엔드포인트에는 요청 제한이 없다 - 미인증 요청이 인증 단계에서 끊겨 DB에 닿지 않으므로
 * 부하 경로는 아니지만, 추측 횟수는 무제한이다. 그래서 <b>약한 토큰이 배포되지 못하게</b>
 * 기동 시점에 막는다. 제한을 거는 대신 이쪽을 고른 이유는 근본이고, 운영자의 정상 폴링을
 * 막을 위험이 없어서다.
 */
class AnalyticsControllerTest {

    @Test
    void 사람이_지어낸_짧은_토큰은_기동을_실패시킨다() {
        // admin123 같은 값이 배포되면 무제한 추측이 열린다 - 여기서 세우지 않으면
        // 운영에 존재할 수 있게 된다.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> AnalyticsController.requireStrongToken("admin123"));

        assertFalse(thrown.getMessage().contains("admin123"),
                "기동 로그도 로그다 - 값이 아니라 길이만 알려야 한다: " + thrown.getMessage());
    }

    @Test
    void 경계값_한_글자_아래도_거부한다() {
        assertThrows(IllegalStateException.class,
                () -> AnalyticsController.requireStrongToken("0123456789abcdef0123456789abcde"));
    }

    @Test
    void 빈_토큰도_거부한다() {
        // @ConditionalOnProperty는 값이 있는지만 보므로 빈 값을 통과시킨다 -
        // 조건과 검증이 겹쳐야 하는 자리다.
        assertThrows(IllegalStateException.class, () -> AnalyticsController.requireStrongToken("   "));
        assertThrows(IllegalStateException.class, () -> AnalyticsController.requireStrongToken(null));
    }

    @Test
    void 무작위_발급_길이의_토큰은_통과한다() {
        assertDoesNotThrow(() -> AnalyticsController.requireStrongToken("0123456789abcdef0123456789abcdef"));
    }
}
