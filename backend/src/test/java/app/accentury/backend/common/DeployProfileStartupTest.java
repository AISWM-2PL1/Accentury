package app.accentury.backend.common;

import app.accentury.backend.BackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.NestedExceptionUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 배포 프로파일에서 필수값을 빼면 기동이 실패한다 (KAN-129 AC).
 * <p>
 * 실패의 <b>원인</b>이 설정 누락이어야 한다는 것이 핵심이다. DataSource가 먼저 떠서 접속 거부로
 * 죽으면 배포 로그에는 "connection refused"만 남고, 정작 빠진 환경 변수는 아무도 알려 주지 않는다.
 * 그래서 DB 주소를 해석되지 않는 호스트로 주고도, 예외 메시지가 접속 오류가 아니라 누락 목록인지
 * 확인한다. DB가 필요 없으므로 Testcontainers도 띄우지 않는다.
 */
class DeployProfileStartupTest {

    @Test
    void 필수값이_빠지면_DB에_닿기_전에_누락_목록을_남기고_기동이_실패한다() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(BackendApplication.class)
                .profiles(DeploymentConfigGuard.PROFILE)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off");

        // 명령행 인자로 준다 - builder.properties()는 기본값(최하위)이라 application-deploy.yml이
        // 비워 둔 값에 덮인다. 배포에서 환경 변수가 yml을 이기는 것과 같은 우선순위 관계다.
        // 빠뜨릴 다섯은 빈 값으로 명시한다 - 명령행 인자는 OS 환경 변수보다 우선하므로, 개발자 셸에
        // ACCENTURY_ADMIN_TOKEN 같은 값이 있어도 "누락 5건"이 흔들리지 않는다 (PR 리뷰).
        Throwable thrown = assertThrows(Throwable.class, () -> app.run(
                // DB 주소와 자격 증명은 있다 - 나머지 다섯이 없다 (web-test-url은 deploy yml이 비운다 -
                // main의 prod 도메인 기본값이 staging에 새지 않게).
                "--spring.datasource.url=jdbc:postgresql://deploy-guard.invalid:5432/accentury",
                "--spring.datasource.username=accentury",
                "--spring.datasource.password=unused",
                "--accentury.analysis.ai-base-url=",
                "--accentury.analysis.ai-token=",
                "--accentury.trusted-proxies=",
                "--accentury.admin.token=",
                "--accentury.result.web-test-url=",
                // CloudWatch 내보내기(KAN-36)는 배포 프로파일이 켜지만 여기서는 올릴 곳이 없다.
                "--management.cloudwatch.metrics.export.enabled=false"));
        String message = String.valueOf(NestedExceptionUtils.getMostSpecificCause(thrown).getMessage());

        assertTrue(message.contains("필수 설정 누락 5건"), message);
        assertTrue(message.contains("ACCENTURY_ANALYSIS_AIBASEURL"), message);
        assertTrue(message.contains("ACCENTURY_ANALYSIS_AITOKEN"), message);
        assertTrue(message.contains("ACCENTURY_TRUSTEDPROXIES"), message);
        assertTrue(message.contains("ACCENTURY_ADMIN_TOKEN"), message);
        assertTrue(message.contains("ACCENTURY_RESULT_WEBTESTURL"), message);
        // 시크릿 값이 메시지에 실리지 않는다 - 기동 로그도 로그다.
        assertFalse(message.contains("unused"), message);
    }
}
