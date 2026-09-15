package app.accentury.backend.feedback;

import app.accentury.backend.analytics.Traffic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * 결과 화면에서 보낸 이용 후기 = 세션당 1행 (KAN-211).
 * <p>
 * (session_id) 유니크 제약이 "세션당 한 번"을 DB 수준에서 강제한다. 제출은 세션 행 잠금으로
 * 직렬화되므로({@link FeedbackService}) 이 제약은 마지막 안전망이다.
 * <p>
 * <b>세션 테이블과 FK로 묶지 않는다.</b> 세션과 결과는 24시간 뒤 정리 잡이 지우지만(§5.5)
 * 후기는 1년 남아야 하므로, FK가 있으면 후기가 세션과 함께 사라지거나 세션 정리가 막힌다.
 * 대신 후기를 읽을 때 맥락이 되는 값을 저장 시점에 복사해 둔다 - 등급({@code tierCode}),
 * 테스트/점수 버전, 플랫폼, 트래픽 구분이다. 세션 행이 사라진 뒤에 남는 것은 이 스냅샷뿐이라,
 * 여기 없는 값은 나중에 되찾을 방법이 없다.
 * <p>
 * 개인 식별 정보는 선택 입력인 {@code contactEmail} 하나다. 이 값과 본문은 어떤 로그에도
 * 남기지 않는다 ({@link FeedbackService}, §2.6).
 */
@Entity
@Table(name = "session_feedback",
        uniqueConstraints = @UniqueConstraint(name = "ux_session_feedback_session",
                columnNames = "session_id"))
public class SessionFeedback {

    /** 형식: {@code fb_} + UUID */
    @Id
    @Column(length = 40)
    private String id;

    /** 후기를 보낸 세션 - FK는 없다 (클래스 javadoc의 보존 기간 차이). */
    @Column(name = "session_id", nullable = false, length = 40)
    private String sessionId;

    /** 클라이언트가 보낸 Idempotency-Key - 재전송(같은 키)과 재제출(새 키)을 가른다 (§5.2). */
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    /**
     * 선택 입력인 별점 1~5. 범위 검증은 서비스가 한다.
     * <p>
     * 컬럼이 {@code smallint}라 필드도 {@link Short}다 - Hibernate의 스키마 validate가
     * 폭까지 대조하므로 {@code Integer}로 두면 기동이 막힌다. API 계약은 정수 1~5이고
     * ({@link FeedbackRequest}) 변환은 서비스가 검증 뒤에 한 번 한다.
     */
    @Column
    private @Nullable Short rating;

    /** 후기 본문 - 필수, trim 후 1~500자 (서비스 검증). 로그에 남기지 않는다. */
    @Column(nullable = false, length = 500)
    private String body;

    /** 선택 입력인 회신용 이메일 - 로그에 남기지 않는다. */
    @Column(name = "contact_email", length = 254)
    private @Nullable String contactEmail;

    /** 저장 시점 {@code test_result.tier_code} 스냅샷 - 어떤 등급을 본 사람의 말인지. */
    @Column(name = "tier_code", nullable = false, length = 40)
    private String tierCode;

    /** 세션의 {@code test_version} 스냅샷 - 문항이 바뀐 뒤에도 어느 세트의 후기인지 안다 (§5.4). */
    @Column(name = "test_version", nullable = false, length = 40)
    private String testVersion;

    /** 세션의 {@code score_version} 스냅샷 - 점수 정책이 바뀌면 등급의 의미도 바뀐다. */
    @Column(name = "score_version", nullable = false, length = 20)
    private String scoreVersion;

    /** 세션의 플랫폼 스냅샷 - 세션 생성 때 안 보내면 없다(nullable). */
    @Column(length = 10)
    private @Nullable String platform;

    /** 합성 트래픽(E2E 스모크, KAN-138)이 남긴 후기를 나중에 걸러내기 위한 구분. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Traffic traffic;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SessionFeedback() {
        // JPA 전용
    }

    public SessionFeedback(String id, String sessionId, String idempotencyKey,
                           @Nullable Short rating, String body, @Nullable String contactEmail,
                           String tierCode, String testVersion, String scoreVersion,
                           @Nullable String platform, Traffic traffic, Instant createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.idempotencyKey = idempotencyKey;
        this.rating = rating;
        this.body = body;
        this.contactEmail = contactEmail;
        this.tierCode = tierCode;
        this.testVersion = testVersion;
        this.scoreVersion = scoreVersion;
        this.platform = platform;
        this.traffic = traffic;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public String sessionId() {
        return sessionId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public @Nullable Short rating() {
        return rating;
    }

    public String body() {
        return body;
    }

    public @Nullable String contactEmail() {
        return contactEmail;
    }

    public String tierCode() {
        return tierCode;
    }

    public String testVersion() {
        return testVersion;
    }

    public String scoreVersion() {
        return scoreVersion;
    }

    public @Nullable String platform() {
        return platform;
    }

    public Traffic traffic() {
        return traffic;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
