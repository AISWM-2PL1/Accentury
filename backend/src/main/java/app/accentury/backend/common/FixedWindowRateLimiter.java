package app.accentury.backend.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 키 단위 고정 윈도우(1분) 요청 제한기 - 인메모리 (API 명세서 §2.5, NFR-SC-04).
 * <p>
 * 판정만 아는 부품이다 - 어떤 경로를 무엇으로 세는지, 한도가 얼마인지, 언제 정리하는지는
 * {@link RateLimits}가 정한다. 프로토타입은 인메모리로 충분하고, 다중 인스턴스 공유
 * 저장소(Redis)는 세션 저장소를 옮기는 시점(§2.1)과 같이 간다.
 */
class FixedWindowRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limitPerMinute;
    private final Clock clock;

    private record Window(Instant start, int count) {
    }

    FixedWindowRateLimiter(int limitPerMinute, Clock clock) {
        this.limitPerMinute = limitPerMinute;
        this.clock = clock;
    }

    /** 한도 초과면 429 + Retry-After (§2.2) - 윈도우가 끝날 때까지 남은 시간을 알려준다. */
    void check(String key) {
        Instant now = clock.instant();
        Window window = windows.compute(key, (unused, current) ->
                current == null || !now.isBefore(current.start().plus(WINDOW))
                        ? new Window(now, 1)
                        : new Window(current.start(), current.count() + 1));
        if (window.count() > limitPerMinute) {
            long retryAfterMs = Duration.between(now, window.start().plus(WINDOW)).toMillis();
            throw ApiException.rateLimited(Math.max(retryAfterMs, 1));
        }
    }

    /** 지나간 윈도우 정리 - 무계정 특성상 키가 무한히 쌓이는 것을 막는다. 스케줄은 {@link RateLimits}가 건다. */
    void evictExpired() {
        Instant cutoff = clock.instant().minus(WINDOW);
        windows.entrySet().removeIf(entry -> entry.getValue().start().isBefore(cutoff));
    }

    /**
     * 추적 중인 키 수 - 정리가 실제로 맵을 줄이는지 확인하는 관찰점이다.
     * 정리는 판정에 영향을 주지 않아(만료 윈도우는 check가 이미 무시한다) 동작으로는 드러나지 않는다.
     */
    int trackedKeys() {
        return windows.size();
    }
}
