package app.accentury.backend.analysis;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import app.accentury.backend.session.SessionService;
import app.accentury.backend.session.TestSession;
import app.accentury.backend.testdefinition.TestDefinition;
import app.accentury.backend.testdefinition.TestDefinitionRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 상태 조회와 채점 대상 선정 (KAN-24, API 명세서 §3.4, §5.1).
 * <p>
 * 폴링 경로다 - 인덱스를 타는 세션 단위 조회 한 번과 메모리 접기만 있고,
 * AI 호출이나 무거운 연산은 없다 (§5.3 규칙 6, KAN-24 AC).
 */
@Service
public class AnalysisStatusService {

    private final SessionService sessionService;
    private final TestDefinitionRegistry registry;
    private final AnalysisJobRepository repository;
    private final PollIntervals pollIntervals;

    public AnalysisStatusService(SessionService sessionService, TestDefinitionRegistry registry,
                                 AnalysisJobRepository repository, PollIntervals pollIntervals) {
        this.sessionService = sessionService;
        this.registry = registry;
        this.repository = repository;
        this.pollIntervals = pollIntervals;
    }

    /**
     * 전체 음성 문항 상태 일괄 조회 (§3.4) - 분석 대기 화면(KAN-14)이 문항 수만큼
     * 폴링하지 않게 하는 엔드포인트다. 시도가 없는 문항도 NOT_SUBMITTED로 실린다.
     */
    @Transactional(readOnly = true)
    public AnalysisStatusResponse statuses(String sessionId, @Nullable String authorization) {
        TestSession session = sessionService.authenticateBearer(sessionId, authorization);
        Map<String, List<AnalysisJob>> attemptsByItem = attemptsByItem(session.id());

        List<AnalysisStatusResponse.Item> items = new ArrayList<>();
        for (TestDefinition.Item item : registry.get(session.testVersion()).definition().items()) {
            if (item.type() != TestDefinition.ItemType.VOICE) {
                continue; // 어휘 문항은 분석 대상이 아니다 (§3.5 - AI를 거치지 않는다)
            }
            List<AnalysisJob> attempts = attemptsByItem.get(item.itemId());
            items.add(attempts == null
                    ? AnalysisStatusResponse.Item.notSubmitted(item.itemId())
                    : AnalysisStatusResponse.Item.from(representative(attempts)));
        }
        return new AnalysisStatusResponse(pollIntervals.pollAfterMs(), items);
    }

    /**
     * 시도(작업) 1건의 상태 조회 (§3.4) - 완료면 모델·점수 버전을 함께 반환한다.
     * 다른 세션의 작업과 없는 작업은 같은 404다 - 작업 ID의 존재 여부를 세션 밖으로
     * 흘리지 않는다 (§2.1의 세션 격리와 같은 취지).
     */
    @Transactional(readOnly = true)
    public AnalysisJobStatusResponse status(String sessionId, String jobId, @Nullable String authorization) {
        TestSession session = sessionService.authenticateBearer(sessionId, authorization);
        AnalysisJob job = repository.findById(jobId)
                .filter(found -> found.sessionId().equals(session.id()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        return AnalysisJobStatusResponse.from(job, pollIntervals.pollAfterMs());
    }

    /**
     * 문항별 채점 대상 = 최신 성공(COMPLETED) 시도 1건 (§5.1, KAN-24 AC).
     * 이전 성공과 실패 시도는 집계에서 제외된다 - 이 규칙이 없으면 "음성 5문항인데
     * 결과 7개" 같은 집계 버그가 난다. 최종 결과 조합(KAN-25)과 집계(KAN-21)의 입력이다.
     *
     * @return itemId -> 채점 대상 작업. 성공 시도가 없는 문항은 키가 없다
     */
    @Transactional(readOnly = true)
    public Map<String, AnalysisJob> scoringTargets(String sessionId) {
        Map<String, AnalysisJob> targets = new HashMap<>();
        for (AnalysisJob job : repository.findBySessionIdOrderByCreatedAtAscAttemptAsc(sessionId)) {
            if (job.status() == AnalysisJobStatus.COMPLETED) {
                targets.put(job.itemId(), job); // 오름차순 순회라 마지막 성공이 남는다
            }
        }
        return targets;
    }

    /** 문항의 시도 이력 - 시간 오름차순 (repository 정렬 유지) */
    private Map<String, List<AnalysisJob>> attemptsByItem(String sessionId) {
        Map<String, List<AnalysisJob>> byItem = new LinkedHashMap<>();
        for (AnalysisJob job : repository.findBySessionIdOrderByCreatedAtAscAttemptAsc(sessionId)) {
            byItem.computeIfAbsent(job.itemId(), key -> new ArrayList<>()).add(job);
        }
        return byItem;
    }

    /**
     * 시도 여럿을 문항 대표 상태 하나로 접는다 - 규칙은 {@link AnalysisStatusResponse.Item} 참조.
     * 우선순위: 최신 시도의 PROCESSING > 최신 COMPLETED > 아직 도는 이전 시도의 PROCESSING >
     * 최신 시도. 성공이 없는데 어떤 시도든 아직 분석 중이면 그것이 채점 대상이 될 수 있으므로,
     * 실패를 먼저 보고해 폴링을 멈추고 불필요한 재녹음을 유도하면 안 된다 (Codex sol 리뷰 P2).
     */
    private static AnalysisJob representative(List<AnalysisJob> attempts) {
        AnalysisJob latest = attempts.getLast();
        if (latest.status() == AnalysisJobStatus.PROCESSING) {
            return latest;
        }
        AnalysisJob inFlight = null;
        for (int i = attempts.size() - 1; i >= 0; i--) {
            AnalysisJob attempt = attempts.get(i);
            if (attempt.status() == AnalysisJobStatus.COMPLETED) {
                return attempt;
            }
            if (inFlight == null && attempt.status() == AnalysisJobStatus.PROCESSING) {
                inFlight = attempt;
            }
        }
        return inFlight != null ? inFlight : latest;
    }
}
