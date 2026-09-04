package app.accentury.backend;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

/**
 * 테스트 클래스가 시작할 때 모든 애플리케이션 테이블을 비운다 (KAN-123).
 * <p>
 * H2 시절에도 JVM 공유 인메모리 DB({@code jdbc:h2:mem:accentury})라 같은 컨텍스트를 쓰는
 * 클래스끼리는 데이터가 새고 있었고, 새 컨텍스트가 뜰 때의 {@code create-drop}만 이따금
 * 판을 갈아엎었다. 이제 스키마는 Flyway가 한 번 만들고 모든 컨텍스트가 같은 컨테이너 DB를
 * 공유하므로, 격리를 클래스 단위로 오히려 강화해 고정한다 - 안 하면 한 테스트 클래스가
 * 남긴 행(예: 고정 일자의 daily_counter, 만료 세션)이 다른 클래스의 집계와 보존 검증을
 * 오염시킨다.
 * <p>
 * 같은 클래스 안의 테스트끼리는 전과 같이 데이터를 공유한다. 테이블 목록은 하드코딩하지 않고
 * 카탈로그에서 읽는다 - 새 테이블이 생겨도 이 클래스는 몰라도 된다. 예외는 {@link #KEEP}에
 * 적은 것들뿐이다.
 */
public final class DatabaseWipeExtension implements BeforeAllCallback {

    /**
     * 비우면 안 되는 테이블.
     * <ul>
     *   <li>{@code flyway_schema_history} - 지우면 다음 컨텍스트의 Flyway가 이미 있는 테이블
     *       위에 V1을 다시 적용하려다 죽는다.</li>
     *   <li>{@code test_definition}, {@code active_test_version} - 마이그레이션이 넣는 콘텐츠이지
     *       테스트가 만든 데이터가 아니다 (KAN-26). 지우면 <b>이미 떠 있는</b> 컨텍스트가 활성
     *       버전을 잃어, 그 뒤의 모든 세션 생성과 활성 전환이 무너진다 - 레지스트리는 기동 시
     *       한 번만 읽으므로 다시 채워 넣을 기회도 없다.</li>
     * </ul>
     * 활성 포인터가 남는다는 것은 <b>활성 버전을 바꾼 테스트가 직접 되돌려야 한다</b>는 뜻이다 -
     * 안 되돌리면 다음 클래스가 바뀐 활성 버전을 물려받는다 (같은 컨텍스트를 재사용하므로
     * 메모리 상태도 함께 남는다). 전환을 시험하는 테스트가 {@code @AfterEach}에서 되돌린다.
     * 반면 {@code active_version_audit}는 테스트가 만든 이력이라 비운다.
     */
    private static final Set<String> KEEP =
            Set.of("flyway_schema_history", "test_definition", "active_test_version");

    @Override
    public void beforeAll(ExtensionContext context) {
        ApplicationContext app = SpringExtension.getApplicationContext(context);
        JdbcTemplate jdbc = new JdbcTemplate(app.getBean(DataSource.class));
        List<String> tables = jdbc.queryForList(
                        "select tablename from pg_tables where schemaname = current_schema()",
                        String.class)
                .stream()
                .filter(table -> !KEEP.contains(table))
                .toList();
        if (!tables.isEmpty()) {
            // cascade: FK(on delete cascade)로 얽힌 하위 테이블 순서를 몰라도 된다.
            jdbc.execute("truncate table " + String.join(", ", tables) + " cascade");
        }
    }
}
