package app.accentury.backend.result;

import app.accentury.backend.analysis.AnalysisJob;
import app.accentury.backend.analysis.AnalysisStatusService;
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
import app.accentury.backend.vocab.VocabAnswer;
import app.accentury.backend.vocab.VocabAnswerRepository;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final AnalysisStatusService analysisStatusService;
    private final VocabAnswerRepository vocabAnswerRepository;
    private final TestResultRepository resultRepository;
    private final TestSessionRepository sessionRepository;
    private final ScoreAggregator aggregator;
    private final CompleteRateLimiter rateLimiter;
    private final PollIntervals pollIntervals;
    private final AccenturyProperties properties;
    private final TransactionTemplate transactionTemplate;

    public CompletionService(SessionService sessionService, TestDefinitionRegistry registry,
                             AnalysisStatusService analysisStatusService,
                             VocabAnswerRepository vocabAnswerRepository,
                             TestResultRepository resultRepository,
                             TestSessionRepository sessionRepository,
                             ScoreAggregator aggregator, CompleteRateLimiter rateLimiter,
                             PollIntervals pollIntervals, AccenturyProperties properties,
                             TransactionTemplate transactionTemplate) {
        this.sessionService = sessionService;
        this.registry = registry;
        this.analysisStatusService = analysisStatusService;
        this.vocabAnswerRepository = vocabAnswerRepository;
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
     * <p>
     * 문항 순회는 정의의 seq 순서라 응답의 문항 목록도 항상 그 순서다. 음성 문항의
     * 판정은 대표 상태({@link AnalysisStatusService#representativeByItem}) 그대로다 -
     * 대기 화면(§3.4)과 완료 판정이 같은 문항을 다르게 말하지 않고, 대표가 COMPLETED인
     * 작업이 곧 채점 대상(최신 성공 시도, §5.1)이라 별도 선정도 필요 없다.
     */
    private CompleteResponse verifyAndFinalize(TestSession session, long pollAfterMs) {
        TestDefinition definition = registry.get(session.testVersion()).definition();
        Map<String, AnalysisJob> representatives = analysisStatusService.representativeByItem(session.id());
        Map<String, VocabAnswer> answers = vocabAnswerRepository.findBySessionId(session.id()).stream()
                .collect(Collectors.toMap(VocabAnswer::itemId, Function.identity()));

        List<String> missing = new ArrayList<>();
        List<String> retake = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        Map<String, Integer> intonationScoreByItem = new HashMap<>();
        Map<String, String> chosenChoiceIdByItem = new HashMap<>();
        for (TestDefinition.Item item : definition.items()) {
            switch (item.type()) {
                case VOICE -> {
                    AnalysisJob representative = representatives.get(item.itemId());
                    if (representative == null) {
                        missing.add(item.itemId()); // 업로드된 시도가 없다 (§5.1 - 로컬 재녹음은 시도가 아니다)
                    } else {
                        switch (representative.status()) {
                            case PROCESSING -> pending.add(item.itemId());
                            // COMPLETED의 점수가 null이면 데이터 오염이다 - 집계가 크게 실패한다 (KAN-21)
                            case COMPLETED -> intonationScoreByItem.put(item.itemId(),
                                    representative.intonationScore());
                            // FAILED(재녹음 무익)도 retake로 묶는다 (2026-08-13 확정, Codex sol 리뷰
                            // P2 기각) - §3.7이 실패 종류를 구분하지 않고, 새 시도가 세션 내 유일한
                            // 복구 경로다. 문항별 retryable의 정본은 §3.4 상태 조회이고, FAILED가
                            // 반복되면 시도 상한(§2.5) → 429 → 재응시(§3.1)로 수렴한다
                            case RETRYABLE_FAILED, FAILED -> retake.add(item.itemId());
                            // 상태가 추가되면 조용한 문항 누락 대신 여기서 즉시 실패한다
                            default -> throw new IllegalStateException(
                                    "완주 판정 규칙이 없는 분석 상태다: " + representative.status());
                        }
                    }
                }
                case VOCABULARY -> {
                    VocabAnswer answer = answers.get(item.itemId());
                    if (answer == null) {
                        missing.add(item.itemId());
                    } else {
                        chosenChoiceIdByItem.put(item.itemId(), answer.choiceId());
                    }
                }
            }
        }

        // 우선순위: 미제출 > 실패 > 분석 중 (2026-08-13 확정) - 아래 주석은 각 갈래의 §3.6 계약
        if (!missing.isEmpty()) {
            // 422 - 제출부터 해야 한다. 건너뛰기가 없으므로(§5.6) 재시도해도 안 바뀐다 (retryable=false)
            throw new ItemsApiException(ErrorCode.RESULT_INCOMPLETE,
                    ItemsApiException.ItemsField.MISSING_ITEMS, missing);
        }
        if (!retake.isEmpty()) {
            // 409 - 성공도 진행 중도 없는 문항은 기다려도 안 바뀐다. 재녹음(새 시도)으로만 풀린다 (§3.7과 같은 코드)
            throw new ItemsApiException(ErrorCode.RESULT_RETAKE_REQUIRED,
                    ItemsApiException.ItemsField.RETAKE_ITEMS, retake);
        }
        if (!pending.isEmpty()) {
            return CompleteResponse.processing(pending, pollAfterMs);
        }

        AggregateScore score = aggregator.aggregate(
                session.scoreVersion(), definition, intonationScoreByItem, chosenChoiceIdByItem);
        Instant now = Instant.now();
        resultRepository.save(new TestResult("r_" + UUID.randomUUID(), session.id(),
                session.testVersion(), score.scoreVersion(),
                score.intonation(), score.vocabulary(), score.overall(),
                score.tier().code(), score.tier().name(), score.tier().rank(), score.tierCount(),
                now, now.plus(properties.analysis().retention())));
        // 결과 저장과 같은 트랜잭션이다 - 완료 전이만 커밋되고 결과가 없는 상태는 존재하지 않는다
        session.markCompleted(now);
        return CompleteResponse.ready();
    }
}
