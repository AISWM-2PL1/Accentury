package app.accentury.backend.feedback;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.analytics.Traffic;
import app.accentury.backend.common.AccenturyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 후기 보존 기간 정리 (KAN-211) - 기간이 지난 것만 지우고 최근 것은 남긴다. */
class FeedbackRetentionTest extends IntegrationTest {

    /** 경계 검증의 기준 시각 - 실제 시계와 무관해야 "정확히 보존 기간 전"을 만들 수 있다. */
    private static final Instant NOW = Instant.parse("2026-08-06T00:00:00Z");

    /** 시험 사이를 비울 때 쓰는 경계 - 어떤 행보다도 뒤다. */
    private static final Instant FAR_FUTURE = Instant.parse("2999-01-01T00:00:00Z");

    @Autowired
    private SessionFeedbackRepository repository;

    @Autowired
    private FeedbackRetention retention;

    @Autowired
    private AccenturyProperties properties;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void 앞_시험이_남긴_행을_비운다() {
        // 클래스 단위 정리(DatabaseWipeExtension)는 메서드 사이를 비우지 않는다 - 두 시험이 모두
        // 전체 행 수를 단언하므로, 비우지 않으면 통과 여부가 실행 순서에 달린다. 저장소가
        // deleteAll을 열지 않으므로(쓰지 않는 쓰기 경로를 두지 않는다) 먼 미래를 경계로 삼는다.
        transactionTemplate.executeWithoutResult(
                tx -> repository.deleteByCreatedAtBefore(FAR_FUTURE));
    }

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

    @Test
    void 정확히_보존_기간_전_후기는_남고_1초만_더_지나면_지워진다() {
        // 경계가 어느 쪽으로 열려 있는지를 고정한다 (Codex 리뷰 P2). deleteByCreatedAtBefore는
        // cutoff "미만"만 지우므로 경계 위의 행은 살아남는다 - 그 한 칸이 어느 쪽인지가 문서와
        // 코드에 적혀 있지 않으면, 나중에 조회를 <=로 바꿔도 아무 테스트가 깨지지 않는다.
        Duration retentionPeriod = properties.feedback().retention();
        transactionTemplate.executeWithoutResult(tx -> {
            repository.save(feedback("s_boundary", NOW.minus(retentionPeriod)));
            repository.save(feedback("s_past", NOW.minus(retentionPeriod).minusSeconds(1)));
        });

        // 스케줄 진입점(@Transactional 프록시)이 아니라 직접 만든 인스턴스라 트랜잭션이 없다 -
        // 파생 delete 질의는 트랜잭션을 요구하므로 여기서 감싼다.
        FeedbackRetention fixed = new FeedbackRetention(repository, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        transactionTemplate.executeWithoutResult(tx -> fixed.purgeExpired());

        assertTrue(repository.findBySessionId("s_boundary").isPresent(),
                "정확히 " + retentionPeriod + " 전 후기는 아직 남는다 - 경계는 cutoff 미만이다");
        assertFalse(repository.findBySessionId("s_past").isPresent(),
                "경계보다 1초 더 지난 후기는 지워진다");
        assertEquals(1, repository.count());
    }

    private static SessionFeedback feedback(String sessionId, Instant createdAt) {
        return new SessionFeedback("fb_" + sessionId, sessionId, "k-" + sessionId,
                (short) 4, "보존 기간 검증용 후기", null, "NATIVE", "gn-2026.08.1", "sv-0.3",
                null, Traffic.REAL, createdAt);
    }
}
