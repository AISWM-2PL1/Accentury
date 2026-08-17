package app.accentury.backend.analytics;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 집계 행의 원자적 증가 (KAN-106).
 * <p>
 * 증가는 두 갈래다: 이미 있는 행이면 {@code UPDATE ... SET x = x + ?} 한 문장, 그 날의 첫
 * 증가면 INSERT. 순서를 <b>UPDATE 먼저</b>로 잡은 이유는 첫 증가만 INSERT를 타고 나머지
 * 전부는 문장 하나로 끝나기 때문이다 - 하루에 한 번뿐인 경합을 위해 매 요청이 조회를
 * 하지 않는다.
 * <p>
 * 두 갈래 사이에는 언제나 틈이 있다 (UPDATE가 0을 반환한 뒤 다른 요청이 먼저 INSERT).
 * 그 경합은 (일자, 버전, 버전) 유니크 제약이 잡고, 진 쪽은 UPDATE로 되돌아온다
 * ({@link AnalyticsCounters}가 그 순서를 맡는다). DB별 upsert 문법(PostgreSQL
 * {@code ON CONFLICT}) 대신 이 형태를 쓰는 것은 테스트가 H2에서 돌기 때문이다.
 * <p>
 * <b>각 단계는 {@code REQUIRES_NEW}로 자기 트랜잭션에서 끝난다.</b> 호출부의 트랜잭션에
 * 합류하면 증가가 실패할 때 그 트랜잭션이 rollback-only로 오염돼 사용자 요청까지 죽는다 -
 * 티켓이 금지한 것이 정확히 그것이다. 다만 호출은 <b>사용자 트랜잭션이 커밋된 뒤</b>여야
 * 한다 - 커밋 전에 부르면 세션 행 잠금을 쥔 채로 커넥션을 하나 더 잡는다.
 */
@Repository
class DailyCounterStore implements CounterStore {

    private final DailyCounterRepository repository;
    private final EntityManager entityManager;

    DailyCounterStore(DailyCounterRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean increment(String id, CounterDelta delta) {
        return repository.increment(id,
                delta.sessionsStarted(), delta.sessionsCompleted(),
                delta.tierOutsider(), delta.tierTraveler(), delta.tierWannabe(),
                delta.tierHonorary(), delta.tierNative(),
                delta.intonationSum(), delta.vocabularySum(), delta.overallSum(),
                delta.scoredCount()) > 0;
    }

    /**
     * 그 날의 첫 행을 증가분을 담은 채로 만든다.
     * <p>
     * {@code save()}가 아니라 {@code persist() + flush()}다 - 식별자를 직접 정하는 엔티티라
     * {@code save()}는 merge로 가고, merge는 <b>조회 후 저장</b>이라 이미 있는 행을
     * 절대값으로 덮어써 동시에 들어온 증가를 지운다. persist는 INSERT만 시도하고,
     * 지면 여기서 즉시(flush) 예외로 끝난다.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(LocalDate statDate, String testVersion, String scoreVersion, CounterDelta delta) {
        entityManager.persist(new DailyCounter(statDate, testVersion, scoreVersion, delta));
        entityManager.flush();
    }
}
