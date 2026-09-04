package app.accentury.backend;

import app.accentury.backend.analysis.AnalysisJob;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobStatus;
import app.accentury.backend.result.TestResult;
import app.accentury.backend.result.TestResultRepository;
import app.accentury.backend.session.TestSession;
import app.accentury.backend.session.TestSessionRepository;
import app.accentury.backend.vocab.VocabAnswer;
import app.accentury.backend.vocab.VocabAnswerRepository;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway baseline 스키마 자체의 명세 (KAN-123).
 * <p>
 * "빈 DB에 Flyway만으로 전체 스키마가 생성되고 validate로 기동한다"(AC)는 이 테스트가
 * 아니라 컨텍스트 기동 자체가 증명한다 - 모든 통합 테스트의 DB가 그렇게 만들어진다.
 * 여기서는 그 위에 얹힌 것들을 고정한다: 마이그레이션 이력, CASCADE 규칙, 그리고
 * validate가 안 보는 범위의 드리프트 가드.
 */
class SchemaBaselineTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TestSessionRepository sessionRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private VocabAnswerRepository vocabAnswerRepository;

    @Autowired
    private TestResultRepository testResultRepository;


    @Test
    void baseline_마이그레이션이_적용되어_있다() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success",
                Integer.class);
        assertEquals(1, applied, "V1 baseline이 성공 상태로 기록되어야 한다");
    }

    /** 발행 입력 DB 이관 (KAN-26) - 정의와 활성 포인터, 감사 이력이 마이그레이션으로 들어온다. */
    @Test
    void 테스트_정의_발행_마이그레이션이_적용되어_있다() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '2' and success",
                Integer.class);
        assertEquals(1, applied, "V2 발행 마이그레이션이 성공 상태로 기록되어야 한다");
    }

    /**
     * 세트 다중화 (KAN-182) - 세션과 결과의 voice_set은 기본값 1이다. 세트를 모르는 옛 바이너리가
     * 컬럼 없이 INSERT해도 세트 1 세션이 되어야 한다 (롤백 호환).
     */
    @Test
    void 세트_컬럼은_기본값_1로_들어온다() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '5' and success",
                Integer.class);
        assertEquals(1, applied, "V5 세트 마이그레이션이 성공 상태로 기록되어야 한다");

        // PgJDBC는 Instant를 바인딩하지 못한다 - OffsetDateTime으로 넘긴다.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("insert into test_session (id, token_hash, test_version, score_version, traffic,"
                        + " created_at, expires_at) values ('s_legacy', ?, 'gn-2026.08.1', 'sv-0.3', 'REAL', ?, ?)",
                "0".repeat(64), now, now.plusMinutes(30));
        assertEquals(1, sessionRepository.findById("s_legacy").orElseThrow().voiceSet(),
                "voice_set 없이 넣은 세션은 세트 1이어야 한다");
    }

    /**
     * 활성 포인터는 한 행뿐이다 (KAN-26). 권역이 하나뿐인 MVP에서 두 번째 행이 생기면 어느
     * 쪽이 활성인지 알 수 없어지므로, 애플리케이션이 아니라 DB가 막게 해 뒀다.
     * <p>
     * {@code @Transactional}은 제약이 언젠가 사라졌을 때를 위한 안전벨트다 - 그러면 이 문장이
     * 실제로 커밋되는데, {@code active_test_version}은 클래스 사이 초기화 대상이 아니라서
     * ({@code DatabaseWipeExtension.KEEP}) 오염된 포인터가 그대로 남아 이후 모든 컨텍스트가
     * 기동에 실패한다. 제약 하나가 무너진 것이 원인과 멀리 떨어진 전체 실패로 번지지 않게 한다.
     */
    @Test
    @Transactional
    void 활성_버전_포인터는_CURRENT_한_행만_허용한다() {
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("insert into active_test_version"
                        + " (id, test_version, previous_test_version, activated_at)"
                        + " values ('OTHER', ?, null, now())", activeTestVersion()),
                "check 제약이 두 번째 행을 거부해야 한다");
    }

    /**
     * 발행되지 않은 버전을 활성으로 지정할 수 없다 - 애플리케이션 검사(404) 아래의 DB 방어선이다.
     * {@code @Transactional}의 이유는 위 테스트와 같다.
     */
    @Test
    @Transactional
    void 발행되지_않은_버전은_활성으로_지정할_수_없다() {
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update active_test_version set test_version = 'gn-9999.99.9'"
                        + " where id = 'CURRENT'"),
                "FK 위반이 거부되어야 한다");
    }

    /** AC: 세션 행 삭제 시 하위 행이 CASCADE로 함께 삭제된다 (KAN-107 소비). */
    @Test
    void 세션_행을_지우면_하위_행이_DB_수준에서_함께_지워진다() {
        TestSession session = TestSessions.ensure(sessionRepository, "s_cascade");
        Instant now = Instant.now();
        analysisJobRepository.save(new AnalysisJob("a_cascade", session.id(), "v1", 1,
                "cascade-key", AnalysisJobStatus.PROCESSING, now));
        vocabAnswerRepository.save(new VocabAnswer("va_cascade", session.id(), "w1", "w1a",
                true, "cascade-key", now));
        testResultRepository.save(new TestResult("r_cascade", session.id(),
                "gn-2026.08.1", "sv-0.3", 1, 80, 80, 80, "HONORARY", "명예주민", 4, 5,
                now, now.plus(24, ChronoUnit.HOURS)));

        // 애플리케이션의 벌크 삭제(KAN-107)를 일부러 우회하고 세션 행만 지운다 -
        // 코드가 하위 삭제를 빠뜨려도 DB가 막아 주는 마지막 안전망이 검증 대상이다.
        jdbc.update("delete from test_session where id = ?", session.id());

        assertTrue(analysisJobRepository.findById("a_cascade").isEmpty(), "분석 작업이 함께 지워져야 한다");
        assertTrue(vocabAnswerRepository.findById("va_cascade").isEmpty(), "어휘 답안이 함께 지워져야 한다");
        assertTrue(testResultRepository.findById("r_cascade").isEmpty(), "결과가 함께 지워져야 한다");
    }

    /** 부모 없는 하위 행은 DB가 거부한다 - CASCADE의 전제인 FK가 실제로 걸려 있다. */
    @Test
    void 존재하지_않는_세션을_참조하는_하위_행은_거부된다() {
        assertFalse(sessionRepository.existsById("s_missing"), "전제: 부모가 없어야 한다");
        assertThrows(DataIntegrityViolationException.class,
                () -> analysisJobRepository.save(new AnalysisJob("a_orphan", "s_missing", "v1", 1,
                        "orphan-key", AnalysisJobStatus.PROCESSING, Instant.now())),
                "FK 위반이 거부되어야 한다");
    }

    /**
     * daily_counter는 FK가 없어야 한다 (CASCADE 제외 근거, KAN-106) - 세션을 참조하는
     * 컬럼 자체가 없고, 익명 통계는 세션 폐기와 무관하게 영속하는 것이 설계다.
     * 미래에 누가 세션 FK를 더하면 이 테스트가 그 결정을 다시 묻는다.
     */
    @Test
    void 집계_카운터에는_FK가_없다() {
        List<String> foreignKeys = jdbc.queryForList(
                "select conname from pg_constraint"
                        + " where contype = 'f' and conrelid = 'daily_counter'::regclass",
                String.class);
        assertTrue(foreignKeys.isEmpty(), "daily_counter에 FK가 생겼다 - 영속 통계 설계와 충돌: " + foreignKeys);
    }

    /**
     * validate가 안 보는 범위의 드리프트 가드 (Opus 리뷰 P2) - Hibernate validate는 컬럼
     * 존재와 타입 코드만 대조하고, 길이와 nullable은 지나친다. 엔티티의 {@code @Column}만
     * 바꾸고 마이그레이션을 빠뜨리면 운영에서 INSERT가 값 잘림(22001)으로 터질 때까지
     * 드러나지 않는다 - 여기서 JPA 메타모델과 카탈로그를 전수 대조해 먼저 잡는다.
     * 스키마에만 있는 컬럼(반대 방향 드리프트)도 함께 잡는다.
     */
    @Test
    void 엔티티의_컬럼_길이와_널_제약이_스키마와_어긋나지_않는다() {
        record ColumnFacts(Integer maxLength, boolean nullable) {
        }
        Map<String, ColumnFacts> schema = new HashMap<>();
        jdbc.query("select table_name, column_name, character_maximum_length, is_nullable"
                        + " from information_schema.columns where table_schema = current_schema()",
                rs -> {
                    schema.put(rs.getString(1) + "." + rs.getString(2),
                            new ColumnFacts((Integer) rs.getObject(3), "YES".equals(rs.getString(4))));
                });

        List<String> mismatches = new ArrayList<>();
        Set<String> entityTables = new HashSet<>();
        Set<String> entityColumns = new HashSet<>();
        for (EntityType<?> entity : entityManager.getMetamodel().getEntities()) {
            Class<?> type = entity.getJavaType();
            String table = type.getAnnotation(Table.class).name();
            entityTables.add(table);
            for (Field field : type.getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column == null) {
                    continue;    // static 상수, @Transient
                }
                String name = column.name().isEmpty() ? camelToSnake(field.getName()) : column.name();
                String key = table + "." + name;
                entityColumns.add(key);
                ColumnFacts facts = schema.get(key);
                if (facts == null) {
                    mismatches.add(key + ": 엔티티에는 있는데 스키마에 없다");
                    continue;
                }
                boolean expectNullable = column.nullable() && !field.isAnnotationPresent(Id.class);
                if (facts.nullable() != expectNullable) {
                    mismatches.add(key + ": nullable 스키마=" + facts.nullable() + " 엔티티=" + expectNullable);
                }
                if (facts.maxLength() != null && facts.maxLength() != column.length()) {
                    mismatches.add(key + ": 길이 스키마=" + facts.maxLength() + " 엔티티=" + column.length());
                }
            }
        }
        schema.keySet().stream()
                .filter(key -> entityTables.contains(key.substring(0, key.indexOf('.'))))
                .filter(key -> !entityColumns.contains(key))
                .forEach(key -> mismatches.add(key + ": 스키마에는 있는데 엔티티에 없다"));

        assertTrue(mismatches.isEmpty(),
                "엔티티와 스키마가 어긋났다 - 새 마이그레이션이 빠졌는지 확인하라:\n" + String.join("\n", mismatches));
    }

    /**
     * 인덱스와 유니크 제약도 validate 밖이다 - 이름 집합째 고정한다. 유니크가 사라지면
     * 멱등/중복 방지(§5.2)가, 인덱스가 사라지면 CASCADE와 상태 조회의 실행 계획이
     * 조용히 무너지므로, 스키마를 바꾸는 마이그레이션은 이 기대 집합도 함께 고친다.
     */
    @Test
    void 인덱스_집합이_baseline과_일치한다() {
        Map<String, Set<String>> expected = Map.of(
                "test_session", Set.of("pk_test_session", "ux_test_session_token_hash"),
                // ix_analysis_job_processing은 KAN-167의 부분 인덱스(V4) - 혼잡 판정의 PROCESSING count가 탄다.
                "analysis_job", Set.of("pk_analysis_job", "ux_analysis_job_idempotency",
                        "ix_analysis_job_session_item", "ix_analysis_job_processing"),
                "vocab_answer", Set.of("pk_vocab_answer", "ux_vocab_answer_session_item"),
                "test_result", Set.of("pk_test_result", "ux_test_result_session"),
                "daily_counter", Set.of("pk_daily_counter", "ux_daily_counter_key"),
                // KAN-26 발행 이관
                "test_definition", Set.of("pk_test_definition"),
                "active_test_version", Set.of("pk_active_test_version"),
                "active_version_audit", Set.of("pk_active_version_audit",
                        "ix_active_version_audit_recorded_at"));

        Map<String, Set<String>> actual = new TreeMap<>();
        jdbc.query("select tablename, indexname from pg_indexes"
                        + " where schemaname = current_schema() and tablename <> 'flyway_schema_history'",
                rs -> {
                    actual.computeIfAbsent(rs.getString(1), t -> new HashSet<>()).add(rs.getString(2));
                });

        assertEquals(new TreeMap<>(expected), actual);
    }

    /** JPA 기본 네이밍(camelCase -> snake_case)과 같은 규칙 - 이름을 명시하지 않은 컬럼용 */
    private static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
