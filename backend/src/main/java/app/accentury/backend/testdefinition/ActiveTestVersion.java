package app.accentury.backend.testdefinition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * 활성 테스트 정의를 가리키는 한 행 (KAN-26, 명세서 §6).
 * <p>
 * 이 값은 새 세션이 고정할 버전이다 (§3.1, §5.4). <b>이미 만들어진 세션은 여기를 다시 보지
 * 않는다</b> - 세션 행이 생성 시점의 {@code testVersion}을 자기 컬럼에 들고 있어서, 활성이
 * 바뀌어도 진행 중 응시는 자기 문항으로 끝난다 (AC - 활성 버전 변경이 진행 중 세션에 영향을
 * 주지 않는다).
 * <p>
 * 행은 하나뿐이고 식별자는 {@link #CURRENT}로 고정이다 - 권역별 활성 버전이 하나여야 하는데
 * MVP의 권역이 경남 하나이므로(KAN-8 범위 제외) 행도 하나가 된다. 두 번째 행은 DB의 check
 * 제약이 막는다. 권역이 늘면 식별자를 권역 코드로 바꾸는 것이 확장 경로다.
 */
@Entity
@Table(name = "active_test_version")
public class ActiveTestVersion {

    /** 단 하나뿐인 행의 식별자. 마이그레이션의 check 제약이 이 값 외의 행을 거부한다. */
    public static final String CURRENT = "CURRENT";

    @Id
    @Column(length = 20)
    private String id;

    /** 활성 정의의 버전. FK가 걸려 있어 발행되지 않은 버전은 DB가 거부한다. */
    @Column(name = "test_version", nullable = false, length = 40)
    private String testVersion;

    /**
     * 롤백 목적지 - 이 전환 직전에 활성이던 버전이다. 최초 발행 상태에서만 null이고, 그때의
     * 롤백 요청은 되돌아갈 자리가 없어 409다.
     * <p>
     * 같은 값이 {@link ActiveVersionAudit}에도 남지만 롤백은 <b>이 컬럼</b>을 본다. 이력에서
     * 다음 동작을 유도하면 감사 로그를 보관 정책으로 잘라내는 순간 기능이 바뀌고, 같은 시각에
     * 기록된 두 행 사이에서 "가장 최근"이 흔들린다. 감사 테이블은 읽기 전용 기록으로 두고,
     * 다음 동작에 필요한 상태는 현재 상태를 담은 이 행이 들고 있게 한다.
     * <p>
     * 그래서 롤백을 두 번 하면 두 버전을 오간다 - 되돌린 순간 방금 떠나온 버전이 새 목적지가
     * 되기 때문이다. 임의 시점으로 되돌아가는 것은 그 버전을 명시한 활성 전환이다.
     */
    @Column(name = "previous_test_version", length = 40)
    private @Nullable String previousTestVersion;

    /** 이 버전이 활성이 된 시각. 왜 바꿨는지는 {@link ActiveVersionAudit}에 남는다. */
    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    protected ActiveTestVersion() {
        // JPA 전용
    }

    /** 픽스처 전용 - 실제 첫 행은 마이그레이션이 넣는다 ({@link StoredTestDefinition}과 같은 이유). */
    ActiveTestVersion(String testVersion, @Nullable String previousTestVersion, Instant activatedAt) {
        this.id = CURRENT;
        this.testVersion = testVersion;
        this.previousTestVersion = previousTestVersion;
        this.activatedAt = activatedAt;
    }

    /**
     * 활성 버전을 갈아 끼운다 - 유일한 변경 지점이다. 떠나온 버전이 새 롤백 목적지가 된다.
     * <p>
     * 호출부({@link ActiveVersionService})가 잠금 아래에서, 감사 행 기록과 같은 트랜잭션으로
     * 부른다. 이력 없이 포인터만 바뀌는 경로를 만들지 않기 위한 제약이라 접근 범위를 좁혀 둔다.
     */
    void moveTo(String newTestVersion, Instant activatedAt) {
        this.previousTestVersion = this.testVersion;
        this.testVersion = newTestVersion;
        this.activatedAt = activatedAt;
    }

    public String id() {
        return id;
    }

    public String testVersion() {
        return testVersion;
    }

    public @Nullable String previousTestVersion() {
        return previousTestVersion;
    }

    public Instant activatedAt() {
        return activatedAt;
    }
}
