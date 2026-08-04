package app.accentury.backend.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * 익명 테스트 세션 (KAN-9).
 * <p>
 * API 명세서 §2.1 - 토큰은 원문이 아닌 SHA-256 해시로만 저장한다.
 * DB가 유출돼도 유효한 토큰을 복원할 수 없다.
 * <p>
 * 사용자 계정·광고 식별자 등 개인 식별 정보 컬럼은 두지 않는다 (KAN-9 AC).
 * {@code campaignToken}은 공유 유입 계측용 코드로 개인 식별이 불가능하다 (§3.1).
 */
@Entity
@Table(name = "test_session",
        indexes = @Index(name = "ux_test_session_token_hash", columnList = "token_hash", unique = true))
public class TestSession {

    /** 형식: {@code s_} + UUID. 클라이언트 경로 파라미터로 쓰인다 */
    @Id
    @Column(length = 40)
    private String id;

    /** 세션 토큰의 SHA-256 해시 (hex 64자). 토큰 원문은 어디에도 저장하지 않는다 */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** 생성 시점에 고정되는 테스트 정의 버전 (§5.4 - 진행 중 신규 발행의 영향 없음) */
    @Column(name = "test_version", nullable = false, length = 40)
    private String testVersion;

    /** 생성 시점에 고정되는 점수 버전 (sv-0.3, KAN-21) */
    @Column(name = "score_version", nullable = false, length = 20)
    private String scoreVersion;

    /** IOS / ANDROID / WEB - 익명 집계용 */
    @Column(length = 10)
    private @Nullable String platform;

    @Column(name = "app_version", length = 32)
    private @Nullable String appVersion;

    /** 공유 유입 계측 코드 (개인 식별 불가) */
    @Column(name = "campaign_token", length = 64)
    private @Nullable String campaignToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 이 시각 이후 토큰은 무효 - 요청 시 만료 검사 + 주기 삭제 (§2.1) */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected TestSession() {
        // JPA 전용
    }

    public TestSession(String id, String tokenHash, String testVersion, String scoreVersion,
                       @Nullable String platform, @Nullable String appVersion, @Nullable String campaignToken,
                       Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.tokenHash = tokenHash;
        this.testVersion = testVersion;
        this.scoreVersion = scoreVersion;
        this.platform = platform;
        this.appVersion = appVersion;
        this.campaignToken = campaignToken;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public String id() {
        return id;
    }

    public String tokenHash() {
        return tokenHash;
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

    public @Nullable String appVersion() {
        return appVersion;
    }

    public @Nullable String campaignToken() {
        return campaignToken;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
