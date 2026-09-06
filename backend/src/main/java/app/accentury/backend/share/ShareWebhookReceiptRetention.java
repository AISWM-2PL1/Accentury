package app.accentury.backend.share;

import app.accentury.backend.common.AccenturyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 웹훅 수신 기록의 보존 기간 정리 (KAN-164).
 * <p>
 * 기록은 중복 콜백을 거르는 용도뿐이라 며칠 뒤에는 쓸모가 없고, 리소스 ID가 개인을 가리키지는
 * 않아도 쌓아 둘 이유가 없다. 보존 기간은 {@code accentury.share.receipt-retention}(기본 7일)이다 -
 * 세션과 결과의 24시간보다 긴 것은 카카오의 재전송 간격을 우리가 모르기 때문이고, 그 안에 다시
 * 온 콜백은 세지 않는다.
 */
@Component
public class ShareWebhookReceiptRetention {

    private static final Logger log = LoggerFactory.getLogger(ShareWebhookReceiptRetention.class);

    private final ShareWebhookReceiptRepository repository;
    private final AccenturyProperties properties;

    public ShareWebhookReceiptRetention(ShareWebhookReceiptRepository repository,
                                        AccenturyProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** 다른 정리 잡(분석 15분, 어휘 25분, 결과 35분 지연)과 시작 시점만 어긋나게 둔다 - 같은 순간의 삭제 몰림 방지 */
    @Scheduled(initialDelay = 45, fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(properties.share().receiptRetention());
        int removed = repository.deleteByReceivedAtBefore(cutoff);
        if (removed > 0) {
            log.info("보존 기간이 지난 공유 웹훅 수신 기록 {}건 삭제", removed);
        }
    }
}
