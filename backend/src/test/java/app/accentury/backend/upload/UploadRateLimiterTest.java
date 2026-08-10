package app.accentury.backend.upload;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IP 단위 고정 윈도우 제한의 단위 명세 (KAN-23, 명세서 §2.5) */
class UploadRateLimiterTest {

    /** 테스트가 직접 진행시키는 시계 */
    private static final class SteppingClock extends Clock {

        private Instant now = Instant.parse("2026-08-06T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    void 한도를_넘으면_429와_남은_시간을_준다() {
        UploadRateLimiter limiter = new UploadRateLimiter(2, new SteppingClock());
        limiter.check("1.2.3.4");
        limiter.check("1.2.3.4");

        ApiException limited = assertThrows(ApiException.class, () -> limiter.check("1.2.3.4"));

        assertEquals(ErrorCode.RATE_LIMITED, limited.code());
        assertNotNull(limited.retryAfterMs());
        assertTrue(limited.retryAfterMs() > 0 && limited.retryAfterMs() <= 60_000,
                "retryAfterMs=" + limited.retryAfterMs());
    }

    @Test
    void 다른_IP는_영향을_받지_않는다() {
        UploadRateLimiter limiter = new UploadRateLimiter(1, new SteppingClock());
        limiter.check("1.2.3.4");

        assertDoesNotThrow(() -> limiter.check("5.6.7.8"));
    }

    @Test
    void 윈도우가_지나면_다시_허용한다() {
        SteppingClock clock = new SteppingClock();
        UploadRateLimiter limiter = new UploadRateLimiter(1, clock);
        limiter.check("1.2.3.4");
        assertThrows(ApiException.class, () -> limiter.check("1.2.3.4"));

        clock.advance(Duration.ofSeconds(61));

        assertDoesNotThrow(() -> limiter.check("1.2.3.4"));
    }

    @Test
    void 정리는_지나간_윈도우만_지운다() {
        // 무계정 웹이라 IP 키가 무한히 쌓이는 것을 막는 것이 정리의 목적이다
        SteppingClock clock = new SteppingClock();
        UploadRateLimiter limiter = new UploadRateLimiter(2, clock);
        limiter.check("1.2.3.4");

        clock.advance(Duration.ofSeconds(61));
        limiter.check("5.6.7.8");
        assertEquals(2, limiter.trackedIps());

        limiter.evictExpired();

        assertEquals(1, limiter.trackedIps(), "만료된 1.2.3.4만 사라지고 활성인 5.6.7.8은 남아야 한다");
    }

    @Test
    void 정리가_활성_윈도우의_카운트를_잃지_않는다() {
        // 정리가 과하게 지우면 한도에 걸린 IP가 즉시 풀려 제한이 무력화된다
        SteppingClock clock = new SteppingClock();
        UploadRateLimiter limiter = new UploadRateLimiter(2, clock);
        limiter.check("1.2.3.4");
        limiter.check("1.2.3.4");

        clock.advance(Duration.ofSeconds(30));
        limiter.evictExpired();

        assertThrows(ApiException.class, () -> limiter.check("1.2.3.4"));
    }
}
