package app.accentury.backend.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface TestSessionRepository extends JpaRepository<TestSession, String> {

    /** 인증 경로 - token_hash 유니크 인덱스 단건 조회 */
    Optional<TestSession> findByTokenHash(String tokenHash);

    /**
     * 만료 세션 주기 삭제 (§2.1). 호출부에 트랜잭션 필요.
     * <p>
     * 파생 delete는 엔티티를 전부 로드해 건별 삭제하므로 벌크 쿼리로 선언한다 -
     * 만료가 한꺼번에 몰려도 DELETE 한 문장이다 (Codex sol 리뷰 P2).
     */
    @Modifying
    @Query("delete from TestSession s where s.expiresAt < :cutoff")
    long deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
