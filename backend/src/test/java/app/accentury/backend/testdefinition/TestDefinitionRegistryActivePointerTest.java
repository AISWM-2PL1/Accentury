package app.accentury.backend.testdefinition;

import app.accentury.backend.scoring.ScorePolicyRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 활성 정의 읽기의 명세 (KAN-167) - 레지스트리는 활성 버전을 들고 있지 않고 포인터 행을 따른다.
 * <p>
 * 실제 DB를 거치는 전파는 {@code AdminActiveVersionApiTest}가 본다. 여기서는 포인터 저장소를
 * 바꿔치기해 "부를 때마다 읽는다"와 "가리키는 버전이 이 프로세스에 없으면 조용히 옛 버전을
 * 주지 않는다"를 고정한다.
 */
class TestDefinitionRegistryActivePointerTest {

    private static final String OLDER = "gn-2026.07.0";
    private static final String NEWER = "gn-2026.08.1";

    @Test
    void 활성_정의는_부를_때마다_포인터_행을_읽는다() {
        MutablePointer pointer = new MutablePointer(OLDER);
        TestDefinitionRegistry registry = registry(pointer);
        assertEquals(OLDER, registry.active().definition().testVersion());

        // 다른 인스턴스의 전환 - 이 레지스트리를 거치지 않고 포인터만 바뀐다.
        pointer.testVersion = NEWER;

        assertEquals(NEWER, registry.active().definition().testVersion());
        assertTrue(pointer.reads >= 2, "활성 정의를 메모리에 들고 있으면 안 된다");
    }

    @Test
    void 활성_정의는_testVersion과_scoreVersion을_한_스냅샷으로_준다() {
        TestDefinitionRegistry registry = registry(new MutablePointer(NEWER));

        TestDefinitionRegistry.PublishedDefinition active = registry.active();

        assertEquals(NEWER, active.definition().testVersion());
        assertEquals(active.definition().scoreVersion(), active.voiceSet(1).response().scoreVersion());
    }

    /**
     * 포인터가 이 프로세스에 발행되지 않은 버전을 가리키면 세운다 - 새 정의의 마이그레이션이
     * 적용된 뒤 재기동하지 않은 옛 태스크가 그 버전의 전환을 만난 상황이다. 옛 버전으로 조용히
     * 세션을 만들면 전환이 반영된 줄 아는 운영자와 실제 응시가 갈라진다.
     */
    @Test
    void 포인터가_발행되지_않은_버전을_가리키면_세운다() {
        MutablePointer pointer = new MutablePointer(NEWER);
        TestDefinitionRegistry registry = registry(pointer);

        pointer.testVersion = "gn-2099.01.0";

        IllegalStateException thrown = assertThrows(IllegalStateException.class, registry::active);
        assertTrue(thrown.getMessage().contains("gn-2099.01.0"));
    }

    @Test
    void 포인터_행이_사라지면_세운다() {
        MutablePointer pointer = new MutablePointer(NEWER);
        TestDefinitionRegistry registry = registry(pointer);

        pointer.testVersion = null;

        assertThrows(IllegalStateException.class, registry::active);
    }

    private static TestDefinitionRegistry registry(ActiveTestVersionRepository pointer) {
        List<StoredTestDefinition> rows = List.of(row(OLDER), row(NEWER));
        return new TestDefinitionRegistry(JsonMapper.builder().build(), () -> rows, pointer,
                new ScorePolicyRegistry(JsonMapper.builder().build()));
    }

    private static StoredTestDefinition row(String testVersion) {
        return new StoredTestDefinition(testVersion, "GYEONGNAM", "sv-0.3",
                DefinitionFixtures.body(testVersion, "GYEONGNAM"), Instant.EPOCH);
    }

    /** 테스트가 직접 옮기는 포인터 - null이면 행이 없는 상태다. */
    private static final class MutablePointer implements ActiveTestVersionRepository {

        private @Nullable String testVersion;
        private int reads;

        MutablePointer(String testVersion) {
            this.testVersion = testVersion;
        }

        @Override
        public Optional<ActiveTestVersion> findById(String id) {
            reads++;
            return Optional.ofNullable(testVersion)
                    .map(version -> new ActiveTestVersion(version, null, Instant.EPOCH));
        }

        @Override
        public Optional<ActiveTestVersion> lockById(String id) {
            return findById(id);
        }
    }
}
