package app.accentury.backend.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 배포 프로파일 필수 설정 검사의 판정 규칙 (KAN-129).
 * <p>
 * 실제 프로파일 배선(BFPP가 DataSource보다 먼저 도는지)은 {@code DeployProfileStartupTest}와
 * {@code DeployProfileBootTest}가 본다. 여기서는 "무엇을 빠졌다고 보는가"만 고정한다.
 */
class DeploymentConfigGuardTest {

    private static final String SECRETS_URL =
            "jdbc:aws-wrapper:postgresql://db.internal:5432/accentury?secretsManagerSecretId=arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:rds!db-x";

    @Test
    void 아무것도_없으면_필수값_다섯_가지를_SSM_이름과_함께_전부_나열한다() {
        // 한 번에 다 나와야 한다 - 하나씩 고치고 다시 띄우는 왕복이 배포마다 반복되면 안 된다.
        List<String> missing = DeploymentConfigGuard.missing(new MockEnvironment());

        assertEquals(5, missing.size(), missing.toString());
        assertTrue(missing.get(0).contains("SPRING_DATASOURCE_URL"));
        assertTrue(missing.get(1).contains("ACCENTURY_ANALYSIS_AIBASEURL"));
        assertTrue(missing.get(2).contains("ACCENTURY_TRUSTEDPROXIES"));
        assertTrue(missing.get(3).contains("ACCENTURY_ADMIN_TOKEN"));
        assertTrue(missing.get(4).contains("ACCENTURY_RESULT_WEBTESTURL"));
    }

    @Test
    void Secrets_Manager_URL이면_사용자_이름과_비밀번호가_비어_있어도_통과한다() {
        // 자격 증명은 플러그인이 시크릿에서 읽는다 - 비워 두는 것이 맞는 형태다.
        MockEnvironment env = complete().withProperty("spring.datasource.url", SECRETS_URL);

        assertEquals(List.of(), DeploymentConfigGuard.missing(env));
    }

    @Test
    void 일반_URL이면_사용자_이름과_비밀번호가_있어야_한다() {
        MockEnvironment env = complete()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.internal:5432/accentury");

        List<String> missing = DeploymentConfigGuard.missing(env);

        assertEquals(1, missing.size(), missing.toString());
        assertTrue(missing.get(0).contains("SPRING_DATASOURCE_PASSWORD"));

        env.setProperty("spring.datasource.username", "accentury");
        env.setProperty("spring.datasource.password", "pw");
        assertEquals(List.of(), DeploymentConfigGuard.missing(env));
    }

    @Test
    void 신뢰_프록시는_환경_변수의_쉼표_한_줄도_목록으로_받는다() {
        // SSM 값은 ACCENTURY_TRUSTEDPROXIES=10.1.0.0/16 한 줄이다 - yml의 배열과 같게 읽혀야 한다.
        MockEnvironment env = complete().withProperty("accentury.trusted-proxies", "10.1.0.0/16,fd00::/8");
        assertEquals(List.of(), DeploymentConfigGuard.missing(env));

        env.setProperty("accentury.trusted-proxies", "");
        assertEquals(1, DeploymentConfigGuard.missing(env).size());
    }

    @Test
    void 누락이_있으면_전부_적어_기동을_거부하고_없으면_통과한다() {
        MockEnvironment env = complete();
        assertDoesNotThrow(() -> DeploymentConfigGuard.requireComplete(env));

        env.setProperty("accentury.admin.token", "");
        env.setProperty("accentury.analysis.ai-base-url", " ");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DeploymentConfigGuard.requireComplete(env));

        assertTrue(e.getMessage().contains("2건"), e.getMessage());
        assertTrue(e.getMessage().contains("ACCENTURY_ANALYSIS_AIBASEURL"), e.getMessage());
        assertTrue(e.getMessage().contains("ACCENTURY_ADMIN_TOKEN"), e.getMessage());
    }

    /** 필수값이 전부 있는 환경 - 시나리오마다 하나씩 뺀다. */
    private static MockEnvironment complete() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", SECRETS_URL)
                .withProperty("accentury.analysis.ai-base-url", "http://ai:8000")
                .withProperty("accentury.trusted-proxies", "10.1.0.0/16")
                .withProperty("accentury.admin.token", "0123456789abcdef0123456789abcdef")
                .withProperty("accentury.result.web-test-url", "https://staging.accentury.app/t?c=kko_share");
    }
}
