package app.accentury.backend.upload;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.FixedWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * IP 단위 업로드 요청 제한 (KAN-23, API 명세서 §2.5, NFR-SC-04).
 * <p>
 * 웹은 계정이 없어 IP가 유일한 남용 차단 단위다. 판정 로직은
 * {@link FixedWindowRateLimiter}에 있고(완료 API의 세션 단위 제한과 공용, KAN-16),
 * 여기는 업로드의 키(IP)와 한도, 정리 주기만 정한다. 임계치는 부하 테스트 후
 * 확정한다 (§7, KAN-28).
 */
@Component
public class UploadRateLimiter extends FixedWindowRateLimiter {

    @Autowired
    public UploadRateLimiter(AccenturyProperties properties) {
        this(properties.upload().rateLimitPerMinute(), Clock.systemUTC());
    }

    UploadRateLimiter(int limitPerMinute, Clock clock) {
        super(limitPerMinute, clock);
    }

    /** 지나간 윈도우 정리 - 무계정 웹 특성상 IP 키가 무한히 쌓이는 것을 막는다 */
    @Scheduled(initialDelay = 10, fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    @Override
    public void evictExpired() {
        super.evictExpired();
    }

    int trackedIps() {
        return trackedKeys();
    }
}
