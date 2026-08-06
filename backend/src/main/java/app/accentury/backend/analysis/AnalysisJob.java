package app.accentury.backend.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 음성 문항의 분석 작업 = 시도 1건 (KAN-23, API 명세서 §5.1).
 * <p>
 * 재녹음은 새 행(새 attempt)으로 쌓인다. 채점은 문항당 최신 성공 시도 1건만
 * 대상으로 하며(KAN-24), 이전 시도는 집계에서 제외한다.
 * <p>
 * (session_id, item_id, idempotency_key) 유니크 제약이 네트워크 재전송의
 * 중복 작업 생성을 DB 수준에서 막는다 (§5.2). 오디오 바이트는 어떤 컬럼에도
 * 저장하지 않는다 (FR-DP-01).
 */
@Entity
@Table(name = "analysis_job",
        indexes = @Index(name = "ix_analysis_job_session_item", columnList = "session_id,item_id"),
        uniqueConstraints = @UniqueConstraint(name = "ux_analysis_job_idempotency",
                columnNames = {"session_id", "item_id", "idempotency_key"}))
public class AnalysisJob {

    /** 형식: {@code a_} + UUID (§3.3 예시의 접두사 규칙) */
    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "session_id", nullable = false, length = 40)
    private String sessionId;

    @Column(name = "item_id", nullable = false, length = 40)
    private String itemId;

    /** 같은 문항의 몇 번째 시도인지 (1부터). 표시용이며 채점 순서는 createdAt 기준이다 */
    @Column(nullable = false)
    private int attempt;

    /** 클라이언트가 보낸 Idempotency-Key. 시도 단위 멱등의 기준이다 (§5.2) */
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisJobStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AnalysisJob() {
        // JPA 전용
    }

    public AnalysisJob(String id, String sessionId, String itemId, int attempt,
                       String idempotencyKey, AnalysisJobStatus status, Instant createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.itemId = itemId;
        this.attempt = attempt;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.createdAt = createdAt;
    }

    /**
     * 전달 실패 시 재녹음(새 시도)을 유도하는 상태로 전이한다 (§3.4).
     * 오디오를 저장하지 않으므로(FR-DP-01) 서버가 같은 시도를 재시도할 수 없다 -
     * 상세한 상태 전이 관리는 KAN-24에서 구현한다.
     */
    public void markRetryableFailed() {
        this.status = AnalysisJobStatus.RETRYABLE_FAILED;
    }

    public String id() {
        return id;
    }

    public String sessionId() {
        return sessionId;
    }

    public String itemId() {
        return itemId;
    }

    public int attempt() {
        return attempt;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public AnalysisJobStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
