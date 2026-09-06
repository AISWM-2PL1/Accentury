package app.accentury.backend.share;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/** 웹훅 수신 기록 저장소 - INSERT 한 문장과 보존 기간 삭제가 전부다 (KAN-164). */
public interface ShareWebhookReceiptRepository extends Repository<ShareWebhookReceipt, String> {

    /** 단건 조회 - 테스트가 쓴다. */
    Optional<ShareWebhookReceipt> findById(String resourceId);

    /** 행 수 - 테스트가 쓴다. */
    long count();

    /**
     * 처음 보는 리소스 ID면 기록하고 1, 이미 있으면 아무것도 하지 않고 0이다.
     * <p>
     * 조회 후 INSERT가 아니라 {@code ON CONFLICT DO NOTHING} 한 문장이다 - 같은 ID의 콜백 둘이 동시에
     * 들어와도 PK가 하나만 통과시키고, 진 쪽은 예외가 아니라 0을 받아 조용히 접는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert into share_webhook_receipt (resource_id, received_at)
            values (:resourceId, :receivedAt)
            on conflict (resource_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("resourceId") String resourceId, @Param("receivedAt") Instant receivedAt);

    /** 보존 기간이 지난 기록 삭제 - 벌크 한 문장이다 (엔티티를 올리지 않는다). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ShareWebhookReceipt r where r.receivedAt < :cutoff")
    int deleteByReceivedAtBefore(@Param("cutoff") Instant cutoff);
}
