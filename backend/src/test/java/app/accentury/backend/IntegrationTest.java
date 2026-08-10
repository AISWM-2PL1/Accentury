package app.accentury.backend;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 스프링 컨텍스트를 띄우는 테스트의 공통 베이스.
 * <p>
 * {@code test} 프로파일을 여기서 한 번만 켠다 - 프로파일이 빠지면 main의
 * {@code application.yml}이 그대로 적용돼 로컬 개발용 PostgreSQL에 붙고,
 * {@code ddl-auto: update}라 개발 DB를 건드릴 수 있다. 각 테스트가
 * {@code @ActiveProfiles}를 기억하는 대신 상속으로 강제한다.
 * <p>
 * {@code application-test.yml}은 main 설정을 대체하지 않고 덮어쓰므로,
 * 테스트는 실제 배포 설정(세션 TTL, 점수 버전, multipart 등)을 그대로 검증한다.
 * <p>
 * MockMvc가 필요한 API 테스트는 여기에 {@code @AutoConfigureMockMvc}를 더한다.
 * 슬라이스 테스트({@code @WebMvcTest})는 전체 컨텍스트를 띄우지 않으므로
 * 이 클래스를 상속하지 않고 {@code @ActiveProfiles}만 직접 붙인다.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTest {
}
