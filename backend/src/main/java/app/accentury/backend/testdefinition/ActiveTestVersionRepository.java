package app.accentury.backend.testdefinition;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * 활성 버전 포인터 저장소 (KAN-26).
 * <p>
 * 행이 하나뿐이라 조회 메서드도 하나다. 쓰기는 {@code save()}가 아니라 <b>잠금 조회 뒤의
 * 더티 체킹</b>으로 한다 ({@link ActiveVersionService}) - 활성 전환은 "읽고 확인하고 바꾸는"
 * 절차라, 읽기와 쓰기 사이에 다른 전환이 끼면 감사 이력의 previous가 실제와 어긋난다.
 */
public interface ActiveTestVersionRepository extends Repository<ActiveTestVersion, String> {

    /**
     * 잠금 없는 조회 - 기동 시 초기 활성 버전 검증과, 세션 생성마다의 활성 버전 읽기가 쓴다
     * ({@link TestDefinitionRegistry#active()}, KAN-167). 기본 키 1행 조회라 세션 생성의
     * INSERT 옆에 얹혀도 비용이 드러나지 않는다.
     */
    Optional<ActiveTestVersion> findById(String id);

    /**
     * 활성 포인터를 배타 잠금으로 읽는다 - 전환 트랜잭션의 첫 문장이다.
     * <p>
     * 전환은 운영자가 이따금 부르는 저빈도 경로이고 행도 하나뿐이라, 이 잠금이 다른 트래픽을
     * 막지 않는다 - 응시 경로는 각 세션이 고정한 버전을 쓰고(§5.4), 세션 생성이 이 행을 읽는
     * 것은 잠금 없는 일반 조회라 PostgreSQL의 MVCC 아래에서 행 잠금에 막히지 않는다.
     * 같은 프로세스 안의 경합은 서비스의 {@code synchronized}가 먼저 걸러내고, 인스턴스 사이의
     * 직렬화는 이 잠금이 맡는다 (KAN-167 - 다중 인스턴스 배포).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ActiveTestVersion a where a.id = :id")
    Optional<ActiveTestVersion> lockById(String id);
}
