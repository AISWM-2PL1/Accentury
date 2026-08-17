package app.accentury.backend.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;

/**
 * 하루치 익명 집계 카운터 한 줄 (KAN-106, SRS FR-AN-10).
 * <p>
 * <b>이 행에 개인을 식별할 수 있는 것은 하나도 없다.</b> 세션 ID, 토큰, IP, 개별 점수 행을
 * 남기지 않고 숫자만 더한다 - 평균은 합({@code intonationSum} 등)을 건수({@code scoredCount})로
 * 나눠 구한다. 개별 점수 행을 만들지 않으려고 고른 형태다 (티켓의 핵심 제약).
 * <p>
 * 키는 (일자, 테스트 버전, 점수 버전) 셋이다 - 버전이 바뀌면 문항도 채점식도 달라지므로
 * 통계를 섞으면 안 된다 (KAN-20 등급 분포 리포트, KAN-21 등급 편향 추적이 소비처다).
 * 일자 경계는 {@code accentury.analytics.zone}(기본 Asia/Seoul) 기준이다 (2026-08-17 확정) -
 * 리포트를 읽는 사람의 하루와 행의 하루가 같아야 한다.
 * <p>
 * <b>식별자는 키 셋에서 유도한 문자열이다</b> ({@link #idOf}). 복합 키 매핑 대신 이렇게 한 이유는
 * 증가가 "PK 한 건 UPDATE" 한 문장으로 끝나야 하기 때문이다 - 조회 후 저장(read-modify-write)은
 * 동시 요청에서 증가를 잃는다 (티켓 제약). 유도 규칙에 버그가 나도 (일자, 버전, 버전) 유니크
 * 제약이 두 줄로 갈라지는 것을 막는다.
 * <p>
 * 개인 결과(24시간 만료, KAN-25)와 무관하게 영속한다 - 어떤 보존 정리 잡도 이 테이블을
 * 건드리지 않는다. 재응시로 이전 세션이 즉시 폐기돼도(KAN-107) 이미 센 카운터는 되돌리지
 * 않는다 - 시도와 완주는 실제로 발생한 사실이다.
 */
@Entity
@Table(name = "daily_counter",
        uniqueConstraints = @UniqueConstraint(name = "ux_daily_counter_key",
                columnNames = {"stat_date", "test_version", "score_version"}))
public class DailyCounter {

    /** 키 셋을 잇는 구분자 - 버전 문자열(gn-2026.08.1, sv-0.3)에 나오지 않는 문자다 */
    private static final String KEY_SEPARATOR = "|";

    /** 형식: {@code 2026-08-17|gn-2026.08.1|sv-0.3} - {@link #idOf}가 만드는 유도 값이다 */
    @Id
    @Column(length = 100)
    private String id;

    /**
     * 집계 일자 - 설정된 타임존 기준의 하루다.
     * 컬럼명이 {@code date}가 아닌 것은 여러 DB에서 예약어라서다 (API 응답 필드명은 {@code date})
     */
    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    /** 이 행이 집계한 테스트 정의 버전 (§5.4) - 문항이 다르면 통계를 섞지 않는다 */
    @Column(name = "test_version", nullable = false, length = 40)
    private String testVersion;

    /** 이 행이 집계한 점수 버전 - 가중치와 등급 경계가 다르면 분포를 섞지 않는다 (KAN-21) */
    @Column(name = "score_version", nullable = false, length = 20)
    private String scoreVersion;

    /** 응시 시도 수 - {@code POST /v0/sessions} 성공 건수 (KAN-9). 재응시도 새 시도로 센다 */
    @Column(name = "sessions_started", nullable = false)
    private long sessionsStarted;

    /** 완주 수 - {@code /complete}가 결과를 확정한 건수 (KAN-16). 시도 수와의 비가 완주율이다 */
    @Column(name = "sessions_completed", nullable = false)
    private long sessionsCompleted;

    /** 등급별 누적 - 순서와 이름은 {@code ScorePolicyRegistry.TIER_CODES}와 1:1이다 (KAN-21) */
    @Column(name = "tier_outsider", nullable = false)
    private long tierOutsider;

    @Column(name = "tier_traveler", nullable = false)
    private long tierTraveler;

    @Column(name = "tier_wannabe", nullable = false)
    private long tierWannabe;

    @Column(name = "tier_honorary", nullable = false)
    private long tierHonorary;

    @Column(name = "tier_native", nullable = false)
    private long tierNative;

    /** 억양 점수 합 - 평균은 {@code scoredCount}로 나눈다. 개별 점수는 남기지 않는다 */
    @Column(name = "intonation_sum", nullable = false)
    private long intonationSum;

    /** 단어 점수 합 */
    @Column(name = "vocabulary_sum", nullable = false)
    private long vocabularySum;

    /** 종합 점수 합 */
    @Column(name = "overall_sum", nullable = false)
    private long overallSum;

    /** 점수 합에 들어간 건수 - 세 평균의 공통 분모다 (완주 1건당 세 점수가 함께 들어온다) */
    @Column(name = "scored_count", nullable = false)
    private long scoredCount;

    protected DailyCounter() {
        // JPA 전용
    }

    /** 첫 증가가 만드는 행 - 0에서 시작하지 않고 그 증가분을 이미 담은 채로 태어난다 */
    DailyCounter(LocalDate statDate, String testVersion, String scoreVersion, CounterDelta delta) {
        this.id = idOf(statDate, testVersion, scoreVersion);
        this.statDate = statDate;
        this.testVersion = testVersion;
        this.scoreVersion = scoreVersion;
        this.sessionsStarted = delta.sessionsStarted();
        this.sessionsCompleted = delta.sessionsCompleted();
        this.tierOutsider = delta.tierOutsider();
        this.tierTraveler = delta.tierTraveler();
        this.tierWannabe = delta.tierWannabe();
        this.tierHonorary = delta.tierHonorary();
        this.tierNative = delta.tierNative();
        this.intonationSum = delta.intonationSum();
        this.vocabularySum = delta.vocabularySum();
        this.overallSum = delta.overallSum();
        this.scoredCount = delta.scoredCount();
    }

    /**
     * 키 셋 → 식별자. 같은 키는 언제나 같은 문자열이라 upsert가 PK 한 건 경합으로 좁혀진다.
     * <p>
     * 버전 문자열에 구분자가 들어 있으면 거부한다 (Fable 리뷰 P3). 그대로 두면 서로 다른 키
     * 셋이 같은 식별자로 접히는데({@code a|b} + {@code c} = {@code a} + {@code b|c}), 증가는
     * 식별자만 보고 UPDATE하므로 유니크 제약에 걸리지 않고 <b>남의 행에 조용히 합산된다</b>.
     * 통계가 어긋난 채 남는 것보다 그 1건을 잃고 로그에 남기는 편이 낫다 - 호출부
     * ({@link AnalyticsCounters})가 이 예외를 삼켜 사용자 요청은 그대로 성공한다.
     */
    public static String idOf(LocalDate statDate, String testVersion, String scoreVersion) {
        if (testVersion.contains(KEY_SEPARATOR) || scoreVersion.contains(KEY_SEPARATOR)) {
            throw new IllegalArgumentException("버전 문자열에 키 구분자 '" + KEY_SEPARATOR + "'가 들어 있다: "
                    + testVersion + ", " + scoreVersion);
        }
        return statDate + KEY_SEPARATOR + testVersion + KEY_SEPARATOR + scoreVersion;
    }

    public String id() {
        return id;
    }

    public LocalDate statDate() {
        return statDate;
    }

    public String testVersion() {
        return testVersion;
    }

    public String scoreVersion() {
        return scoreVersion;
    }

    public long sessionsStarted() {
        return sessionsStarted;
    }

    public long sessionsCompleted() {
        return sessionsCompleted;
    }

    public long tierOutsider() {
        return tierOutsider;
    }

    public long tierTraveler() {
        return tierTraveler;
    }

    public long tierWannabe() {
        return tierWannabe;
    }

    public long tierHonorary() {
        return tierHonorary;
    }

    public long tierNative() {
        return tierNative;
    }

    public long intonationSum() {
        return intonationSum;
    }

    public long vocabularySum() {
        return vocabularySum;
    }

    public long overallSum() {
        return overallSum;
    }

    public long scoredCount() {
        return scoredCount;
    }
}
