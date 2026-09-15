package app.accentury.backend.share;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;

/**
 * 카카오톡 공유 "전송 완료"의 일자별 카운터 한 줄 (KAN-164, FR-SH-06).
 * <p>
 * 집계 카운터({@code daily_counter}, KAN-106)와 나란한 영속 데이터이지만 키가 다르다 - 웹훅에는
 * testVersion도 scoreVersion도 없고, 있어서도 안 된다 (세션과 연결하지 않는 것이 익명 집계의
 * 요구다). 남는 축은 일자와 캠페인뿐이다. 캠페인은 앱이 {@code serverCallbackArgs}로 실어
 * 보내는 공용 상수({@code kko_share})라 사람을 가리키지 않는다.
 * <p>
 * <b>쓰기는 저장소의 upsert 한 문장이다</b> ({@link ShareDailyCounterRepository#countSent}) - 이
 * 엔티티는 읽기 전용 매핑이고 생성자가 없다. KAN-106이 UPDATE 먼저, 없으면 INSERT로 두 갈래를
 * 둔 것은 그때 테스트가 H2에서 돌아서였고, 지금은 테스트도 PostgreSQL(Testcontainers, KAN-123)이라
 * {@code ON CONFLICT}를 그대로 쓴다.
 */
@Entity
@Table(name = "share_daily_counter",
        uniqueConstraints = @UniqueConstraint(name = "ux_share_daily_counter_key",
                columnNames = {"stat_date", "campaign"}))
public class ShareDailyCounter {

    /** 키 두 조각을 잇는 구분자 - 캠페인 값은 영숫자와 {@code _-}뿐이라 나올 수 없는 문자다. */
    private static final String KEY_SEPARATOR = "|";

    /** 형식: {@code 2026-09-06|kko_share} - {@link #idOf}가 만드는 유도 값이다. */
    @Id
    @Column(length = 60)
    private String id;

    /** 집계 일자 - 집계 카운터와 같은 타임존({@code accentury.analytics.zone}) 기준의 하루다. */
    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    /** 앱이 웹훅에 실어 보낸 캠페인 상수. 값이 형식에 맞지 않으면 {@code unknown}으로 접힌다. */
    @Column(name = "campaign", nullable = false, length = 32)
    private String campaign;

    /** 전송 완료 수 - 카카오가 알려 준 횟수이고, 같은 리소스 ID는 한 번만 센다. */
    @Column(name = "sent", nullable = false)
    private long sent;

    protected ShareDailyCounter() {
        // JPA 전용 - 행은 upsert가 만든다.
    }

    /**
     * 키 셋 → 식별자. upsert의 충돌 대상은 이 값이 아니라 유니크 키({@code stat_date, campaign})다 - 식별자는
     * 첫 행이 태어날 때 채워지는 PK이고, 같은 키는 언제나 같은 문자열이라 조회({@code findById})가 키 셋만으로 된다.
     */
    public static String idOf(LocalDate statDate, String campaign) {
        return statDate + KEY_SEPARATOR + campaign;
    }

    public String id() {
        return id;
    }

    public LocalDate statDate() {
        return statDate;
    }

    public String campaign() {
        return campaign;
    }

    public long sent() {
        return sent;
    }
}
