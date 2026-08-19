package app.accentury.backend.testdefinition;

import app.accentury.backend.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 활성 전환의 트랜잭션 경계 (KAN-26) - <b>진짜 트랜잭션으로</b> 검증한다.
 * <p>
 * {@link ActiveVersionServiceTest}는 트랜잭션을 무동작으로 대체해 전환 규칙만 본다. 그래서
 * "메모리 반영은 커밋 뒤"와 "포인터 UPDATE와 감사 INSERT는 한 트랜잭션"이라는 두 불변식은
 * 거기서 증명되지 않는다 - 그 자리를 여기가 메운다. 이 명세가 없으면
 * {@code registry.applyActivation()}을 트랜잭션 안으로 옮기거나 전파를 되돌려도 전 테스트가
 * 그대로 통과한다.
 * <p>
 * 실패는 감사 저장에서 주입한다. 포인터를 옮긴 <b>뒤</b>에 일어나는 유일한 쓰기라, 두 문장이
 * 정말 한 트랜잭션인지가 여기서 갈린다.
 */
class ActiveVersionTransactionTest extends IntegrationTest {

    private static final String BASELINE = "gn-2026.08.1";

    private static final String OLDER = "gn-2026.07.0";

    /** 주입한 실패의 표식 - 다른 원인의 IllegalStateException과 구별하는 데 쓴다. */
    private static final String BROKEN_AUDIT_MESSAGE = "감사 기록 실패 주입";

    @Autowired
    private TestDefinitionRegistry registry;

    @Autowired
    private ActiveTestVersionRepository activeVersions;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ActiveVersionService activeVersionService;

    @AfterEach
    void restoreBaseline() {
        // 활성 포인터는 클래스 사이 초기화 대상이 아니다 (DatabaseWipeExtension.KEEP) -
        // 바꾼 테스트가 직접 되돌린다.
        activeVersionService.activate(BASELINE, "테스트 정리");
        // 되돌리기가 실제로 통했는지 확인한다. DB와 메모리가 갈라진 채 나가면 이 컨텍스트를
        // 공유하는 뒤 클래스들이 원인에서 먼 곳에서 무너진다 - 게다가 갈라진 상태에서는
        // 위의 activate가 멱등 분기로 빠져 메모리를 고치지 못하므로, 여기서 세워야 한다.
        assertEquals(storedActiveVersion(), activeTestVersion(),
                "DB와 레지스트리의 활성 버전이 갈라진 채 클래스를 벗어나면 안 된다");
    }

    @Test
    void 감사_기록이_실패하면_포인터도_메모리도_그대로다() {
        String before = activeTestVersion();
        // 전제: 되돌릴 필요가 없도록, 실패하는 전환만 시도한다.
        assertEquals(before, storedActiveVersion());

        ActiveVersionService failing = new ActiveVersionService(
                registry, activeVersions, new BrokenAudits(), transactionTemplate);

        // 예외 타입만 보면 "포인터 행이 사라졌다"는 다른 IllegalStateException과 구별되지
        // 않는다 - 주입한 실패가 맞는지 메시지로 못 박는다.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> failing.activate(OLDER, "실패 주입"));
        assertEquals(BROKEN_AUDIT_MESSAGE, thrown.getMessage());

        // DB: 감사 INSERT가 터졌으니 같은 트랜잭션의 포인터 UPDATE도 함께 사라져야 한다.
        assertEquals(before, storedActiveVersion(), "포인터 UPDATE가 감사 INSERT와 같은 트랜잭션이어야 한다");
        // 메모리: 커밋되지 않은 전환이 레지스트리에 남으면, 그 뒤의 세션이 DB에 없는 활성
        // 버전을 고정한다 (§5.4). 커밋 뒤에만 반영해야 하는 이유가 이것이다.
        assertEquals(before, activeTestVersion(), "커밋되지 않은 전환이 메모리에 남으면 안 된다");
    }

    /**
     * 전환은 <b>자기가 커밋 지점</b>이다 (REQUIRES_NEW) - 바깥 트랜잭션이 롤백해도 살아남는다.
     * <p>
     * 바깥에 합류하면 {@code execute()}가 돌아온 시점에도 커밋 전이라, 뒤이은 메모리 반영이
     * "커밋 뒤"라는 불변식을 깬다. 지금은 컨트롤러가 트랜잭션 없이 부르므로 겉으로 드러나지
     * 않지만, 이 서비스를 다른 트랜잭션 안에서 부르는 호출자가 생기는 순간 조용히 어긋난다.
     * 그 계약을 여기서 못 박는다.
     */
    @Test
    void 전환은_바깥_트랜잭션이_롤백해도_커밋된다() {
        transactionTemplate.execute(tx -> {
            tx.setRollbackOnly();
            return activeVersionService.activate(OLDER, "바깥 롤백 시험");
        });

        assertEquals(OLDER, storedActiveVersion(), "전환은 바깥 트랜잭션에 딸려 롤백되면 안 된다");
        assertEquals(OLDER, activeTestVersion(), "커밋됐으므로 메모리도 따라와야 한다");
    }

    private String storedActiveVersion() {
        return activeVersions.findById(ActiveTestVersion.CURRENT)
                .orElseThrow()
                .testVersion();
    }

    /** 포인터를 옮긴 뒤의 쓰기에서 터지는 저장소 - 두 문장이 한 트랜잭션인지 가른다. */
    private static final class BrokenAudits implements ActiveVersionAuditRepository {

        @Override
        public ActiveVersionAudit save(ActiveVersionAudit audit) {
            throw new IllegalStateException(BROKEN_AUDIT_MESSAGE);
        }

        @Override
        public List<ActiveVersionAudit> findAllByOrderByRecordedAtDescIdDesc(Limit limit) {
            return List.of();
        }

        @Override
        public long count() {
            return 0;
        }
    }
}
