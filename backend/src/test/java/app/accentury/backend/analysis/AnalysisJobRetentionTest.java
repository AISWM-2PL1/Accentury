package app.accentury.backend.analysis;

import app.accentury.backend.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 분석 작업 보존 기간 정리의 명세 (KAN-23, 명세서 §5.5 - 24시간, Codex sol 리뷰 P2) */
class AnalysisJobRetentionTest extends IntegrationTest {

    @Autowired
    private AnalysisJobRepository repository;

    @Autowired
    private AnalysisJobRetention retention;

    @Test
    void 보존_기간이_지난_작업만_삭제된다() {
        Instant now = Instant.now();
        repository.save(new AnalysisJob("a_retention-old", "s_retention", "v1", 1, "old-key",
                AnalysisJobStatus.PROCESSING, now.minus(Duration.ofHours(25))));
        repository.save(new AnalysisJob("a_retention-new", "s_retention", "v2", 1, "new-key",
                AnalysisJobStatus.PROCESSING, now));

        retention.purgeExpired();

        assertFalse(repository.existsById("a_retention-old"));
        assertTrue(repository.existsById("a_retention-new"));
    }
}
