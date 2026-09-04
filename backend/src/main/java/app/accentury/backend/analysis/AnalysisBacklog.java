package app.accentury.backend.analysis;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>이 인스턴스</b>의 진행 중(in-flight) 분석 전달 건수 (KAN-24, KAN-167).
 * <p>
 * "전달 접수부터 종결 처리까지"를 세는 인메모리 카운터다. 인스턴스별인 것이 의도다 - 이 값의
 * 소비처는 종료 배수({@code AnalysisDrainLifecycle}, KAN-166)로, 자기 워커가 아직 붙들고 있는
 * 작업이 몇인지를 알아야 하고 다른 태스크의 작업은 기다릴 대상이 아니다. 워커 큐가
 * 인스턴스별인 이유({@code AnalysisDispatchConfig}, FR-DP-01)와 같은 경계다.
 * <p>
 * 폴링 혼잡 판정은 KAN-24에서 이 카운터를 썼으나 KAN-167에서 {@link AnalysisCongestion}(DB의
 * PROCESSING 건수)으로 옮겼다. backend가 Fargate 태스크 여러 개로 돌면 이 카운터는 자기 몫만
 * 세어 전체 밀림을 과소 판정하기 때문이다.
 */
@Component
public class AnalysisBacklog {

    private final AtomicInteger inFlight = new AtomicInteger();

    /** 전달 접수 - 워커 큐에 들어가는 시점에 센다. */
    public void started() {
        inFlight.incrementAndGet();
    }

    /**
     * 종결 - 성공이든 실패든 워커가 작업을 놓는 시점. 0 밑으로는 내려가지 않는다 -
     * 이중 복귀가 음수로 쌓이면 혼잡 감지가 조용히 무력화된다 (Codex 리뷰).
     */
    public void finished() {
        inFlight.updateAndGet(n -> n > 0 ? n - 1 : 0);
    }

    public int inFlight() {
        return inFlight.get();
    }
}
