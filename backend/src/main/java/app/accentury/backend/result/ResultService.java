package app.accentury.backend.result;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import app.accentury.backend.common.ItemsApiException;
import app.accentury.backend.session.SessionService;
import app.accentury.backend.session.TestSession;
import app.accentury.backend.testdefinition.TestDefinition;
import app.accentury.backend.testdefinition.TestDefinitionRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 최종 결과 조회 (KAN-25, API 명세서 §3.7).
 * <p>
 * 읽기 전용이다 - 결과의 영속화는 {@code /complete}(KAN-16)의 몫이고, 이 서비스는
 * 어떤 경로에서도 결과를 만들거나 바꾸지 않는다 (2026-08-13 확정 - KAN-25는 조회만).
 * 그래서 세션 행 잠금도 잡지 않는다: 판정과 응답 사이에 제출이 끼어들어도 잘못
 * 확정되는 것이 없고, 다음 조회가 새 상태를 그대로 보여준다.
 * <p>
 * 미완료 세션의 판정은 {@code /complete}와 공용({@link CompletionJudge}, 2026-08-14
 * 확정)이라 우선순위도 같다: 미제출(422) > 실패(409) > 분석 중(409). 분석 중만
 * 코드가 다르다 - {@code /complete}는 완료 시도의 200 PROCESSING이고, 여기는 아직
 * 결과가 없다는 409 RESULT_NOT_READY다 (§3.7).
 */
@Service
public class ResultService {

    private final SessionService sessionService;
    private final TestDefinitionRegistry registry;
    private final CompletionJudge judge;
    private final TestResultRepository resultRepository;
    private final TierAssets tierAssets;

    public ResultService(SessionService sessionService, TestDefinitionRegistry registry,
                         CompletionJudge judge, TestResultRepository resultRepository,
                         TierAssets tierAssets) {
        this.sessionService = sessionService;
        this.registry = registry;
        this.judge = judge;
        this.resultRepository = resultRepository;
        this.tierAssets = tierAssets;
    }

    /**
     * public이어야 한다 - 프록시 기반 트랜잭션은 public 메서드의 {@code @Transactional}만
     * 읽으므로(AnnotationTransactionAttributeSource의 publicMethodsOnly 기본값 true),
     * 패키지 전용으로 두면 readOnly 트랜잭션이 조용히 적용되지 않는다.
     */
    @Transactional(readOnly = true)
    public ResultResponse result(String sessionId, @Nullable String authorization) {
        // /result 전용 인증 - 완료된 세션은 만료 후에도 통과시켜 410이 401보다 먼저 선다 (KAN-25).
        TestSession session = sessionService.authenticateBearerForResult(sessionId, authorization);

        if (session.isCompleted()) {
            // 완료 세션의 결과 부재는 만료 정리가 먼저 지운 것이다 - 저장은 완료 전이와 같은
            // 트랜잭션이라(KAN-16) 그 외의 부재는 없다. 행이 남아 있어도 만료면 같은 410이다 (§5.5).
            TestResult result = resultRepository.findBySessionId(session.id())
                    .filter(found -> !found.isExpired(Instant.now()))
                    .orElseThrow(() -> new ApiException(ErrorCode.RESULT_EXPIRED));
            return ResultResponse.of(result, tierAssets.assetFor(result.tierCode()),
                    tierAssets.webTestUrl());
        }

        // 미완료 세션 - 만료는 인증이 이미 401로 걸렀으니 여기는 진행 중 세션뿐이다.
        TestDefinition definition = registry.get(session.testVersion()).definition();
        CompletionJudge.Judgment judgment = judge.judge(session.id(), definition);
        if (!judgment.missingItems().isEmpty()) {
            throw new ItemsApiException(ErrorCode.RESULT_INCOMPLETE,
                    ItemsApiException.ItemsField.MISSING_ITEMS, judgment.missingItems());
        }
        if (!judgment.retakeItems().isEmpty()) {
            throw new ItemsApiException(ErrorCode.RESULT_RETAKE_REQUIRED,
                    ItemsApiException.ItemsField.RETAKE_ITEMS, judgment.retakeItems());
        }
        // 분석 중이거나, 전부 갖춰졌지만 /complete가 아직 확정하지 않은 세션이다. 후자도
        // 여기서 결과를 만들지 않고 NOT_READY로 낸다 (조회는 만들지 않는다) - pendingItems가
        // 빈 목록이면 클라이언트가 기다릴 문항이 없다는 뜻 그대로이고, 완료 확정은 §5.7
        // 흐름대로 /complete 폴링이 맡는다.
        throw new ItemsApiException(ErrorCode.RESULT_NOT_READY,
                ItemsApiException.ItemsField.PENDING_ITEMS, judgment.pendingItems());
    }
}
