package app.accentury.backend.testdefinition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * 활성 버전 전환 이력 한 줄 (KAN-26 AC - 발행·롤백 이력이 감사 로그에 남는다).
 * <p>
 * 애플리케이션 로그가 아니라 테이블인 이유가 둘이다. 로그는 로테이션으로 사라지는데 "언제
 * 무엇에서 무엇으로 바꿨는가"는 사고 조사에서 몇 주 뒤에 필요하고, <b>롤백 대상을 서버가
 * 스스로 알아야</b> 하기 때문이다 - 가장 최근 행의 {@link #previousVersion}이 곧 되돌아갈
 * 자리다 ({@link ActiveVersionService#rollback}).
 * <p>
 * 개인 식별 정보가 없다. 운영자의 전환 행위만 남고 세션이나 응시자와 연결되는 컬럼이 없어서,
 * 음성·결과 무보관 원칙(NFR-PR-03)과 충돌하지 않는다 - 익명 집계 카운터(KAN-106)와 같은
 * 계열의 영속 데이터다. 어떤 보존 정리 잡도 이 테이블을 건드리지 않는다.
 */
@Entity
@Table(name = "active_version_audit")
public class ActiveVersionAudit {

    /**
     * 전환의 종류.
     * <p>
     * 되돌아간 것인지 새로 올린 것인지는 버전 번호만 보고는 알 수 없다 - 롤백이 곧 옛 버전을
     * 다시 활성으로 만드는 일이라, 행만 보면 "구버전을 새로 활성화한 것"과 구별되지 않는다.
     * 운영자의 의도를 남기려고 종류를 따로 적는다.
     */
    public enum Action {
        /** 지정한 버전을 활성으로 올렸다. */
        ACTIVATE,
        /** 직전 활성 버전으로 되돌렸다. */
        ROLLBACK
    }

    /** 사유 문구의 최대 길이 - 저장 컬럼과 같은 값이라, 넘는 요청은 저장 전에 400으로 끊는다. */
    public static final int MAX_REASON_LENGTH = 200;

    @Id
    @Column(length = 40)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    /**
     * 이 전환 직전에 활성이던 버전. <b>최초 발행 행에서만 null이다</b> - 그 상태에서의 롤백
     * 요청은 되돌아갈 자리가 없어 409다.
     */
    @Column(name = "previous_version", length = 40)
    private @Nullable String previousVersion;

    /** 이 전환으로 활성이 된 버전. */
    @Column(name = "new_version", nullable = false, length = 40)
    private String newVersion;

    /** 운영자가 남긴 사유. 선택 입력이라 비어 있을 수 있다. */
    @Column(length = MAX_REASON_LENGTH)
    private @Nullable String reason;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected ActiveVersionAudit() {
        // JPA 전용
    }

    ActiveVersionAudit(Action action, @Nullable String previousVersion, String newVersion,
                       @Nullable String reason, Instant recordedAt) {
        this.id = "av_" + UUID.randomUUID();
        this.action = action;
        this.previousVersion = previousVersion;
        this.newVersion = newVersion;
        this.reason = reason;
        this.recordedAt = recordedAt;
    }

    public String id() {
        return id;
    }

    public Action action() {
        return action;
    }

    public @Nullable String previousVersion() {
        return previousVersion;
    }

    public String newVersion() {
        return newVersion;
    }

    public @Nullable String reason() {
        return reason;
    }

    public Instant recordedAt() {
        return recordedAt;
    }
}
