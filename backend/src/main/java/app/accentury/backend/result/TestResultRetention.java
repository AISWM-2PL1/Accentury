package app.accentury.backend.result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 최종 결과 보존 기간 정리 (API 명세서 §5.5 - 세션과 결과는 24시간 후 파기).
 * <p>
 * 세션 삭제(30분 TTL)와 연동하지 않는다 - 결과는 세션 만료 후에도 보존 기간 동안
 * 남는다. 기준은 각 행이 저장 시점에 확정한 {@code expires_at}이라 설정 값을 다시
 * 읽지 않는다 ({@link TestResultRepository#deleteByExpiresAtBefore}).
 */
@Component
public class TestResultRetention {

    private static final Logger log = LoggerFactory.getLogger(TestResultRetention.class);

    private final TestResultRepository repository;

    public TestResultRetention(TestResultRepository repository) {
        this.repository = repository;
    }

    /** 분석 작업(15분)/어휘 답안(25분) 정리와 시작 시점만 어긋나게 둔다 - 같은 순간의 삭제 몰림 방지 */
    @Scheduled(initialDelay = 35, fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void purgeExpired() {
        long removed = repository.deleteByExpiresAtBefore(Instant.now());
        if (removed > 0) {
            log.info("보존 기간이 지난 결과 {}건 삭제", removed);
        }
    }
}
