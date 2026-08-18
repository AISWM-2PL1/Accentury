package app.accentury.backend.analysis;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 복구 프로브 스케줄의 배선 (KAN-28, API 명세서 §4.2).
 * <p>
 * 여기는 박자만 준다 - 지금이 프로브 차례인지는 디스패처가 판단한다. 그래서 볼 것은
 * 두 가지뿐이다: 틱이 디스패처에 실제로 닿는가, 그리고 판정할 것이 없는 구현(개발 모드)에서
 * 조용히 지나가는가. 이 배선이 끊기면 회로가 한 번 열린 뒤 <b>영영</b> 복구되지 않는데,
 * 그 증상은 장애가 끝난 뒤에야 드러난다.
 */
class AnalysisAvailabilityProbeTest {

    @Test
    void 틱마다_디스패처에_복구_확인을_넘긴다() {
        AtomicInteger probes = new AtomicInteger();
        AnalysisAvailabilityProbe probe = new AnalysisAvailabilityProbe(new AnalysisDispatcher() {
            @Override
            public void dispatch(AnalysisRequest request) {
                throw new UnsupportedOperationException("이 테스트는 전달을 쓰지 않는다");
            }

            @Override
            public void probeAvailability() {
                probes.incrementAndGet();
            }
        });

        probe.probe();
        probe.probe();

        assertEquals(2, probes.get());
    }

    @Test
    void 판정할_것이_없는_구현에서는_아무_일도_일어나지_않는다() {
        // AI 서버 없이 BE만 띄우는 개발 모드(NoopAnalysisDispatcher)에서도 스케줄은 돈다 -
        // 기본 구현이 비어 있지 않으면 그 환경이 매초 예외로 로그를 채운다.
        AnalysisAvailabilityProbe probe = new AnalysisAvailabilityProbe(new NoopAnalysisDispatcher());

        probe.probe();

        assertTrue(true, "예외 없이 지나간다");
    }
}
