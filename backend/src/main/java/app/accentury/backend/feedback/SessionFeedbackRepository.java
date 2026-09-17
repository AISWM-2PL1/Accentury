package app.accentury.backend.feedback;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * 후기 저장소 - 세션당 단건 조회와 INSERT, 보존 기간 삭제가 전부다 (KAN-211).
 * <p>
 * {@code JpaRepository}가 아니라 필요한 메서드만 선언한다 ({@code ShareWebhookReceiptRepository}와
 * 같은 방식) - 후기는 수정도 개별 삭제도 하지 않으므로, 쓰지 않는 쓰기 경로를 열어 둘 이유가 없다.
 */
public interface SessionFeedbackRepository extends Repository<SessionFeedback, String> {

    /** 멱등 재전송/재제출 판별의 진입점 - 유니크 제약과 같은 키의 단건 조회 */
    Optional<SessionFeedback> findBySessionId(String sessionId);

    /**
     * id 단건 조회 - 슬랙 알림이 커밋 뒤에 행을 다시 읽는 자리다 (KAN-211 2단계,
     * {@link FeedbackSlackNotifier}). 비어 있으면 그 사이 보존 기간 정리가 지웠다는 뜻이라
     * 알림도 보내지 않는다.
     */
    Optional<SessionFeedback> findById(String id);

    SessionFeedback save(SessionFeedback feedback);

    /**
     * 세션의 후기 수 - 0 아니면 1이다 (유니크 제약). 테스트가 쓴다.
     * <p>
     * 전수 {@link #count()}가 아니라 세션 단위인 것은, 같은 테스트 클래스의 다른 시나리오가
     * 남긴 행에 판정이 흔들리지 않게 하려는 것이다.
     */
    long countBySessionId(String sessionId);

    /** 행 수 - 보존 기간 정리 테스트가 쓴다. */
    long count();

    /**
     * 보존 기간이 지난 후기 삭제 - 벌크 한 문장이다 (엔티티를 올리지 않는다).
     * 호출부에 트랜잭션 필요.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SessionFeedback f where f.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
