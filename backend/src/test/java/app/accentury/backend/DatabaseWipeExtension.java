package app.accentury.backend;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;
import java.util.List;

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
 * 카탈로그에서 읽는다 - 새 테이블이 생겨도 이 클래스는 몰라도 된다. {@code flyway_schema_history}만
 * 남긴다 (지우면 다음 컨텍스트의 Flyway가 이미 있는 테이블 위에 V1을 다시 적용하려다 죽는다).
 */
public final class DatabaseWipeExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        ApplicationContext app = SpringExtension.getApplicationContext(context);
        JdbcTemplate jdbc = new JdbcTemplate(app.getBean(DataSource.class));
        List<String> tables = jdbc.queryForList(
                "select tablename from pg_tables where schemaname = current_schema()"
                        + " and tablename <> 'flyway_schema_history'",
                String.class);
        if (!tables.isEmpty()) {
            // cascade: FK(on delete cascade)로 얽힌 하위 테이블 순서를 몰라도 된다.
            jdbc.execute("truncate table " + String.join(", ", tables) + " cascade");
        }
    }
}
