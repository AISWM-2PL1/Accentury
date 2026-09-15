package app.accentury.backend.share;

import app.accentury.backend.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 수신 기록 보존 기간 정리 (KAN-164) - 기간이 지난 것만 지우고 최근 것은 남긴다. */
class ShareWebhookReceiptRetentionTest extends IntegrationTest {

    @Autowired
    private ShareWebhookReceiptRepository repository;

    @Autowired
    private ShareWebhookReceiptRetention retention;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 보존_기간이_지난_기록만_지운다() {
        // 기본 보존 기간(7일)을 기준으로 하루 전과 하루 후 - main 값을 그대로 검증한다.
        Instant now = Instant.now();
        transactionTemplate.executeWithoutResult(tx -> {
            repository.insertIfAbsent("stale", now.minus(Duration.ofDays(8)));
            repository.insertIfAbsent("fresh", now.minus(Duration.ofDays(6)));
        });

        retention.purgeExpired();

        assertFalse(repository.findById("stale").isPresent(), "8일 전 기록은 지워진다");
        assertTrue(repository.findById("fresh").isPresent(), "6일 전 기록은 남는다 - 그 안의 중복 콜백을 거른다");
    }
}
