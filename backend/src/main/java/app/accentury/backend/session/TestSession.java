package app.accentury.backend.session;

import app.accentury.backend.analytics.Traffic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * 익명 테스트 세션 (KAN-9).
 * <p>
 * API 명세서 §2.1 - 토큰은 원문이 아닌 SHA-256 해시로만 저장한다.
 * DB가 유출돼도 유효한 토큰을 복원할 수 없다.
 * <p>
 * 사용자 계정과 광고 식별자 등 개인 식별 정보 컬럼은 두지 않는다 (KAN-9 AC).
 * {@code campaignToken}은 공유 유입 계측용 코드로 개인 식별이 불가능하다 (§3.1).
 * <p>
 * {@link Persistable}인 이유: 식별자를 직접 정하는 엔티티라 {@code save()}가 merge(조회
 * 후 저장)로 가면 생성마다 반드시 빗나가는 SELECT 한 번을 낸다 - 가장 뜨거운 쓰기
 * 경로(세션 생성)의 낭비이고, 재응시 경로에서는 이전 세션의 행 잠금을 쥔 채 실행된다
 * (2026-08-17 리뷰, {@code DailyCounterStore}가 persist를 쓰는 것과 같은 이유).
 * 신규 판정은 생성자 기준이다 - JPA가 조회로 만든 인스턴스(protected 생성자)는 신규가
 * 아니고, 코드가 만든 인스턴스는 첫 INSERT까지만 신규다 ({@link PostPersist}).
 */
@Entity
@Table(name = "test_session",
        indexes = @Index(name = "ux_test_session_token_hash", columnList = "token_hash", unique = true))
public class TestSession implements Persistable<String> {

    /** 형식: {@code s_} + UUID. 클라이언트 경로 파라미터로 쓰인다. */
    @Id
    @Column(length = 40)
    private String id;

    /** 세션 토큰의 SHA-256 해시 (hex 64자). 토큰 원문은 어디에도 저장하지 않는다. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** 생성 시점에 고정되는 테스트 정의 버전 (§5.4 - 진행 중 신규 발행의 영향 없음) */
    @Column(name = "test_version", nullable = false, length = 40)
    private String testVersion;

    /** 생성 시점에 고정되는 점수 버전 (sv-0.3, KAN-21) */
    @Column(name = "score_version", nullable = false, length = 20)
    private String scoreVersion;

    /**
     * 생성 시점에 고정되는 음성 문항 세트 번호 (1부터, KAN-182, §5.4). 세션의 유효 문항은
     * {@code testVersion}과 이 번호로 정해지는 세트 하나(음성 5 + 어휘 5)뿐이다 -
     * 제출 검증, 상태 조회, 완주 판정, 집계가 전부 이 세트만 본다. 기본 1이라 세트를
     * 모르는 클라이언트는 현행과 같다.
     */
    @Column(name = "voice_set", nullable = false)
    private int voiceSet;

    /** IOS / ANDROID / WEB - 익명 집계용 */
    @Column(length = 10)
    private @Nullable String platform;

    @Column(name = "app_version", length = 32)
    private @Nullable String appVersion;

    /** 공유 유입 계측 코드 (개인 식별 불가) */
    @Column(name = "campaign_token", length = 64)
    private @Nullable String campaignToken;

    /**
     * 이 세션이 실사용자인지 검증용 합성 트래픽인지 (KAN-138).
     * <p>
     * 생성 시점에 한 번 정해지고 이후 바뀌지 않는다. 완주 카운터(KAN-106)가 이 값을 따라가므로
     * ({@code CompletionService}), 응시와 완주가 언제나 같은 통에 들어간다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Traffic traffic;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 이 시각 이후 토큰은 무효 - 요청 시 만료 검사 + 주기 삭제 (§2.1) */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * {@code /complete} 성공 시각 (KAN-16이 기록) - null이면 진행 중.
     * 완료된 세션에 추가 답변을 막는 가드의 기준이다 (KAN-15 AC, §3.5 - 409 SESSION_COMPLETED).
     */
    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    /** 코드 생성 인스턴스만 신규다 - JPA의 protected 생성자 경로는 false로 남는다. */
    @Transient
    private boolean isNew = false;

    protected TestSession() {
        // JPA 전용
    }

    public TestSession(String id, String tokenHash, String testVersion, String scoreVersion, int voiceSet,
                       @Nullable String platform, @Nullable String appVersion, @Nullable String campaignToken,
                       Traffic traffic, Instant createdAt, Instant expiresAt) {
        this.isNew = true;
        this.id = id;
        this.tokenHash = tokenHash;
        this.testVersion = testVersion;
        this.scoreVersion = scoreVersion;
        this.voiceSet = voiceSet;
        this.platform = platform;
        this.appVersion = appVersion;
        this.campaignToken = campaignToken;
        this.traffic = traffic;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public boolean isCompleted() {
        return completedAt != null;
    }

    /**
     * 완료 처리 - 호출 지점은 {@code /complete}(KAN-16)다. KAN-15는 읽기(가드)만 한다.
     * <p>
     * 전이는 제출 경로와 같은 세션 행 잠금({@link TestSessionRepository#lockById}) 아래에서
     * 해야 한다 - 아니면 제출 경로의 완료 검사와 저장 사이에 완료가 끼어들어 확정된
     * 세션에 답안/분석 작업이 추가된다 (Codex sol 리뷰 P2).
     * <p>
     * 토큰 수명을 결과 수명(24시간, §5.5)까지 함께 연장한다 (2026-08-14 확정, KAN-25) -
     * 30분 TTL 그대로면 결과 재조회(새로고침, 앱 복귀)와 만료 후 410 안내가 전부 401에
     * 막힌다. 완료된 세션은 제출 가드(SESSION_COMPLETED)로 잠겨 있어 연장으로 열리는
     * 쓰기 경로는 없고, 읽히는 것은 익명 결과뿐이다.
     * <p>
     * 결과 보존 수명을 토큰 수명과 분리(전용 컬럼/tombstone)하라는 지적(Codex sol
     * 리뷰 P2)은 기각한다 (2026-08-14 확정 설계) - 완료 후 상태 조회(/analyses)가 24시간
     * 열리지만 자기 세션의 익명 상태뿐이고, 만료 후 세션 행이 주기 삭제되면 410이 401로
     * 바뀌는 것도 수용한 트레이드오프다(401 안내 문구 역시 재응시로 이끈다). 분리 모델의
     * 값이 프로토타입 복잡도를 넘지 않는다.
     *
     * @param completedAt     완료 확정 시각
     * @param resultExpiresAt 함께 저장되는 결과의 만료 시각 - 세션과 결과가 같은 순간
     *                        만료돼 "세션과 결과는 24시간 후 파기 → 이후 조회 410"(§5.5)이 성립한다.
     */
    public void markCompleted(Instant completedAt, Instant resultExpiresAt) {
        this.completedAt = completedAt;
        this.expiresAt = resultExpiresAt;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    void markPersisted() {
        this.isNew = false;
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

    public int voiceSet() {
        return voiceSet;
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

    public Traffic traffic() {
        return traffic;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public @Nullable Instant completedAt() {
        return completedAt;
    }
}
