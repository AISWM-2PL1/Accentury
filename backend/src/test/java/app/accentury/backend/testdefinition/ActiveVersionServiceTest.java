package app.accentury.backend.testdefinition;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import app.accentury.backend.scoring.ScorePolicyRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 활성 전환 규칙의 단위 명세 (KAN-26, 명세서 §6).
 * <p>
 * API를 통과하는 경로는 {@code AdminActiveVersionApiTest}가 실제 DB로 검증한다. 여기서는
 * <b>그 경로로는 만들 수 없는 상태</b>를 조립해 규칙 자체를 고정한다 - 대표적인 것이 경북
 * 정의다. 발행 검증이 경북 정의를 아예 싣지 않아서(§6, 2026-08-19 확정 - 저장 자체를 차단)
 * 정상 배포에서는 활성화 요청이 404로 먼저 끝나고, 활성화 쪽 가드가 실제로 동작하는지는
 * 여기서만 볼 수 있다.
 */
class ActiveVersionServiceTest {

    /** AC - 경북 정의는 MVP에서 활성화할 수 없다. */
    @Test
    void 경북_정의는_활성화할_수_없다() {
        // 발행 검증이 풀리는 날 마지막 방어선으로 남으라고 둔 검사다 - 그날 이 테스트가
        // "그래도 활성화는 막힌다"를 증명한다.
        ActiveVersionService service = service(registryWith("gb-2026.08.1", "GYEONGBUK"), "gn-2026.08.1");

        ApiException rejected = assertThrows(ApiException.class,
                () -> service.activate("gb-2026.08.1", null));
        assertEquals(ErrorCode.ADMIN_DIALECT_NOT_ALLOWED, rejected.code());
    }

    @Test
    void 발행되지_않은_버전은_404다() {
        ActiveVersionService service = service(registryWith(null, null), "gn-2026.08.1");

        ApiException rejected = assertThrows(ApiException.class,
                () -> service.activate("gn-9999.99.9", null));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, rejected.code());
    }

    @Test
    void 되돌아갈_이전_버전이_없으면_409다() {
        ActiveVersionService service = service(registryWith(null, null), "gn-2026.08.1");

        ApiException rejected = assertThrows(ApiException.class, () -> service.rollback(null));
        assertEquals(ErrorCode.ADMIN_ROLLBACK_UNAVAILABLE, rejected.code());
    }

    /**
     * 검증 실패는 잠금을 잡기 전에 끝난다 - 거절될 요청이 포인터 행을 붙들면, 정상 전환이
     * 그 뒤에 줄을 선다. 잠금 조회가 한 번도 불리지 않은 것으로 확인한다.
     */
    @Test
    void 거절되는_요청은_포인터를_잠그지_않는다() {
        CountingActiveVersions pointers = new CountingActiveVersions("gn-2026.08.1", null);
        ActiveVersionService service = service(registryWith("gb-2026.08.1", "GYEONGBUK"), pointers);

        assertThrows(ApiException.class, () -> service.activate("gn-9999.99.9", null));
        assertThrows(ApiException.class, () -> service.activate("gb-2026.08.1", null));

        assertEquals(0, pointers.locks, "검증 전에 잠금을 잡으면 안 된다");
    }

    /**
     * 롤백은 목적지를 <b>잠금 안에서</b> 읽는다 - 잠금 밖의 조회를 한 번도 쓰지 않는 것으로
     * 확인한다.
     * <p>
     * 잠금 밖에서 먼저 읽으면 읽기와 쓰기가 원자적이지 않다. {@code active=A, previous=B}에서
     * 활성 전환(C)과 롤백이 겹치면 롤백은 낡은 B를 들고 들어와 방금 올라온 C를 두 걸음 뒤로
     * 되돌리고, 감사 이력에는 존재한 적 없는 "C 다음 B" 관계가 남는다. 잠금은 잡지만 목적지를
     * 그 전에 계산해 두면 잠금이 아무것도 지켜 주지 않는다는 것이 이 회귀의 핵심이라, 잠금
     * 횟수가 아니라 <b>잠금 없는 조회 횟수</b>를 센다.
     */
    @Test
    void 롤백은_잠금_밖에서_목적지를_읽지_않는다() {
        CountingActiveVersions pointers = new CountingActiveVersions("gn-2026.08.1", "gn-2026.07.0");
        ActiveVersionService service = service(registryWith("gn-2026.07.0", "GYEONGNAM"), pointers);

        assertEquals("gn-2026.07.0", service.rollback(null).activeVersion());

        assertEquals(1, pointers.locks, "전환은 잠금 아래에서 한 번만 일어난다");
        assertEquals(0, pointers.unlockedReads, "목적지를 잠금 밖에서 읽으면 안 된다");
    }

    /** 되돌아갈 자리가 없다는 판정도 잠금 안에서 한다 - 목적지 계산과 같은 자리여야 한다. */
    @Test
    void 롤백_불가_판정도_잠금_안에서_한다() {
        CountingActiveVersions pointers = new CountingActiveVersions("gn-2026.08.1", null);
        ActiveVersionService service = service(registryWith(null, null), pointers);

        assertThrows(ApiException.class, () -> service.rollback(null));
        assertEquals(0, pointers.unlockedReads, "판정을 잠금 밖에서 하면 목적지 계산과 갈라진다");
    }

    /** 사유는 감사 이력에 그대로 남는다 - 없이 보내도 전환은 성립한다. */
    @Test
    void 전환은_사유와_함께_이력을_남긴다() {
        CountingActiveVersions pointers = new CountingActiveVersions("gn-2026.08.1", null);
        RecordingAudits audits = new RecordingAudits();
        ActiveVersionService service = service(registryWith("gn-2026.07.0", "GYEONGNAM"), pointers, audits);

        ActiveVersionResponse response = service.activate("gn-2026.07.0", "시험 전환");

        assertEquals("gn-2026.07.0", response.activeVersion());
        assertEquals("gn-2026.08.1", response.previousVersion());
        assertTrue(response.changed());
        assertEquals(1, audits.saved.size());
        assertEquals(ActiveVersionAudit.Action.ACTIVATE, audits.saved.getFirst().action());
        assertEquals("gn-2026.08.1", audits.saved.getFirst().previousVersion());
        assertEquals("gn-2026.07.0", audits.saved.getFirst().newVersion());
        assertEquals("시험 전환", audits.saved.getFirst().reason());
    }

    // === 픽스처 ===

    private static ActiveVersionService service(TestDefinitionRegistry registry, String activeVersion) {
        return service(registry, new CountingActiveVersions(activeVersion, null));
    }

    private static ActiveVersionService service(TestDefinitionRegistry registry,
                                                ActiveTestVersionRepository pointers) {
        return service(registry, pointers, new RecordingAudits());
    }

    private static ActiveVersionService service(TestDefinitionRegistry registry,
                                                ActiveTestVersionRepository pointers,
                                                ActiveVersionAuditRepository audits) {
        return new ActiveVersionService(registry, pointers, audits, immediateTransactions());
    }

    /**
     * 트랜잭션 경계 없이 콜백을 그대로 실행하는 템플릿 - 이 명세의 관심은 전환 규칙이지
     * 커밋 시점이 아니다. 커밋 뒤 반영은 실제 DB를 쓰는 API 테스트가 검증한다.
     */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }

    /**
     * 활성 정의는 {@code gn-2026.08.1}(경남)이고, 인자로 준 버전이 하나 더 있는 레지스트리.
     * <p>
     * 경남이 아닌 추가 버전은 <b>발행 목록에 넣지 않고 조회만 열어 준다</b>. 발행 검증이 경북
     * 정의를 애초에 거부하므로(§6 - 저장 자체를 차단) 목록에 넣으면 레지스트리가 조립되지도
     * 않기 때문이다. 활성화 가드에 실제로 닿게 하려고 만든 <b>정상 경로로는 존재할 수 없는
     * 상태</b>이며, 그 가드가 살아 있는지는 이렇게만 확인할 수 있다.
     */
    private static TestDefinitionRegistry registryWith(@Nullable String extraVersion,
                                                       @Nullable String extraDialect) {
        boolean publishable = extraVersion != null && "GYEONGNAM".equals(extraDialect);
        List<StoredTestDefinition> rows = new ArrayList<>();
        rows.add(row("gn-2026.08.1", "GYEONGNAM"));
        if (publishable) {
            rows.add(row(extraVersion, extraDialect));
        }
        return new TestDefinitionRegistry(JsonMapper.builder().build(),
                () -> rows,
                new CountingActiveVersions("gn-2026.08.1", null),
                new ScorePolicyRegistry(JsonMapper.builder().build())) {
            @Override
            public PublishedDefinition get(String testVersion) {
                if (!publishable && testVersion.equals(extraVersion)) {
                    TestDefinition definition = new TestDefinition(extraVersion, "sv-0.3",
                            extraDialect, 240, List.of());
                    return new PublishedDefinition(definition, List.of(new VoiceSet(1, definition,
                            TestDefinitionResponse.from(definition, 1, 1), "\"stub\"")));
                }
                return super.get(testVersion);
            }
        };
    }

    private static StoredTestDefinition row(String testVersion, String dialect) {
        return new StoredTestDefinition(testVersion, dialect, "sv-0.3",
                DefinitionFixtures.body(testVersion, dialect), Instant.EPOCH);
    }

    /** 잠금 호출 횟수를 세는 포인터 저장소 */
    private static final class CountingActiveVersions implements ActiveTestVersionRepository {

        private final ActiveTestVersion pointer;
        private int locks;
        private int unlockedReads;

        CountingActiveVersions(String testVersion, @Nullable String previousVersion) {
            this.pointer = new ActiveTestVersion(testVersion, previousVersion, Instant.EPOCH);
        }

        @Override
        public Optional<ActiveTestVersion> findById(String id) {
            unlockedReads++;
            return Optional.of(pointer);
        }

        @Override
        public Optional<ActiveTestVersion> lockById(String id) {
            locks++;
            return Optional.of(pointer);
        }
    }

    /** 저장된 감사 행을 모아 두는 저장소 */
    private static final class RecordingAudits implements ActiveVersionAuditRepository {

        private final List<ActiveVersionAudit> saved = new ArrayList<>();

        @Override
        public ActiveVersionAudit save(ActiveVersionAudit audit) {
            saved.add(audit);
            return audit;
        }

        @Override
        public List<ActiveVersionAudit> findAllByOrderByRecordedAtDescIdDesc(Limit limit) {
            return List.copyOf(saved.reversed());
        }

        @Override
        public long count() {
            return saved.size();
        }
    }
}
