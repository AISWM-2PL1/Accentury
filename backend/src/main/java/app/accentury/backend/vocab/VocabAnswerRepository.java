package app.accentury.backend.vocab;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface VocabAnswerRepository extends JpaRepository<VocabAnswer, String> {

    /** 멱등 재전송·재제출 판별의 진입점 - 유니크 제약과 같은 키 조합의 단건 조회 */
    Optional<VocabAnswer> findBySessionIdAndItemId(String sessionId, String itemId);

    /** 답안이 저장된 어휘 문항 수 - 진행도(answeredCount)의 어휘 쪽 입력 (§3.5) */
    long countBySessionId(String sessionId);

    /**
     * 보존 기간 지난 답안 정리 (§5.5). 호출부에 트랜잭션 필요.
     * 파생 delete의 건별 삭제 대신 벌크 한 문장이다 - 세션·분석 작업 정리와 같은 방식
     */
    @Modifying
    @Query("delete from VocabAnswer a where a.createdAt < :cutoff")
    long deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
