package app.accentury.backend.analysis;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * PROCESSING 잔류 작업의 타임아웃 처리 (KAN-24).
 * <p>
 * 워커가 종결을 못 남기면 작업이 PROCESSING으로 영영 남는다 - 오디오가 없어(FR-DP-01)
 * 서버가 이어서 처리할 수 없으므로 RETRYABLE_FAILED로 넘겨 재녹음(새 시도)을 유도한다.
 * 두 경우를 다른 한도와 다른 사유로 가른다 (Codex sol 리뷰 P1 - 큐 대기를 실행 잔류로
 * 오인해 정상 대기 작업을 폐기하면 안 된다):
 * <ul>
 *   <li><b>실행 잔류</b> ({@code startedAt} 경과): 실행 중 프로세스 사망 등. 한도는
 *       AI 호출 재시도 전체 소요보다 긴 60초 - 클라이언트 폴링 상한(§5.3 규칙 5)과 같은
 *       스케일이다. AI에 닿았을 수 있어 ANALYSIS_TIMEOUT(시도 예산에 포함)이다.</li>
 *   <li><b>큐 유실</b> ({@code startedAt} 없이 {@code createdAt} 경과): 접수와 실행 사이의
 *       프로세스 사망. 정상 큐 대기와 구분할 수 없어 한도가 더 길다(5분 - 정상 큐 소진보다
 *       길고, 복구가 세션 TTL 안에 보이도록 그보다 짧다). AI에 닿지 않았으므로
 *       ANALYSIS_UNAVAILABLE(예산 제외)이다.</li>
 * </ul>
 * 전이가 "PROCESSING일 때만"의 조건부 UPDATE라, 정리 직후 늦게 도착한 워커의 결과는
 * 조용히 버려진다 ({@link AnalysisJobTransitions}) - 살아 있는 워커와의 경합은 안전하다.
 */
@Component
public class AnalysisJobTimeout {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobTimeout.class);

    private final AnalysisJobRepository repository;
    private final AccenturyProperties properties;
    private final AnalysisMetrics metrics;

    public AnalysisJobTimeout(AnalysisJobRepository repository, AccenturyProperties properties,
                              AnalysisMetrics metrics) {
        this.repository = repository;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(initialDelay = 30, fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void failStuckJobs() {
        // 두 정리는 서로 독립이라 한 트랜잭션으로 묶을 이유가 없고, 묶으면 위험하다.
        // (KAN-107 리뷰 P2) - 첫 벌크 UPDATE의 행 잠금을 쥔 채 두 번째 문장을 실행하면,
        // 재응시 폐기의 세션 단위 벌크 DELETE(잠금 획득 순서가 다른 문장)와 순환 대기가
        // 될 수 있다. 문장마다 자기 트랜잭션으로 끊는다 - 잠금은 문장이 끝나면 풀린다.
        Instant now = Instant.now();
        int stuck = repository.failStartedBefore(
                now.minus(properties.analysis().processingTimeout()),
                ErrorCode.ANALYSIS_TIMEOUT.name(), now);
        int lost = repository.failUnstartedBefore(
                now.minus(properties.analysis().queuedTimeout()),
                ErrorCode.ANALYSIS_UNAVAILABLE.name(), now);
        // 지표는 로그와 달리 0건도 지난다 - 카운터는 증가분만 올리므로 0을 더해도 값이 그대로다.
        // 두 사유를 태그로 가르는 이유는 대응이 다르기 때문이다 (KAN-38): 실행 잔류는 워커나
        // AI 쪽이고, 큐 유실은 프로세스가 죽은 흔적이다.
        metrics.recordTimeouts(stuck, lost);
        if (stuck > 0 || lost > 0) {
            log.warn("PROCESSING 잔류 작업 종결 - 실행 잔류 {}건, 큐 유실 {}건", stuck, lost);
        }
    }
}
