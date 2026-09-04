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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 상태 조회와 문항 대표 상태 선정 (KAN-24, API 명세서 §3.4, §5.1).
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
     * 싣는 것은 세션 세트의 음성 5문항뿐이다 (KAN-182) - 풀의 다른 문항은 이 세션의 것이 아니다.
     */
    @Transactional(readOnly = true)
    public AnalysisStatusResponse statuses(String sessionId, @Nullable String authorization) {
        TestSession session = sessionService.authenticateBearer(sessionId, authorization);
        Map<String, List<AnalysisJob>> attemptsByItem = attemptsByItem(session.id());

        List<AnalysisStatusResponse.Item> items = new ArrayList<>();
        TestDefinition definition = registry.sessionDefinition(session.testVersion(), session.voiceSet());
        for (TestDefinition.Item item : definition.items()) {
            if (item.type() != TestDefinition.ItemType.VOICE) {
                continue; // 어휘 문항은 분석 대상이 아니다 (§3.5 - AI를 거치지 않는다).
            }
            List<AnalysisJob> attempts = attemptsByItem.get(item.itemId());
            items.add(attempts == null
                    ? AnalysisStatusResponse.Item.notSubmitted(item.itemId())
                    : AnalysisStatusResponse.Item.from(representative(attempts)));
        }
        return new AnalysisStatusResponse(pollIntervals.pollAfterMs(), items);
    }

    /**
     * 시도(작업) 1건의 상태 조회 (§3.4) - 완료면 모델과 점수 버전을 함께 반환한다.
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
     * 문항별 대표 상태 작업 - {@code /complete}(KAN-16)의 완주 판정 입력이다.
     * <p>
     * 완주 판정이 이 대표 상태를 그대로 쓰므로 대기 화면(§3.4)과 {@code /complete}(§3.6)가
     * 같은 문항을 다르게 말하는 일이 없다: 대표가 PROCESSING이면 완료 대기, COMPLETED면
     * 그 작업이 곧 채점 대상(최신 성공 시도, §5.1)이고, 실패면 재녹음 대상이다.
     *
     * @return itemId -> 대표 작업. 시도가 없는 문항은 키가 없다.
     */
    @Transactional(readOnly = true)
    public Map<String, AnalysisJob> representativeByItem(String sessionId) {
        Map<String, AnalysisJob> byItem = new LinkedHashMap<>();
        attemptsByItem(sessionId).forEach((itemId, attempts) -> byItem.put(itemId, representative(attempts)));
        return byItem;
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
     * 최신 시도부터 거슬러 올라가 처음 만나는 PROCESSING 또는 COMPLETED가 대표다 -
     * 실패는 건너뛴다. 전부 실패면 최신 시도의 실패 상태다.
     * <ul>
     *   <li>성공보다 <b>새로운</b> 시도가 아직 분석 중이면 성공이 아니라 그 시도가 대표다
     *       (PROCESSING) - 그 결과가 채점 대상(최신 성공, §5.1)을 갈아치울 수 있으므로,
     *       성공을 보고하면 {@code /complete}가 옛 점수로 결과를 영구 확정할 수 있다
     *       (Codex sol 리뷰 P1). 성공이 없을 때 실패를 먼저 보고해 폴링을 멈추게 하면
     *       안 되는 것도 같은 이유다 (Codex sol 리뷰 P2).</li>
     *   <li>성공보다 <b>오래된</b> 시도는 나중에 성공해도 채점 대상(최신 성공)이 될 수
     *       없으므로 기다리지 않는다.</li>
     * </ul>
     */
    private static AnalysisJob representative(List<AnalysisJob> attempts) {
        for (int i = attempts.size() - 1; i >= 0; i--) {
            AnalysisJob attempt = attempts.get(i);
            if (attempt.status() == AnalysisJobStatus.PROCESSING
                    || attempt.status() == AnalysisJobStatus.COMPLETED) {
                return attempt;
            }
        }
        return attempts.getLast();
    }
}
