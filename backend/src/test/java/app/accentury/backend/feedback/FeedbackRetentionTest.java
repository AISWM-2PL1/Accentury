package app.accentury.backend.feedback;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.analytics.Traffic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 후기 보존 기간 정리 (KAN-211) - 기간이 지난 것만 지우고 최근 것은 남긴다. */
class FeedbackRetentionTest extends IntegrationTest {

    @Autowired
    private SessionFeedbackRepository repository;

    @Autowired
    private FeedbackRetention retention;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 보존_기간이_지난_후기만_지운다() {
        // 기본 보존 기간(365일)을 기준으로 하루 전과 하루 후 - main 값을 그대로 검증한다.
        // 세션 행 없이 넣는 것 자체가 FK가 없다는 사실의 검증이다 - 세션은 24시간 뒤 사라진다.
        Instant now = Instant.now();
        transactionTemplate.executeWithoutResult(tx -> {
            repository.save(feedback("s_stale", now.minus(Duration.ofDays(366))));
            repository.save(feedback("s_fresh", now.minus(Duration.ofDays(364))));
        });

        retention.purgeExpired();

        assertFalse(repository.findBySessionId("s_stale").isPresent(), "366일 전 후기는 지워진다");
        assertTrue(repository.findBySessionId("s_fresh").isPresent(), "364일 전 후기는 남는다");
        assertEquals(1, repository.count(), "지워진 것은 한 건뿐이어야 한다");
    }

    private static SessionFeedback feedback(String sessionId, Instant createdAt) {
        return new SessionFeedback("fb_" + sessionId, sessionId, "k-" + sessionId,
                (short) 4, "보존 기간 검증용 후기", null, "NATIVE", "gn-2026.08.1", "sv-0.3",
                null, Traffic.REAL, createdAt);
    }
}
