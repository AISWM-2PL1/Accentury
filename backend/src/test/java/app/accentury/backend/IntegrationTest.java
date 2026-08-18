package app.accentury.backend;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 스프링 컨텍스트를 띄우는 테스트의 공통 베이스.
 * <p>
 * {@code test} 프로파일을 여기서 한 번만 켠다 - 프로파일이 빠지면 main의
 * {@code application.yml}이 그대로 적용돼 로컬 개발용 PostgreSQL에 붙고,
 * 개발 DB를 건드릴 수 있다. 각 테스트가 {@code @ActiveProfiles}를 기억하는
 * 대신 상속으로 강제한다.
 * <p>
 * DB는 {@link PostgresTestcontainer}의 공유 컨테이너다 (KAN-123) - 스키마는 main과
 * 똑같이 Flyway V1이 만들고 Hibernate validate가 대조하므로, 컨텍스트가 뜨는 것
 * 자체가 "빈 DB에 Flyway만으로 스키마 생성 + validate 기동"(티켓 AC)의 검증이다.
 * 클래스 사이의 데이터 격리는 {@link DatabaseWipeExtension}이 맡는다.
 * <p>
 * {@code application-test.yml}은 main 설정을 대체하지 않고 덮어쓰므로,
 * 테스트는 실제 배포 설정(세션 TTL, 점수 버전, multipart 등)을 그대로 검증한다.
 * <p>
 * MockMvc가 필요한 API 테스트는 여기에 {@code @AutoConfigureMockMvc}를 더한다.
 * 슬라이스 테스트({@code @WebMvcTest})는 전체 컨텍스트를 띄우지 않으므로
 * 이 클래스를 상속하지 않고 {@code @ActiveProfiles}만 직접 붙인다. 상속이 안 되는
 * 전체 컨텍스트 테스트(웹 환경 지정 등)는 {@code @Import(PostgresTestcontainer.class)}와
 * {@code @ExtendWith(DatabaseWipeExtension.class)}를 둘 다 직접 붙여야 한다 - 앞이
 * 빠지면 main datasource로 흘러가 기동이 실패하고, 뒤가 빠지면 앞 클래스의 잔여 행을
 * 그대로 물려받는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestcontainer.class)
@ExtendWith(DatabaseWipeExtension.class)
public abstract class IntegrationTest {
}
