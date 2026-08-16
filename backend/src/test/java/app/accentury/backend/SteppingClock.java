package app.accentury.backend;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 테스트가 직접 진행시키는 시계.
 * <p>
 * 요청 제한 윈도우(§2.5)나 회로 쿨다운(KAN-28)처럼 시간이 판정에 들어가는 코드는
 * 실제 대기 없이 검증해야 한다 - {@code Thread.sleep}으로 시간을 흘리면 테스트가
 * 느려지고 CI 부하에 따라 깜빡인다.
 */
public final class SteppingClock extends Clock {

    private Instant now = Instant.parse("2026-08-06T00:00:00Z");

    public void advance(Duration duration) {
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
