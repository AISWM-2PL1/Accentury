package app.accentury.backend.session;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface TestSessionRepository extends JpaRepository<TestSession, String> {

    /** 인증 경로 - token_hash 유니크 인덱스 단건 조회 */
    Optional<TestSession> findByTokenHash(String tokenHash);

    /**
     * 제출 쓰기와 완료 전이의 직렬화 지점 - 세션 행 잠금 (Codex sol 리뷰 P2).
     * <p>
     * 완료 검사와 제출 저장(답안 KAN-15, 분석 작업 KAN-23)이 다른 트랜잭션이면 그 사이에
     * {@code /complete}(KAN-16)가 끼어들어 확정된 세션에 쓰기가 추가된다. 제출 경로는 검사와
     * 저장을 이 잠금 아래 한 트랜잭션으로 묶고, <b>완료 전이 구현도 같은 잠금을 잡아야 한다</b>.
     * 호출부에 트랜잭션 필요 - 잠금은 트랜잭션 커밋까지 유지된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TestSession s where s.id = :id")
    Optional<TestSession> lockById(@Param("id") String id);

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
