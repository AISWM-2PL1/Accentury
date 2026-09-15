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
     * 재응시 폐기의 진입점 (KAN-107) - 이전 토큰의 세션 행을 잠그고 가져온다.
     * <p>
     * 제출 쓰기(KAN-15/23)와 완료 전이(KAN-16)가 잡는 {@link #lockById}와 같은 행 잠금이라
     * 진행 중인 제출 트랜잭션과 폐기가 직렬화된다 - 잠금 없이 지우면 제출 경로가 완료 검사를
     * 통과한 뒤와 삭제 사이에 자식 행을 끼워 넣어 고아가 남는다. 호출부에 트랜잭션 필요.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TestSession s where s.tokenHash = :tokenHash")
    Optional<TestSession> lockByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * 아직 만료되지 않은 세션 행 수 - 동시 활성 세션 지표의 입력이다 ({@link SessionMetrics}, KAN-38).
     * <p>
     * {@code expires_at}에 인덱스가 없어 전체 스캔이다. 발행 주기마다 한 번(1분)만 부르는 조회라
     * 그대로 두었다 - 근거와 재검토 신호는 {@link SessionMetrics}에 적어 두었다.
     */
    long countByExpiresAtAfter(Instant now);

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
