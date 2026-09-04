package app.accentury.backend.vocab;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 어휘 문항의 답안 = 문항당 1행 (KAN-15, API 명세서 §3.5).
 * <p>
 * 음성 시도({@code analysis_job})와 달리 재제출로 행이 쌓이지 않는다 -
 * (session_id, item_id) 유니크 제약이 "정상 답변이 한 번만 저장된다"(AC)를
 * DB 수준에서 강제한다. 제출은 세션 행 잠금으로 직렬화되므로({@code VocabAnswerService})
 * 이 제약은 마지막 안전망이다.
 * <p>
 * 정오({@code is_correct})는 제출 시점에 정답표와 대조해 저장한다. 정의는 발행 후
 * 불변이고(§5.4) 세션이 버전에 고정되므로, 제출 시점 판정과 {@code /complete} 시점
 * 판정이 다를 수 없다. 이 값은 KAN-21 단어 점수의 입력이며 어떤 응답에도 싣지 않는다.
 */
@Entity
@Table(name = "vocab_answer",
        uniqueConstraints = @UniqueConstraint(name = "ux_vocab_answer_session_item",
                columnNames = {"session_id", "item_id"}))
public class VocabAnswer {

    /** 형식: {@code va_} + UUID */
    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "session_id", nullable = false, length = 40)
    private String sessionId;

    @Column(name = "item_id", nullable = false, length = 40)
    private String itemId;

    /** 사용자가 고른 선택지 - 세션 버전의 해당 문항 선택지임을 서비스가 검증한다. */
    @Column(name = "choice_id", nullable = false, length = 40)
    private String choiceId;

    /** 정답표 대조 결과 - KAN-21 단어 점수(정답률 x 100)의 입력. 응답 비노출 (KAN-13) */
    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    /** 클라이언트가 보낸 Idempotency-Key - 재전송(같은 키)과 재제출(새 키)을 가른다 (§5.2). */
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected VocabAnswer() {
        // JPA 전용
    }

    public VocabAnswer(String id, String sessionId, String itemId, String choiceId,
                       boolean correct, String idempotencyKey, Instant createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.itemId = itemId;
        this.choiceId = choiceId;
        this.correct = correct;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
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

    public String choiceId() {
        return choiceId;
    }

    public boolean correct() {
        return correct;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
