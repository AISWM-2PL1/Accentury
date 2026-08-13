package app.accentury.backend.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 키 단위 고정 윈도우(1분) 요청 제한기 - 인메모리 (API 명세서 §2.5, NFR-SC-04).
 * <p>
 * 업로드는 IP를(KAN-23), 완료는 세션을(KAN-16) 키로 쓴다 - 한도와 정리 주기만 다르고
 * 판정 로직이 같아 여기로 모았다. 프로토타입은 인메모리로 충분하다 - 임계치 확정과
 * 다중 인스턴스 공유 저장소는 KAN-28에서 정식 도입한다 (§7).
 */
public class FixedWindowRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limitPerMinute;
    private final Clock clock;

    private record Window(Instant start, int count) {
    }

    protected FixedWindowRateLimiter(int limitPerMinute, Clock clock) {
        this.limitPerMinute = limitPerMinute;
        this.clock = clock;
    }

    /** 한도 초과면 429 + Retry-After (§2.2) - 윈도우가 끝날 때까지 남은 시간을 알려준다 */
    public void check(String key) {
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

    /** 지나간 윈도우 정리 - 무계정 특성상 키가 무한히 쌓이는 것을 막는다. 스케줄은 하위 클래스가 건다 */
    public void evictExpired() {
        Instant cutoff = clock.instant().minus(WINDOW);
        windows.entrySet().removeIf(entry -> entry.getValue().start().isBefore(cutoff));
    }

    /**
     * 추적 중인 키 수 - 정리가 실제로 맵을 줄이는지 확인하는 관찰점이다.
     * 정리는 판정에 영향을 주지 않아(만료 윈도우는 check가 이미 무시한다) 동작으로는 드러나지 않는다.
     */
    protected int trackedKeys() {
        return windows.size();
    }
}
