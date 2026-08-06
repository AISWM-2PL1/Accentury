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
 * 분석 작업 보존 기간 정리 (API 명세서 §5.5 - 세션·결과는 24시간 후 파기, Codex sol 리뷰 P2).
 * <p>
 * 세션 삭제(30분 TTL)와 연동하지 않고 createdAt 기준 자체 수명을 갖는다 - 작업 상태는
 * 세션 만료 후에도 결과 보존 기간(24시간, KAN-25) 동안 남아 있어야 한다.
 * 업로드마다 행이 쌓이므로 이 잡이 없으면 테이블이 무한히 자란다.
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
