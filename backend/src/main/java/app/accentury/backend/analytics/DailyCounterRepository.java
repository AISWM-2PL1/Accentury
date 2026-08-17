package app.accentury.backend.analytics;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 집계 행 저장소 - <b>필요한 메서드만 연다</b> (Fable 리뷰 P3).
 * <p>
 * {@code JpaRepository}를 상속하지 않는 유일한 저장소다. 그쪽이 딸려 오는 {@code save()}는
 * 식별자를 직접 정하는 이 엔티티에서 merge(조회 후 저장)로 가고, merge는 이미 있는 행을
 * 절대값으로 덮어써 동시에 들어온 증가를 지운다 - 이 티켓이 금지한 read-modify-write가
 * 저장소 API에 그대로 노출되는 셈이라 아예 없앤다. 쓰기는 {@link DailyCounterStore} 두
 * 갈래(원자적 증가, 첫 행 persist)가 전부다.
 */
public interface DailyCounterRepository extends Repository<DailyCounter, String> {

    /** 단건 조회 - 내부 조회와 테스트가 쓴다 */
    Optional<DailyCounter> findById(String id);

    /** 행 수 - 테스트가 쓴다 */
    long count();

    /**
     * 카운터 증가 - <b>DB 한 문장의 원자적 덧셈이다</b> (티켓 제약: read-modify-write 금지).
     * <p>
     * 같은 일자 행에 증가가 몰리므로 조회 후 저장하면 동시 요청에서 증가를 잃는다. PK 한 건을
     * 잡는 {@code UPDATE ... SET x = x + ?}라 DB의 행 잠금이 직렬화를 대신해 주고, 잠금은
     * 문장이 끝나는 즉시 풀린다 - 응용 계층 잠금이 필요 없다.
     * <p>
     * 값이 아니라 증가분을 더하는 형태라 어느 순서로 도착해도 결과가 같다. 등급 다섯 축은
     * {@link CounterDelta}가 0/1로 펼쳐서 넘기므로 여기에는 조건 분기가 없다.
     *
     * @return 1이면 증가 완료, 0이면 그 키의 행이 아직 없다 (첫 증가라 INSERT가 필요하다)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DailyCounter c
               set c.sessionsStarted = c.sessionsStarted + :sessionsStarted,
                   c.sessionsCompleted = c.sessionsCompleted + :sessionsCompleted,
                   c.tierOutsider = c.tierOutsider + :tierOutsider,
                   c.tierTraveler = c.tierTraveler + :tierTraveler,
                   c.tierWannabe = c.tierWannabe + :tierWannabe,
                   c.tierHonorary = c.tierHonorary + :tierHonorary,
                   c.tierNative = c.tierNative + :tierNative,
                   c.intonationSum = c.intonationSum + :intonationSum,
                   c.vocabularySum = c.vocabularySum + :vocabularySum,
                   c.overallSum = c.overallSum + :overallSum,
                   c.scoredCount = c.scoredCount + :scoredCount
             where c.id = :id
            """)
    int increment(@Param("id") String id,
                  @Param("sessionsStarted") long sessionsStarted,
                  @Param("sessionsCompleted") long sessionsCompleted,
                  @Param("tierOutsider") long tierOutsider,
                  @Param("tierTraveler") long tierTraveler,
                  @Param("tierWannabe") long tierWannabe,
                  @Param("tierHonorary") long tierHonorary,
                  @Param("tierNative") long tierNative,
                  @Param("intonationSum") long intonationSum,
                  @Param("vocabularySum") long vocabularySum,
                  @Param("overallSum") long overallSum,
                  @Param("scoredCount") long scoredCount);

    /**
     * 기간 조회 (내부 전용, KAN-106 AC - 등급 누적 수와 점수 평균 확인).
     * 양 끝 일자를 포함한다. 정렬은 일자 오름차순, 같은 일자 안에서는 버전 순이다 -
     * 버전 전환일에 두 줄이 나오는데 그 순서가 흔들리면 리포트를 비교하기 어렵다.
     */
    List<DailyCounter> findByStatDateBetweenOrderByStatDateAscTestVersionAscScoreVersionAsc(
            LocalDate from, LocalDate to);
}
