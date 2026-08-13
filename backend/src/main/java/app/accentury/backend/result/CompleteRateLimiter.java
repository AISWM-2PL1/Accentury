package app.accentury.backend.result;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.FixedWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * 세션 단위 {@code /complete} 요청 제한 (KAN-16 AC, API 명세서 §2.5).
 * <p>
 * 폴링 대상 엔드포인트라(§3.6 PROCESSING + pollAfterMs) IP가 아니라 세션이 키다 -
 * NAT 뒤의 여러 정상 세션이 서로의 한도를 깎으면 안 된다. 키는 인증을 통과한
 * 세션 ID만 들어오므로 비인증 요청이 맵을 부풀릴 수 없다. 임계치 확정과 다중
 * 인스턴스 공유 저장소는 KAN-28 몫이다 (§7).
 */
@Component
public class CompleteRateLimiter extends FixedWindowRateLimiter {

    @Autowired
    public CompleteRateLimiter(AccenturyProperties properties) {
        this(properties.completion().rateLimitPerMinute(), Clock.systemUTC());
    }

    CompleteRateLimiter(int limitPerMinute, Clock clock) {
        super(limitPerMinute, clock);
    }

    /** 세션 키 정리 - 업로드 제한 정리(10분 주기)와 시작 시점만 어긋나게 둔다 */
    @Scheduled(initialDelay = 12, fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    @Override
    public void evictExpired() {
        super.evictExpired();
    }
}
