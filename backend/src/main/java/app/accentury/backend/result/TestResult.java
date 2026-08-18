package app.accentury.backend.result;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 세션 하나의 확정된 최종 결과 (KAN-16, API 명세서 §3.6, §3.7).
 * <p>
 * {@code /complete}가 집계(KAN-21) 직후 저장하는 유일한 쓰기 지점이고, 이후 불변이다 -
 * 완료 재시도는 이 행을 다시 만들지 않고(AC - 중복 생성 없음), {@code /result}(KAN-25)는
 * 읽기만 한다. (session_id) 유니크 제약은 세션 행 잠금 직렬화의 마지막 안전망이다.
 * <p>
 * 등급의 code/name/rank를 판정 시점 값으로 저장한다 - 결과는 세션이 고정한
 * {@code scoreVersion}(§5.4)의 판정이므로, 이후 정책 seed가 바뀌어도 이 행은
 * 그 시점의 정본으로 남는다 (KAN-47 오프라인 재채점의 대조 기준).
 */
@Entity
@Table(name = "test_result",
        uniqueConstraints = @UniqueConstraint(name = "ux_test_result_session",
                columnNames = {"session_id"}))
public class TestResult {

    /** 형식: {@code r_} + UUID */
    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "session_id", nullable = false, length = 40)
    private String sessionId;

    /** 세션이 생성 시점에 고정한 테스트 정의 버전 (§5.4) - §3.7 응답에 그대로 실린다. */
    @Column(name = "test_version", nullable = false, length = 40)
    private String testVersion;

    /** 집계에 쓴 점수 버전 - 이 값 하나로 가중치와 경계를 재현할 수 있다 (KAN-21 AC). */
    @Column(name = "score_version", nullable = false, length = 20)
    private String scoreVersion;

    /** 억양 점수 0~100 - 음성 5문항 20점 환산 점수의 합 (§4.3, 반올림 정수) */
    @Column(nullable = false)
    private int intonation;

    /** 단어 점수 0~100 - 어휘 정답률 x 100 (0/20/40/60/80/100) */
    @Column(nullable = false)
    private int vocabulary;

    /** 종합 점수 0~100 - 가중 평균 (sv-0.3: 억양 2 : 단어 1). 등급 판정의 입력이다. */
    @Column(nullable = false)
    private int overall;

    /** 등급 코드 (예: HONORARY) - §3.7 tier.code, 클라이언트 자산 키 계약 (KAN-21) */
    @Column(name = "tier_code", nullable = false, length = 40)
    private String tierCode;

    /** 등급 표시 이름 (예: 명예주민) - §3.7 tier.name */
    @Column(name = "tier_name", nullable = false, length = 60)
    private String tierName;

    /** 1(외지인)~5(경남 토박이) - §3.7 tier.rank */
    @Column(name = "tier_rank", nullable = false)
    private int tierRank;

    /** 전체 등급 수 (5) - §3.7 tier.of */
    @Column(name = "tier_count", nullable = false)
    private int tierCount;

    /** {@code /complete}가 결과를 확정한 시각 - 세션의 completed_at과 같은 트랜잭션에 기록된다. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 이 시각 이후 조회는 410 RESULT_EXPIRED (§3.7, KAN-25) - 생성 시점 + 보존 기간(24시간, §5.5) */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected TestResult() {
        // JPA 전용
    }

    public TestResult(String id, String sessionId, String testVersion, String scoreVersion,
                      int intonation, int vocabulary, int overall,
                      String tierCode, String tierName, int tierRank, int tierCount,
                      Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.testVersion = testVersion;
        this.scoreVersion = scoreVersion;
        this.intonation = intonation;
        this.vocabulary = vocabulary;
        this.overall = overall;
        this.tierCode = tierCode;
        this.tierName = tierName;
        this.tierRank = tierRank;
        this.tierCount = tierCount;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public String id() {
        return id;
    }

    public String sessionId() {
        return sessionId;
    }

    public String testVersion() {
        return testVersion;
    }

    public String scoreVersion() {
        return scoreVersion;
    }

    public int intonation() {
        return intonation;
    }

    public int vocabulary() {
        return vocabulary;
    }

    public int overall() {
        return overall;
    }

    public String tierCode() {
        return tierCode;
    }

    public String tierName() {
        return tierName;
    }

    public int tierRank() {
        return tierRank;
    }

    public int tierCount() {
        return tierCount;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
