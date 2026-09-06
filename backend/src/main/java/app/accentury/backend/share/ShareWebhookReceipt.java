package app.accentury.backend.share;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 받은 웹훅의 기록 - 중복 콜백을 한 번만 세기 위한 것이다 (KAN-164 AC "같은 콜백이 두 번 와도 1만 증가").
 * <p>
 * 카카오는 웹훅마다 고유 ID({@code X-Kakao-Resource-ID})를 붙인다. 그 값을 PK로 두면 두 번째
 * 콜백의 INSERT가 충돌로 끝나고, 그것이 곧 "이미 셌다"는 신호다. 값은 카카오가 만든 불투명 ID라
 * 개인도 세션도 가리키지 않는다. 채팅방 해시(HASH_CHAT_ID)는 저장하지 않는다 - 셀 필요가 없고,
 * 있으면 "누구에게 보냈나"의 흔적이 된다.
 * <p>
 * 영원히 남길 이유가 없다 - 카카오가 같은 전송을 며칠 뒤에 다시 알릴 일은 없으므로 보존 기간
 * ({@code accentury.share.receipt-retention}) 뒤에 {@link ShareWebhookReceiptRetention}이 지운다.
 */
@Entity
@Table(name = "share_webhook_receipt")
public class ShareWebhookReceipt {

    @Id
    @Column(name = "resource_id", length = 128)
    private String resourceId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected ShareWebhookReceipt() {
        // JPA 전용 - 행은 저장소의 INSERT 문장이 만든다.
    }

    public String resourceId() {
        return resourceId;
    }

    public Instant receivedAt() {
        return receivedAt;
    }
}
