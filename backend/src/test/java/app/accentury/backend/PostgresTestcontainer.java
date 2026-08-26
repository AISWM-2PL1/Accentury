package app.accentury.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 전체가 공유하는 PostgreSQL 컨테이너 (KAN-123 확정 - Testcontainers 전환).
 * <p>
 * H2 호환 모드를 폐기한 이유: 마이그레이션 SQL이 실제로 실행될 곳은 PostgreSQL(RDS)인데,
 * H2의 PostgreSQL 흉내는 완전하지 않아 PG 전용 문법이 배포 순간까지 숨는다. 테스트가
 * 실제 PostgreSQL에서 돌면 Flyway 적용과 validate 기동이 모든 실행에서 검증된다 (티켓 AC).
 * <p>
 * 컨테이너는 static으로 JVM당 딱 하나만 띄운다 - 스프링이 컨텍스트를 여러 벌 캐시해도
 * ({@code @TestPropertySource} 조합마다 하나) 전부 같은 컨테이너에 붙는다. {@code @Bean}이
 * 컨텍스트마다 새 컨테이너를 만들면 기동 비용이 컨텍스트 수만큼 늘어난다. 종료는 Testcontainers의
 * Ryuk이 JVM 종료 후 정리한다. 컨텍스트 간 데이터 격리는 {@link DatabaseWipeExtension}이 맡는다.
 * <p>
 * 이미지 버전은 로컬 개발 DB(docker-compose.yml)와 같은 postgres:16으로 고정한다 -
 * 테스트, 로컬 개발, 배포(RDS)가 같은 메이저 버전을 봐야 한다 (KAN-122).
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

    // max_connections 상향: 캐시된 스프링 컨텍스트마다 커넥션 풀이 하나씩 이 컨테이너에
    // 붙는다 - 기본 100이면 컨텍스트가 쌓이다가 후반 테스트가 "sorry, too many clients
    // already"로 죽는다. 풀 크기도 테스트 프로파일에서 줄여(application-test.yml, 컨텍스트당
    // 5) 상한 = 컨텍스트 캐시 기본 32 x 5 = 160 < 500이다. fsync=off는 컨테이너 기본
    // command인데 withCommand가 추가가 아니라 통째 교체라 함께 다시 넘겨야 한다 (Opus 리뷰
    // P2 - 빠지면 버려도 되는 테스트 DB가 매 커밋 실제 fsync를 하며 느려진다).
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16"))
                    .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=500");

    static {
        POSTGRES.start();
    }

    // destroyMethod = "": 지정을 비우지 않으면 스프링이 소멸 메서드를 추론해
    // AutoCloseable.close()(= stop())를 부른다 - 컨텍스트 하나가 닫힐 때 JVM 공용 컨테이너가
    // 통째로 죽고, 같은 컨테이너를 보던 다른 캐시 컨텍스트가 "Connection refused"로 무너진다.
    // 지금은 @DirtiesContext가 없고 컨텍스트가 캐시 상한(32) 미만이라 잠복 상태지만,
    // 둘 중 하나만 바뀌어도 터진다. 컨테이너 정리는 위 주석대로 Ryuk에 맡긴다.
    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }

    // 아래 접속 정보는 @ServiceConnection을 거치지 않고 다른 드라이버로 붙는 테스트용이다 -
    // 배포 프로파일 기동 검증(KAN-129)이 AWS Advanced JDBC Wrapper URL을 직접 조립한다.
    // @ServiceConnection은 URL과 드라이버를 pgjdbc로 덮어써서 wrapper 경로를 검증할 수 없다.

    public static String host() {
        return POSTGRES.getHost();
    }

    public static int port() {
        return POSTGRES.getMappedPort(5432);
    }

    public static String database() {
        return POSTGRES.getDatabaseName();
    }

    public static String username() {
        return POSTGRES.getUsername();
    }

    public static String password() {
        return POSTGRES.getPassword();
    }
}
