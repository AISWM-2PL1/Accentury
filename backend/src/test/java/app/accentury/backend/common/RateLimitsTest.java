package app.accentury.backend.common;

import app.accentury.backend.SteppingClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 고정 윈도우 제한 판정의 단위 명세 (KAN-23 도입, KAN-28 범위별 통합, 명세서 §2.5).
 * <p>
 * 범위(Scope)마다 창이 따로라는 것도 여기서 본다 - 하나로 섞이면 업로드 폭주가
 * 세션 생성까지 막는 연쇄가 생긴다.
 */
class RateLimitsTest {

    @Test
    void 한도를_넘으면_429와_남은_시간을_준다() {
        RateLimits limits = limits(2, new SteppingClock());
        limits.check(RateLimits.Scope.VOICE_UPLOAD_IP, "1.2.3.4");
        limits.check(RateLimits.Scope.VOICE_UPLOAD_IP, "1.2.3.4");

        ApiException limited = assertThrows(ApiException.class,
                () -> limits.check(RateLimits.Scope.VOICE_UPLOAD_IP, "1.2.3.4"));

        assertEquals(ErrorCode.RATE_LIMITED, limited.code());
        assertNotNull(limited.retryAfterMs());
        assertTrue(limited.retryAfterMs() > 0 && limited.retryAfterMs() <= 60_000,
                "retryAfterMs=" + limited.retryAfterMs());
    }

    @Test
    void 다른_키는_영향을_받지_않는다() {
        RateLimits limits = limits(1, new SteppingClock());
        limits.check(RateLimits.Scope.VOICE_UPLOAD_IP, "1.2.3.4");

        assertDoesNotThrow(() -> limits.check(RateLimits.Scope.VOICE_UPLOAD_IP, "5.6.7.8"));
    }

    @Test
    void 범위가_다르면_같은_키라도_창이_다르다() {
        // 세션 하나가 업로드 한도를 다 써도 답안 제출과 완료 폴링까지 막히면 안 된다 (§2.5)
        RateLimits limits = limits(1, new SteppingClock());
        limits.check(RateLimits.Scope.VOICE_UPLOAD_SESSION, "s_1");
        assertThrows(ApiException.class,
                () -> limits.check(RateLimits.Scope.VOICE_UPLOAD_SESSION, "s_1"));

        assertDoesNotThrow(() -> limits.check(RateLimits.Scope.VOCAB_ANSWER, "s_1"));
        assertDoesNotThrow(() -> limits.check(RateLimits.Scope.COMPLETE, "s_1"));
    }

    @Test
    void 윈도우가_지나면_다시_허용한다() {
        SteppingClock clock = new SteppingClock();
        RateLimits limits = limits(1, clock);
        limits.check(RateLimits.Scope.SESSION_CREATE, "1.2.3.4");
        assertThrows(ApiException.class,
                () -> limits.check(RateLimits.Scope.SESSION_CREATE, "1.2.3.4"));

        clock.advance(Duration.ofSeconds(61));

        assertDoesNotThrow(() -> limits.check(RateLimits.Scope.SESSION_CREATE, "1.2.3.4"));
    }

    @Test
    void 정리는_지나간_윈도우만_지운다() {
        // 무계정 웹이라 IP와 세션 키가 무한히 쌓이는 것을 막는 것이 정리의 목적이다
        SteppingClock clock = new SteppingClock();
        RateLimits limits = limits(2, clock);
        limits.check(RateLimits.Scope.VOICE_UPLOAD_IP, "1.2.3.4");

        clock.advance(Duration.ofSeconds(61));
        limits.check(RateLimits.Scope.VOICE_UPLOAD_IP, "5.6.7.8");
        assertEquals(2, limits.trackedKeys(RateLimits.Scope.VOICE_UPLOAD_IP));

        limits.evictExpired();

        assertEquals(1, limits.trackedKeys(RateLimits.Scope.VOICE_UPLOAD_IP),
                "만료된 1.2.3.4만 사라지고 활성인 5.6.7.8은 남아야 한다");
    }

    @Test
    void 정리가_활성_윈도우의_카운트를_잃지_않는다() {
        // 정리가 과하게 지우면 한도에 걸린 키가 즉시 풀려 제한이 무력화된다
        SteppingClock clock = new SteppingClock();
        RateLimits limits = limits(2, clock);
        limits.check(RateLimits.Scope.VOICE_UPLOAD_IP, "1.2.3.4");
        limits.check(RateLimits.Scope.VOICE_UPLOAD_IP, "1.2.3.4");

        clock.advance(Duration.ofSeconds(30));
        limits.evictExpired();

        assertThrows(ApiException.class,
                () -> limits.check(RateLimits.Scope.VOICE_UPLOAD_IP, "1.2.3.4"));
    }

    @Test
    void 한도가_빠진_범위가_있으면_조립에_실패한다() {
        // 축을 추가하고 한도를 안 정하면 그 경로만 조용히 무제한이 된다
        Map<RateLimits.Scope, Integer> incomplete = new EnumMap<>(RateLimits.Scope.class);
        incomplete.put(RateLimits.Scope.SESSION_CREATE, 10);

        assertThrows(IllegalStateException.class,
                () -> new RateLimits(incomplete, new SteppingClock()));
    }

    @Test
    void 한도가_1보다_작으면_조립에_실패한다() {
        // 없는 한도의 반대쪽 사고다 - 0이면 첫 요청부터 429라 그 경로가 통째로 막힌다.
        // 한도가 네 개 설정 섹션에 흩어져 있어 부분만 채운 배포 설정에서 나온다
        Map<RateLimits.Scope, Integer> zeroed = new EnumMap<>(RateLimits.Scope.class);
        for (RateLimits.Scope scope : RateLimits.Scope.values()) {
            zeroed.put(scope, 10);
        }
        zeroed.put(RateLimits.Scope.VOCAB_ANSWER, 0);

        assertThrows(IllegalStateException.class,
                () -> new RateLimits(zeroed, new SteppingClock()));
    }

    private static RateLimits limits(int limitPerMinute, Clock clock) {
        Map<RateLimits.Scope, Integer> limits = new EnumMap<>(RateLimits.Scope.class);
        for (RateLimits.Scope scope : RateLimits.Scope.values()) {
            limits.put(scope, limitPerMinute);
        }
        return new RateLimits(limits, clock);
    }
}
