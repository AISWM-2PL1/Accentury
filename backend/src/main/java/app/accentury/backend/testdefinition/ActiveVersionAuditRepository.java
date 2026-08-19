package app.accentury.backend.testdefinition;

import org.springframework.data.domain.Limit;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * 활성 전환 감사 이력 저장소 (KAN-26 AC - 발행·롤백 이력이 남는다).
 * <p>
 * 추가와 읽기만 있고 수정과 삭제가 없다 - 감사 로그는 append-only여야 나중에 믿을 수 있다.
 * 롤백 목적지를 여기서 유도하지 않는 이유는 {@link ActiveTestVersion#previousTestVersion()}에
 * 적어 두었다.
 */
public interface ActiveVersionAuditRepository extends Repository<ActiveVersionAudit, String> {

    /** 이력 한 줄을 남긴다 - 포인터 UPDATE와 같은 트랜잭션이어야 한다. */
    ActiveVersionAudit save(ActiveVersionAudit audit);

    /**
     * 최근 이력 - 관리자 목록 조회(§6)가 쓴다. 최신이 위로 온다.
     * <p>
     * 같은 시각의 두 행 사이 순서를 식별자로 고정한다. 전환이 잠금으로 직렬화되어 실제로
     * 겹칠 일은 없지만, 정렬이 흔들리면 목록 응답이 호출마다 달라져 회귀 테스트가 흔들린다.
     */
    List<ActiveVersionAudit> findAllByOrderByRecordedAtDescIdDesc(Limit limit);

    /** 행 수 - 테스트가 쓴다. */
    long count();
}
