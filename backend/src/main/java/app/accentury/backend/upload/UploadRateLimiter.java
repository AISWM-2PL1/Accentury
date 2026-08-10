package app.accentury.backend.upload;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * IP 단위 업로드 요청 제한 (KAN-23, API 명세서 §2.5, NFR-SC-04).
 * <p>
 * 웹은 계정이 없어 IP가 유일한 남용 차단 단위다. 프로토타입은 인메모리 고정
 * 윈도우(1분)로 충분하다 - 세션 단위 제한, 재녹음 횟수 상한, 다중 인스턴스
 * 공유 저장소는 KAN-28에서 정식 도입한다. 임계치는 부하 테스트 후 확정한다 (§7).
 */
@Component
public class UploadRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limitPerMinute;
    private final Clock clock;

    private record Window(Instant start, int count) {
    }

    @Autowired
    public UploadRateLimiter(AccenturyProperties properties) {
        this(properties.upload().rateLimitPerMinute(), Clock.systemUTC());
    }

    UploadRateLimiter(int limitPerMinute, Clock clock) {
        this.limitPerMinute = limitPerMinute;
        this.clock = clock;
    }

    /** 한도 초과면 429 + Retry-After (§2.2) - 윈도우가 끝날 때까지 남은 시간을 알려준다 */
    public void check(String clientIp) {
        Instant now = clock.instant();
        Window window = windows.compute(clientIp, (ip, current) ->
                current == null || !now.isBefore(current.start().plus(WINDOW))
                        ? new Window(now, 1)
                        : new Window(current.start(), current.count() + 1));
        if (window.count() > limitPerMinute) {
            long retryAfterMs = Duration.between(now, window.start().plus(WINDOW)).toMillis();
            throw ApiException.rateLimited(Math.max(retryAfterMs, 1));
        }
    }

    /** 지나간 윈도우 정리 - 무계정 웹 특성상 IP 키가 무한히 쌓이는 것을 막는다 */
    @Scheduled(initialDelay = 10, fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    void evictExpired() {
        Instant cutoff = clock.instant().minus(WINDOW);
        windows.entrySet().removeIf(entry -> entry.getValue().start().isBefore(cutoff));
    }

    /**
     * 추적 중인 IP 수 - 정리가 실제로 맵을 줄이는지 확인하는 관찰점이다.
     * 정리는 판정에 영향을 주지 않아(만료 윈도우는 check가 이미 무시한다) 동작으로는 드러나지 않는다.
     */
    int trackedIps() {
        return windows.size();
    }
}
