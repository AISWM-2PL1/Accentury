package app.accentury.backend.result;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface TestResultRepository extends JpaRepository<TestResult, String> {

    /** 결과 조회의 진입점 - (session_id) 유니크라 단건이다 ({@code /result}, KAN-25) */
    Optional<TestResult> findBySessionId(String sessionId);

    /**
     * 보존 기간 지난 결과 정리 (§5.5). 호출부에 트랜잭션 필요.
     * <p>
     * 행이 저장 시점에 확정한 {@code expires_at}을 기준으로 지운다 - 이후 설정이 바뀌어도
     * 이미 발급된 결과의 수명({@code /result} 응답의 expiresAt, §3.7)과 어긋나지 않는다.
     * 파생 delete의 건별 삭제 대신 벌크 한 문장이다 - 세션/분석 작업 정리와 같은 방식
     */
    @Modifying
    @Query("delete from TestResult r where r.expiresAt < :cutoff")
    long deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
