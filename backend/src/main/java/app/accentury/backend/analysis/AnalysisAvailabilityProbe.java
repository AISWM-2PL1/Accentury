package app.accentury.backend.analysis;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 회로가 열린 AI 경로의 복구 확인 스케줄 (KAN-28, API 명세서 §4.2).
 * <p>
 * 여기는 박자만 준다 - 지금이 프로브 차례인지, 무엇을 물어볼지는 디스패처가
 * 안다 ({@link HttpAnalysisDispatcher#probeAvailability()}). 회로가 닫혀 있으면
 * 아무 일도 하지 않는 값싼 틱이다.
 * <p>
 * 실제 프로브 간격은 설정({@code accentury.analysis.circuit-probe-interval})이 정하고,
 * 이 틱은 그 간격을 잴 수 있을 만큼만 촘촘하면 된다. 스케줄 주기를 설정과 묶지 않은
 * 것은 의도다 - {@code @Scheduled}의 문자열 주기는 기동 시점에야 해석돼, 오타 하나가
 * 컨텍스트 기동 실패로 돌아온다.
 */
@Component
class AnalysisAvailabilityProbe {

    private final AnalysisDispatcher dispatcher;

    AnalysisAvailabilityProbe(AnalysisDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
    void probe() {
        dispatcher.probeAvailability();
    }
}
