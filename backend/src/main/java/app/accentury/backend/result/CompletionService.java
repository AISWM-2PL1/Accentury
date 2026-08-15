package app.accentury.backend.result;

import app.accentury.backend.analysis.PollIntervals;
import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import app.accentury.backend.common.IdempotencyKeys;
import app.accentury.backend.common.ItemsApiException;
import app.accentury.backend.scoring.AggregateScore;
import app.accentury.backend.scoring.ScoreAggregator;
import app.accentury.backend.session.SessionService;
import app.accentury.backend.session.TestSession;
import app.accentury.backend.session.TestSessionRepository;
import app.accentury.backend.testdefinition.TestDefinition;
import app.accentury.backend.testdefinition.TestDefinitionRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 완료 검증과 결과 확정 (KAN-16, API 명세서 §3.6).
 * <p>
 * 검증 순서: 세션 인증 -> 멱등 키 -> 요청 제한 -> (잠금) 만료/완료 재확인 -> 완주 판정 -> 집계/저장.
 * 완주 판정의 우선순위는 미제출(422) > 실패(409) > 분석 중(200 PROCESSING)이다
 * (2026-08-13 확정) - 실패 문항은 기다려도 안 바뀌므로, 다른 문항의 분석을 기다리는
 * 동안 사용자가 재녹음을 시작할 수 있게 실패를 먼저 알린다.
 * <p>
 * 완료 전이(집계, 결과 저장, {@code completed_at})는 제출 경로(KAN-15/23)와 같은
 * 세션 행 잠금({@link TestSessionRepository#lockById}) 아래 한 트랜잭션이다 - 잠금이
 * 없으면 완주 판정과 확정 사이에 답안/업로드가 끼어들어 결과에 안 담긴 제출이 생긴다.
 */
@Service
public class CompletionService {

    private static final Logger log = LoggerFactory.getLogger(CompletionService.class);

    private final SessionService sessionService;
    private final TestDefinitionRegistry registry;
    private final CompletionJudge judge;
    private final TestResultRepository resultRepository;
    private final TestSessionRepository sessionRepository;
    private final ScoreAggregator aggregator;
    private final CompleteRateLimiter rateLimiter;
    private final PollIntervals pollIntervals;
    private final AccenturyProperties properties;
    private final TransactionTemplate transactionTemplate;

    public CompletionService(SessionService sessionService, TestDefinitionRegistry registry,
                             CompletionJudge judge,
                             TestResultRepository resultRepository,
                             TestSessionRepository sessionRepository,
                             ScoreAggregator aggregator, CompleteRateLimiter rateLimiter,
                             PollIntervals pollIntervals, AccenturyProperties properties,
                             TransactionTemplate transactionTemplate) {
        this.sessionService = sessionService;
        this.registry = registry;
        this.judge = judge;
        this.resultRepository = resultRepository;
        this.sessionRepository = sessionRepository;
        this.aggregator = aggregator;
        this.rateLimiter = rateLimiter;
        this.pollIntervals = pollIntervals;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    /** 트랜잭션의 반환값 - 로그는 커밋이 확정된 뒤에만 남기려고 확정 여부를 함께 나른다 */
    private record Outcome(CompleteResponse response, boolean completedNow) {
    }

    CompleteResponse complete(String sessionId, @Nullable String authorization,
                              @Nullable String idempotencyKey) {
        TestSession session = sessionService.authenticateBearer(sessionId, authorization);
        // 완료는 자연 멱등이라(완료 후 재시도 = READY 재확인) 키 값 대조는 없지만,
        // 비용 발생 POST의 계약(§2.2 - 업로드/답안/완료)이므로 존재는 강제한다
        IdempotencyKeys.require(idempotencyKey);
        // 인증 뒤, 잠금 전 - 폭주 폴링이 세션 행 잠금까지 도달하지 못하게 끊는다 (KAN-16 AC)
        rateLimiter.check(session.id());

        long pollAfterMs = pollIntervals.pollAfterMs();
        Outcome outcome = Objects.requireNonNull(transactionTemplate.execute(tx -> {
            // 잠금 재조회가 빈 것은 동시 삭제(만료 정리)를 뜻하므로 만료와 같게 응답한다
            TestSession locked = sessionRepository.lockById(session.id())
                    .orElseThrow(() -> new ApiException(ErrorCode.SESSION_EXPIRED));
            // 만료도 잠금 아래에서 재확인한다 - 제출 경로(KAN-15/23)와 같은 이유의 재검사다
            if (locked.isExpired(Instant.now())) {
                throw new ApiException(ErrorCode.SESSION_EXPIRED);
            }
            // 완료 재시도는 READY를 다시 준다 - 결과를 중복 생성하지 않는다 (AC, §3.6)
            if (locked.isCompleted()) {
                return new Outcome(CompleteResponse.ready(), false);
            }
            return new Outcome(verifyAndFinalize(locked, pollAfterMs), true);
        }));

        if (outcome.completedNow() && outcome.response().status() == CompleteResponse.Status.READY) {
            // 점수와 등급은 로그에 남기지 않는다 - 결과 공개는 /result 한 곳이다 (§2.6의 취지)
            log.info("테스트 완료 sessionId={} testVersion={} scoreVersion={}",
                    session.id(), session.testVersion(), session.scoreVersion());
        }
        return outcome.response();
    }

    /**
     * 완주 판정과 결과 확정 - 세션 행 잠금 아래에서만 호출된다.
     * 판정 자체는 {@code /result}(KAN-25)와 공용이다 ({@link CompletionJudge}).
     */
    private CompleteResponse verifyAndFinalize(TestSession session, long pollAfterMs) {
        TestDefinition definition = registry.get(session.testVersion()).definition();
        CompletionJudge.Judgment judgment = judge.judge(session.id(), definition);

        // 우선순위: 미제출 > 실패 > 분석 중 (2026-08-13 확정) - 아래 주석은 각 갈래의 §3.6 계약
        if (!judgment.missingItems().isEmpty()) {
            // 422 - 제출부터 해야 한다. 건너뛰기가 없으므로(§5.6) 재시도해도 안 바뀐다 (retryable=false)
            throw new ItemsApiException(ErrorCode.RESULT_INCOMPLETE,
                    ItemsApiException.ItemsField.MISSING_ITEMS, judgment.missingItems());
        }
        if (!judgment.retakeItems().isEmpty()) {
            // 409 - 성공도 진행 중도 없는 문항은 기다려도 안 바뀐다. 재녹음(새 시도)으로만 풀린다 (§3.7과 같은 코드)
            throw new ItemsApiException(ErrorCode.RESULT_RETAKE_REQUIRED,
                    ItemsApiException.ItemsField.RETAKE_ITEMS, judgment.retakeItems());
        }
        if (!judgment.pendingItems().isEmpty()) {
            return CompleteResponse.processing(judgment.pendingItems(), pollAfterMs);
        }

        AggregateScore score = aggregator.aggregate(session.scoreVersion(), definition,
                judgment.intonationScoreByItem(), judgment.chosenChoiceIdByItem());
        Instant now = Instant.now();
        Instant resultExpiresAt = now.plus(properties.analysis().retention());
        resultRepository.save(new TestResult("r_" + UUID.randomUUID(), session.id(),
                session.testVersion(), score.scoreVersion(),
                score.intonation(), score.vocabulary(), score.overall(),
                score.tier().code(), score.tier().name(), score.tier().rank(), score.tierCount(),
                now, resultExpiresAt));
        // 결과 저장과 같은 트랜잭션이다 - 완료 전이만 커밋되고 결과가 없는 상태는 존재하지
        // 않는다. 세션 수명은 결과 수명까지 늘어난다 - 재조회와 410 안내의 전제 (KAN-25)
        session.markCompleted(now, resultExpiresAt);
        return CompleteResponse.ready();
    }
}
