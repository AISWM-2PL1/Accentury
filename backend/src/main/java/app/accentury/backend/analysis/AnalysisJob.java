package app.accentury.backend.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.jspecify.annotations.Nullable;

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

    /**
     * AI가 반환한 문항 억양 점수 0~100 원값 (§4.1) - 완료 전에는 null.
     * 추론 완료 즉시 세션 저장소(이 행)에 누적하고(§4.3), 합산은 /complete에서 1회 한다 (KAN-21·25).
     * 상태 응답에는 절대 싣지 않는다 (§3.4 - 문항 중간 점수 미노출, KAN-12).
     */
    @Column(name = "intonation_score")
    private @Nullable Integer intonationScore;

    /** AI 품질 판정 코드 (§4.1 quality.code, 예: OK) - 완료 전에는 null */
    @Column(name = "quality_code", length = 40)
    private @Nullable String qualityCode;

    /** 실패 사유 코드 (§3.4 error.code, 예: AUDIO_TOO_QUIET) - 실패 상태에서만 값이 있다 */
    @Column(name = "error_code", length = 40)
    private @Nullable String errorCode;

    /** 분석에 쓰인 AI 모델 버전 (§3.4, §4.1) - 완료 전에는 null */
    @Column(name = "model_version", length = 60)
    private @Nullable String modelVersion;

    /** AI가 확인한 점수 버전 (§3.4, §4.1) - 완료 전에는 null */
    @Column(name = "score_version", length = 40)
    private @Nullable String scoreVersion;

    /**
     * 워커가 AI 호출 실행을 시작한 시각 - 큐 대기 중이면 null. 타임아웃 판정이
     * "큐에서 기다리는 중"(정상)과 "실행이 오래 걸림"(잔류)을 가르는 기준이다 (Codex sol 리뷰 P1)
     */
    @Column(name = "started_at")
    private @Nullable Instant startedAt;

    /** 종결(COMPLETED, RETRYABLE_FAILED, FAILED) 시각 - PROCESSING이면 null */
    @Column(name = "finished_at")
    private @Nullable Instant finishedAt;

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
     * 오디오를 저장하지 않으므로(FR-DP-01) 서버가 같은 시도를 재시도할 수 없다.
     * <p>
     * 업로드 요청 스레드가 저장 직후의 자기 작업에만 쓴다 - 경합이 없어 엔티티 변경으로
     * 충분하다. 비동기 전달 이후의 종결(타임아웃 스위퍼와 경합 가능)은 조건부 UPDATE인
     * {@link AnalysisJobTransitions}를 거쳐야 한다.
     */
    public void markRetryableFailed(String errorCode) {
        this.status = AnalysisJobStatus.RETRYABLE_FAILED;
        this.errorCode = errorCode;
        this.finishedAt = Instant.now();
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

    public @Nullable Integer intonationScore() {
        return intonationScore;
    }

    public @Nullable String qualityCode() {
        return qualityCode;
    }

    public @Nullable String errorCode() {
        return errorCode;
    }

    public @Nullable String modelVersion() {
        return modelVersion;
    }

    public @Nullable String scoreVersion() {
        return scoreVersion;
    }

    public @Nullable Instant startedAt() {
        return startedAt;
    }

    public @Nullable Instant finishedAt() {
        return finishedAt;
    }
}
