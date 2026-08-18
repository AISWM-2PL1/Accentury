package app.accentury.backend.analysis;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 진행 중(in-flight) 분석 전달 건수 - 혼잡 판정({@link PollIntervals})의 입력 (KAN-24).
 * <p>
 * "전달 접수부터 종결 처리까지"를 세는 인메모리 카운터다. DB를 세지 않는 이유:
 * 혼잡 판정은 모든 폴링 응답 경로에 놓이므로 §5.3 규칙 6(가벼운 조회만)을 지켜야 하고,
 * 어차피 이 인스턴스가 AI로 보내는 압력만 알면 충분하다. BE 다중 인스턴스가 되면
 * 인스턴스별 판정이 된다 - 그 시점의 정밀화는 Redis 전환(§2.1)과 함께 검토한다.
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
