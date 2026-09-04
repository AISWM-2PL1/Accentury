package app.accentury.backend.analysis;

import app.accentury.backend.common.AccenturyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 분석 작업 보존 기간 정리 (API 명세서 §5.5 - 세션과 결과는 24시간 후 파기, Codex sol 리뷰 P2).
 * <p>
 * createdAt 기준 자체 수명을 갖는다 - 완주 세션은 만료가 결과 만료(24시간, KAN-25)로
 * 연장되어({@code TestSession.markCompleted}) 그동안 작업 상태가 세션과 함께 남는다.
 * baseline(KAN-123)의 ON DELETE CASCADE 이후에는 세션 주기 삭제가 하위 행을 함께
 * 지우므로, 이 잡은 그와 별개로 도는 상한선이자 안전망이다. 업로드마다 행이 쌓이므로
 * 이 잡이 없으면 테이블이 무한히 자란다.
 */
@Component
public class AnalysisJobRetention {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobRetention.class);

    private final AnalysisJobRepository repository;
    private final AccenturyProperties properties;

    public AnalysisJobRetention(AnalysisJobRepository repository, AccenturyProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(initialDelay = 15, fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(properties.analysis().retention());
        long removed = repository.deleteByCreatedAtBefore(cutoff);
        if (removed > 0) {
            log.info("보존 기간이 지난 분석 작업 {}건 삭제", removed);
        }
    }
}
