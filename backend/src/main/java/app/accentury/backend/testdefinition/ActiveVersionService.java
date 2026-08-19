package app.accentury.backend.testdefinition;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;

/**
 * 활성 테스트 버전의 전환과 롤백 (KAN-26, 명세서 §6).
 * <p>
 * 발행(정의를 DB에 넣는 것)은 마이그레이션이 하고, 이 클래스는 <b>그중 무엇을 활성으로 쓸지</b>만
 * 바꾼다. 둘을 나눈 것이 티켓의 2단계 롤아웃 원칙이다 - 새 정의를 먼저 배포하고 활성 전환은
 * 그다음이라, 배포 도중 신규 버전 세션이 아직 옛 코드인 인스턴스에 닿아 404를 받는 일이 없다.
 * <p>
 * <b>전환은 진행 중 세션에 영향을 주지 않는다</b> (AC). 세션은 생성 시점의 {@code testVersion}을
 * 자기 행에 들고 있고(§5.4), 발행본은 활성이 아니게 되어도 계속 조회된다 - 응시 중이던 사람은
 * 자기 문항으로 끝까지 간다.
 */
@Service
public class ActiveVersionService {

    private static final Logger log = LoggerFactory.getLogger(ActiveVersionService.class);

    private final TestDefinitionRegistry registry;
    private final ActiveTestVersionRepository activeVersions;
    private final ActiveVersionAuditRepository audits;
    private final TransactionTemplate transactionTemplate;

    public ActiveVersionService(TestDefinitionRegistry registry,
                                ActiveTestVersionRepository activeVersions,
                                ActiveVersionAuditRepository audits,
                                TransactionTemplate transactionTemplate) {
        this.registry = registry;
        this.activeVersions = activeVersions;
        this.audits = audits;
        // 전환은 자신이 커밋 지점이어야 한다 (REQUIRES_NEW) - 바깥 트랜잭션에 합류하면
        // execute()가 돌아온 시점에도 커밋 전이라, 뒤이은 메모리 반영이 "커밋 뒤"라는 불변식을
        // 깬다. 바깥이 롤백하면 DB는 옛 버전인데 레지스트리만 새 버전을 가리킨 채 남는다
        // (SessionService의 카운터 증가와 같은 이유). 공용 템플릿 빈의 전파는 바꾸지 않도록
        // 사본을 쓴다.
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 지정한 버전을 활성으로 올린다.
     * <p>
     * 이미 그 버전이 활성이면 <b>아무것도 바꾸지 않고 현재 상태를 그대로 돌려준다</b> - 감사
     * 행도 남기지 않는다. 운영자가 네트워크 오류로 같은 호출을 두 번 보냈을 때 이력이 "A에서
     * A로 바꿈"으로 더럽혀지지 않게 하려는 것이고, 그 결과 이 호출은 멱등이다. 되돌아갈 자리
     * ({@code previousTestVersion})도 그대로 보존된다 - 재시도가 롤백 목적지를 자기 자신으로
     * 덮어써 롤백을 잠가 버리면 안 된다.
     *
     * @param testVersion 활성으로 만들 발행본의 버전
     * @param reason      운영자가 남기는 사유 - 감사 이력에 그대로 저장된다. 선택 입력이다.
     * @throws ApiException 발행되지 않은 버전이면 404 {@code RESOURCE_NOT_FOUND},
     *                      MVP에서 활성화할 수 없는 방언이면 409 {@code ADMIN_DIALECT_NOT_ALLOWED}
     */
    public ActiveVersionResponse activate(String testVersion, @Nullable String reason) {
        // 대상이 요청에 실려 오므로 잠금 전에 검증할 수 있다 - 거절될 요청이 포인터 행을
        // 붙들면 정상 전환이 그 뒤에 줄을 선다.
        requireActivatable(testVersion);
        return apply(ActiveVersionAudit.Action.ACTIVATE, pointer -> testVersion, reason);
    }

    /**
     * 직전 활성 버전으로 되돌린다 (AC - 이전 활성 버전으로 롤백할 수 있다).
     * <p>
     * 목적지는 활성 포인터 행이 들고 있는 {@code previousTestVersion}이다 - 감사 이력을 뒤지지
     * 않는 이유는 {@link ActiveTestVersion#previousTestVersion()}에 적어 두었다. 되돌린 순간
     * 방금 떠나온 버전이 새 목적지가 되므로, 롤백을 두 번 하면 원래 자리로 돌아온다.
     * <p>
     * <b>목적지는 잠금을 잡은 뒤에 읽는다.</b> 잠금 밖에서 먼저 읽으면 읽기와 쓰기 사이에 다른
     * 전환이 끼어들 수 있다 - {@code active=A, previous=B}에서 활성 전환(C)과 롤백이 겹치면,
     * 롤백은 이미 낡은 B를 목적지로 들고 들어와 방금 올라온 C를 두 걸음 뒤인 B로 되돌리고
     * 감사 이력에는 존재한 적 없는 "C 다음 B" 관계가 남는다. 그래서 활성 전환과 달리 롤백은
     * 검증도 잠금 안에서 한다 - 무엇을 검증할지가 잠금을 잡아야 정해지기 때문이다.
     *
     * @throws ApiException 되돌아갈 이전 버전이 없으면 409 {@code ADMIN_ROLLBACK_UNAVAILABLE}
     *                      (최초 발행 직후가 그렇다)
     */
    public ActiveVersionResponse rollback(@Nullable String reason) {
        return apply(ActiveVersionAudit.Action.ROLLBACK, pointer -> {
            String target = pointer.previousTestVersion();
            if (target == null) {
                throw new ApiException(ErrorCode.ADMIN_ROLLBACK_UNAVAILABLE);
            }
            requireActivatable(target);
            return target;
        }, reason);
    }

    /** 지금 활성인 버전과 롤백 목적지 - 관리자 목록 조회(§6)가 쓴다. */
    public ActiveTestVersion current() {
        return activeVersions.findById(ActiveTestVersion.CURRENT)
                .orElseThrow(() -> new IllegalStateException(
                        "활성 버전 행(active_test_version.CURRENT)이 사라졌다"));
    }

    /**
     * 대상 버전이 활성이 될 수 있는지 확인한다. 발행되지 않았으면 404, MVP에서 활성화할 수
     * 없는 방언이면 409다.
     * <p>
     * 방언 검사는 지금 도달하지 않는다 - 발행 검증이 경남이 아닌 정의를 아예 싣지 않아
     * ({@link TestDefinitionRegistry#validate}) 그 앞의 조회가 먼저 404를 낸다. 발행 쪽 규칙이
     * 풀리는 날 마지막 방어선으로 남으라고 둔 것이다 (AC - 경북 정의는 활성화 불가, §6).
     */
    private void requireActivatable(String testVersion) {
        TestDefinition target = registry.get(testVersion).definition();
        if (!TestDefinitionRegistry.DIALECT_GYEONGNAM.equals(target.dialect())) {
            throw new ApiException(ErrorCode.ADMIN_DIALECT_NOT_ALLOWED);
        }
    }

    /**
     * 전환의 공통 절차 - 잠금, 목적지 확정, 포인터 UPDATE와 감사 INSERT 한 트랜잭션, 커밋 뒤 메모리 반영.
     * <p>
     * {@code synchronized}는 같은 프로세스 안의 동시 전환을 막는다. 운영자가 이따금 부르는
     * 저빈도 경로라 비용이 없고, 배포가 단일 인스턴스로 고정되어 있어(BE 인메모리 상태 때문,
     * KAN-119) 실질적으로 이것만으로 충분하다. 그래도 잠금 조회를 함께 쓰는 것은 인스턴스가
     * 둘 이상이 되는 날 이 코드가 조용히 틀리지 않게 하기 위해서다.
     * <p>
     * <b>목적지는 잠금 안에서 정한다</b> ({@code target}이 값이 아니라 함수인 이유다). 롤백은
     * 목적지가 현재 포인터에서 유도되므로, 잠금 밖에서 먼저 계산하면 읽기와 쓰기가 원자적이지
     * 않아 다른 전환이 끼어든다 ({@link #rollback} 참고). 활성 전환은 대상이 요청에 실려 오므로
     * 검증을 잠금 전에 끝내고 들어온다.
     * <p>
     * 순서가 중요하다. <b>메모리 반영은 커밋 뒤</b>여야 한다 - 트랜잭션 안에서 바꾸면 롤백된
     * 전환이 메모리에만 남아 그 뒤의 세션이 DB와 다른 버전을 고정한다. 로그도 같은 이유로
     * 커밋 뒤다 (세션 폐기 로그와 같은 규칙, {@code SessionService}).
     */
    private synchronized ActiveVersionResponse apply(ActiveVersionAudit.Action action,
                                                     Function<ActiveTestVersion, String> target,
                                                     @Nullable String reason) {
        Instant now = Instant.now();
        Outcome outcome = Objects.requireNonNull(transactionTemplate.execute(tx -> {
            ActiveTestVersion pointer = activeVersions.lockById(ActiveTestVersion.CURRENT)
                    .orElseThrow(() -> new IllegalStateException(
                            "활성 버전 행(active_test_version.CURRENT)이 사라졌다"));
            String testVersion = target.apply(pointer);
            String previous = pointer.testVersion();
            if (previous.equals(testVersion)) {
                return new Outcome(pointer.testVersion(), pointer.previousTestVersion(),
                        pointer.activatedAt(), false);
            }
            pointer.moveTo(testVersion, now);
            audits.save(new ActiveVersionAudit(action, previous, testVersion, reason, now));
            return new Outcome(testVersion, previous, now, true);
        }));

        if (outcome.changed()) {
            registry.applyActivation(outcome.activeVersion());
            log.info("활성 테스트 버전 전환 action={} from={} to={} reason={}",
                    action, outcome.previousVersion(), outcome.activeVersion(), reason);
        }
        return new ActiveVersionResponse(outcome.activeVersion(), outcome.previousVersion(),
                outcome.activatedAt(), outcome.changed());
    }

    /**
     * 트랜잭션 안에서 정해진 결과를 밖으로 들고 나온다 - 로그와 메모리 반영이 커밋 뒤여야 해서다.
     *
     * @param changed 실제로 바뀌었는지. 같은 버전 재활성화는 false이고, 그때는 감사 행도 없다.
     */
    private record Outcome(String activeVersion, @Nullable String previousVersion,
                           Instant activatedAt, boolean changed) {
    }
}
