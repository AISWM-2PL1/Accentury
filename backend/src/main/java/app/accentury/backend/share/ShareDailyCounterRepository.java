package app.accentury.backend.share;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 공유 전송 카운터 저장소 - 필요한 메서드만 연다 (KAN-106 저장소와 같은 이유).
 * <p>
 * {@code JpaRepository}의 {@code save()}는 식별자를 직접 정하는 이 엔티티에서 merge(조회 후
 * 저장)로 가고, 동시에 들어온 증가를 절대값으로 덮어써 지운다. 쓰기는 {@link #countSent} 한 문장이
 * 전부다.
 */
public interface ShareDailyCounterRepository extends Repository<ShareDailyCounter, String> {

    /** 단건 조회 - 테스트가 쓴다. */
    Optional<ShareDailyCounter> findById(String id);

    /** 행 수 - 테스트가 쓴다. */
    long count();

    /**
     * 전송 1건을 더한다 - <b>DB 한 문장의 원자적 upsert</b>다.
     * <p>
     * 그 날의 첫 건이면 행을 만들고, 이미 있으면 {@code sent}에 1을 더한다. 유니크 키
     * ({@code stat_date, campaign})의 충돌이 직렬화를 대신하므로 조회 후 저장도, 응용 계층 잠금도,
     * INSERT 경합에 지면 UPDATE로 되돌아가는 재시도(KAN-106)도 필요 없다. PostgreSQL 전용 문법인
     * 것은 의도다 - 운영도 테스트도 PostgreSQL이다 (KAN-123).
     *
     * @param id       {@link ShareDailyCounter#idOf} - 첫 행이 태어날 때의 식별자
     * @param statDate 집계 일자
     * @param campaign 캠페인 상수
     * @return 영향 행 수 - INSERT든 UPDATE든 1이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert into share_daily_counter (id, stat_date, campaign, sent)
            values (:id, :statDate, :campaign, 1)
            on conflict (stat_date, campaign) do update set sent = share_daily_counter.sent + 1
            """, nativeQuery = true)
    int countSent(@Param("id") String id, @Param("statDate") LocalDate statDate,
                  @Param("campaign") String campaign);

    /**
     * 기간 조회 (관리자 집계 API, §6.1 {@code shares}). 양 끝 일자를 포함하고 일자 오름차순,
     * 같은 일자 안에서는 캠페인 순이다 - 리포트를 비교할 때 순서가 흔들리면 안 된다.
     */
    List<ShareDailyCounter> findByStatDateBetweenOrderByStatDateAscCampaignAsc(LocalDate from, LocalDate to);
}
