package app.accentury.backend.common;

import app.accentury.backend.DatabaseWipeExtension;
import app.accentury.backend.PostgresTestcontainer;
import app.accentury.backend.analysis.AnalysisDispatcher;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 배포 프로파일이 갖춰진 값으로 실제로 뜨는지 (KAN-129).
 * <p>
 * {@code DeployProfileStartupTest}가 "빠지면 죽는다"를 보고, 여기는 "다 있으면 배포 경로 그대로
 * 뜬다"를 본다. DB는 pgjdbc가 아니라 <b>AWS Advanced JDBC Wrapper + awsSecretsManager 플러그인</b>을
 * 그대로 거친다 - 사용자 이름과 비밀번호를 설정에 주지 않고, 플러그인이 Secrets Manager에서 받은
 * 시크릿 JSON으로 Flyway와 JPA까지 통과해야 한다. Secrets Manager만 가짜다: JDK 내장 HTTP 서버가
 * GetSecretValue 응답을 돌려주고, 플러그인의 {@code secretsManagerEndpoint}로 그리 보낸다.
 * <p>
 * 이렇게까지 하는 이유는 이 경로가 <b>배포에서만</b> 켜지기 때문이다. 플러그인을 끄고 wrapper만
 * 검증하면 시크릿 JSON 파싱(Jackson 3, Java 17+ 멀티릴리스 클래스)과 자격 증명 주입이 staging
 * 재구축 때까지 한 번도 실행되지 않는다 (Codex 리뷰 P1). 실제 AWS 호출(IAM, 회전)은 재구축에서
 * 실증한다.
 * <p>
 * {@code PostgresTestcontainer}를 import하지 않는 것은 의도다 - {@code @ServiceConnection}이 URL과
 * 드라이버를 pgjdbc로 덮어써 wrapper 경로가 검증되지 않는다. 대신 같은 컨테이너의 접속 정보로
 * wrapper URL을 직접 조립한다.
 */
@SpringBootTest(properties = {
        "accentury.analysis.ai-base-url=http://ai.test:8000",
        // AI 내부 호출 시크릿 (KAN-36) - 배포 프로파일 필수값.
        "accentury.analysis.ai-token=deploy-profile-boot-test-internal-token-0123456789",
        // CloudWatch 내보내기(KAN-36)는 배포 프로파일이 켜지만 테스트에는 올릴 곳도 자격 증명도 없다.
        "management.cloudwatch.metrics.export.enabled=false",
        // SSM ACCENTURY_TRUSTEDPROXIES와 같은 한 줄 형태 - staging VPC CIDR.
        "accentury.trusted-proxies=10.1.0.0/16",
        "accentury.admin.token=deploy-profile-boot-test-token-0123456789",
        "accentury.result.web-test-url=https://staging.accentury.app/t?c=kko_share",
        // 테스트 DB는 RDS가 아니라 순정 PostgreSQL이다.
        "spring.datasource.hikari.data-source-properties.wrapperDialect=pg"})
@ActiveProfiles({"test", DeploymentConfigGuard.PROFILE})
@ExtendWith(DatabaseWipeExtension.class)
class DeployProfileBootTest {

    /** SSM SPRING_DATASOURCE_URL이 싣는 것과 같은 모양의 ARN - 리전은 플러그인이 여기서 읽는다. */
    private static final String SECRET_ARN =
            "arn:aws:secretsmanager:ap-northeast-2:000000000000:secret:accentury-test-AbCdEf";

    private static final AtomicInteger SECRET_FETCHES = new AtomicInteger();

    private static HttpServer fakeSecretsManager;

    @DynamicPropertySource
    static void wrapperDatasource(DynamicPropertyRegistry registry) throws IOException {
        if (fakeSecretsManager == null) {
            fakeSecretsManager = startFakeSecretsManager();
            // @AfterAll에서 세우지 않는다 - 스프링이 이 컨텍스트(와 그 커넥션 풀)를 JVM이 끝날 때까지
            // 캐시하므로, 뒤에 도는 클래스가 같은 컨텍스트를 받았을 때 시크릿 재조회가 죽은 주소를
            // 만나면 안 된다. 루프백 임시 포트 하나라 JVM 종료 훅으로 닫는 것으로 충분하다 (PR 리뷰).
            HttpServer server = fakeSecretsManager;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        }
        // SDK 기본 자격 증명 체인 - 배포에서는 인스턴스 프로파일(IMDSv2)이 이 자리다. 같은 이유로
        // 되돌리지 않는다: 캐시된 컨텍스트가 살아 있는 동안 플러그인이 시크릿을 다시 받을 수 있고,
        // 이 JVM의 다른 테스트는 AWS SDK를 쓰지 않는다. 이미 있는 값(개발자 셸)은 건드리지 않는다.
        System.setProperty("aws.accessKeyId", System.getProperty("aws.accessKeyId", "test"));
        System.setProperty("aws.secretAccessKey", System.getProperty("aws.secretAccessKey", "test"));

        registry.add("spring.datasource.url", () -> "jdbc:aws-wrapper:postgresql://"
                + PostgresTestcontainer.host() + ":" + PostgresTestcontainer.port()
                + "/" + PostgresTestcontainer.database()
                + "?secretsManagerSecretId=" + SECRET_ARN);
        registry.add("spring.datasource.hikari.data-source-properties.secretsManagerEndpoint",
                () -> "http://127.0.0.1:" + fakeSecretsManager.getAddress().getPort());
    }

    /** GetSecretValue 하나만 아는 Secrets Manager - RDS 관리형 시크릿과 같은 JSON 형태를 돌려준다. */
    private static HttpServer startFakeSecretsManager() throws IOException {
        String secretJson = "{\"username\":\"" + PostgresTestcontainer.username()
                + "\",\"password\":\"" + PostgresTestcontainer.password() + "\"}";
        String body = "{\"ARN\":\"" + SECRET_ARN + "\",\"Name\":\"accentury-test\",\"VersionId\":\"v1\","
                + "\"VersionStages\":[\"AWSCURRENT\"],"
                + "\"SecretString\":" + quote(secretJson) + "}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            SECRET_FETCHES.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/x-amz-json-1.1");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ClientIps clientIps;

    @Autowired
    private AnalysisDispatcher analysisDispatcher;

    @Autowired
    private AdminAuth adminAuth;

    @Test
    void 자격_증명_없이_뜨고_플러그인이_Secrets_Manager에서_받은_시크릿으로_DB에_붙는다() {
        HikariDataSource hikari = assertInstanceOf(HikariDataSource.class, dataSource);
        assertEquals("software.amazon.jdbc.Driver", hikari.getDriverClassName());
        assertEquals("software.amazon.jdbc.util.HikariCPSQLException", hikari.getExceptionOverrideClassName());
        // 설정에 사용자 이름과 비밀번호가 없다 - application-deploy.yml이 비워 둔 그대로다.
        assertNull(hikari.getUsername());
        assertNull(hikari.getPassword());
        // Flyway와 validate 기동은 컨텍스트가 뜬 것으로 증명됐다 - 질의 한 번으로 풀 자체도 확인한다.
        assertEquals(Integer.valueOf(1), new JdbcTemplate(dataSource).queryForObject("select 1", Integer.class));
        // 시크릿은 캐시(secretsManagerExpirationTimeSec)되므로 연결 수만큼 부르지는 않는다.
        assertTrue(SECRET_FETCHES.get() >= 1, "가짜 Secrets Manager 호출 " + SECRET_FETCHES.get() + "회");
    }

    @Test
    void CloudFront_VPC_오리진과_ALB_두_겹을_VPC_대역_하나로_걷어내_사용자_IP에_닿는다() {
        // 배포 경로: 사용자 -> CloudFront -> VPC 오리진 ENI(10.1.0.77) -> internal ALB(10.1.10.23) -> EC2.
        // CloudFront는 뷰어 IP(203.0.113.9)를, ALB는 ENI IP를 XFF 오른쪽에 덧붙인다. 맨 왼쪽은
        // 사용자가 위조해 보낸 값이다. VPC CIDR 하나로 오른쪽 두 홉이 걷히고 뷰어 IP가 남아야 한다.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.10.23");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.9, 10.1.0.77");

        assertEquals("203.0.113.9", clientIps.resolve(request));
    }

    @Test
    void ai_base_url이_있으면_실제_전달_디스패처가_조립된다() {
        // 미설정이면 NoopAnalysisDispatcher다 - 배포에서 그것이 뜨면 업로드가 전부 타임아웃으로 끝난다.
        assertEquals("HttpAnalysisDispatcher", analysisDispatcher.getClass().getSimpleName());
    }

    @Test
    void 관리자_토큰이_있으면_관리자_인증이_등록된다() {
        // 토큰이 없으면 이 빈 자체가 없어 관리자 API가 404다 (KAN-138 스모크, §6).
        adminAuth.authorize("deploy-profile-boot-test-token-0123456789");
    }
}
