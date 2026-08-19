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

    /** 잠금 없는 조회 - 기동 시 초기 활성 버전을 읽는 용도다. */
    Optional<ActiveTestVersion> findById(String id);

    /**
     * 활성 포인터를 배타 잠금으로 읽는다 - 전환 트랜잭션의 첫 문장이다.
     * <p>
     * 전환은 운영자가 이따금 부르는 저빈도 경로이고 행도 하나뿐이라, 이 잠금이 다른 트래픽을
     * 막지 않는다 - 응시 경로는 이 행을 읽지 않고 각 세션이 고정한 버전을 쓴다 (§5.4).
     * 같은 프로세스 안의 경합은 서비스의 {@code synchronized}가 먼저 걸러내고, 이 잠금은
     * 인스턴스가 둘 이상이 되는 배포까지 덮는 두 번째 방어선이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ActiveTestVersion a where a.id = :id")
    Optional<ActiveTestVersion> lockById(String id);
}
