package app.accentury.backend.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, String> {

    /** 멱등 재전송 판별 (§5.2) - 유니크 제약과 같은 키 조합의 단건 조회 */
    Optional<AnalysisJob> findBySessionIdAndItemIdAndIdempotencyKey(
            String sessionId, String itemId, String idempotencyKey);

    /** 다음 attempt 번호 계산용 (§5.1) */
    long countBySessionIdAndItemId(String sessionId, String itemId);
}
