package app.accentury.backend.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface TestSessionRepository extends JpaRepository<TestSession, String> {

    /** 인증 경로 - token_hash 유니크 인덱스 단건 조회 */
    Optional<TestSession> findByTokenHash(String tokenHash);

    /** 만료 세션 주기 삭제 (§2.1). 호출부에 트랜잭션 필요 */
    long deleteByExpiresAtBefore(Instant cutoff);
}
