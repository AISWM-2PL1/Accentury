package app.accentury.backend.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, String> {

    /** 멱등 재전송 판별 (§5.2) - 유니크 제약과 같은 키 조합의 단건 조회 */
    Optional<AnalysisJob> findBySessionIdAndItemIdAndIdempotencyKey(
            String sessionId, String itemId, String idempotencyKey);

    /** 다음 attempt 번호 계산용 (§5.1) */
    long countBySessionIdAndItemId(String sessionId, String itemId);

    /**
     * 보존 기간 지난 작업 정리 (§5.5). 호출부에 트랜잭션 필요.
     * 파생 delete의 건별 삭제 대신 벌크 한 문장이다 - 세션 정리와 같은 방식
     */
    @Modifying
    @Query("delete from AnalysisJob j where j.createdAt < :cutoff")
    long deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
