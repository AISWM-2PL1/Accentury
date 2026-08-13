package app.accentury.backend.result;

import app.accentury.backend.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 결과 보존 기간 정리의 명세 (KAN-16, API 명세서 §5.5 - 결과는 24시간 후 파기).
 * <p>
 * 기준은 행이 저장 시점에 확정한 {@code expires_at}이다 - 설정 변경이 이미 발급된
 * 결과의 수명({@code /result} 응답의 expiresAt)과 어긋나면 안 된다.
 */
class TestResultRetentionTest extends IntegrationTest {

    @Autowired
    private TestResultRetention retention;

    @Autowired
    private TestResultRepository repository;

    @Test
    void 만료된_결과만_지운다() {
        Instant now = Instant.now();
        repository.save(result("r_retention-expired", "s_retention-expired",
                now.minusSeconds(90_000), now.minusSeconds(3_600)));
        repository.save(result("r_retention-active", "s_retention-active",
                now.minusSeconds(3_600), now.plusSeconds(82_800)));

        retention.purgeExpired();

        assertTrue(repository.findById("r_retention-expired").isEmpty(),
                "만료된 결과는 삭제되어야 한다");
        assertFalse(repository.findById("r_retention-active").isEmpty(),
                "보존 기간이 남은 결과는 남아야 한다");
    }

    private static TestResult result(String id, String sessionId, Instant createdAt, Instant expiresAt) {
        return new TestResult(id, sessionId, "gn-2026.08.1", "sv-0.3",
                75, 60, 70, "HONORARY", "명예주민", 4, 5, createdAt, expiresAt);
    }
}
