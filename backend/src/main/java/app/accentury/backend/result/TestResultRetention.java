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
 * 완주 세션은 만료가 결과 만료와 같아({@code TestSession.markCompleted}) baseline
 * (KAN-123)의 ON DELETE CASCADE 이후에는 세션 주기 삭제가 결과도 같은 순간 함께
 * 지운다 - 이 잡은 그와 별개로 도는 상한선이자 안전망이다. 기준은 각 행이 저장
 * 시점에 확정한 {@code expires_at}이라 설정 값을 다시 읽지 않는다
 * ({@link TestResultRepository#deleteByExpiresAtBefore}).
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
