package app.accentury.backend.vocab;

import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import app.accentury.backend.common.IdempotencyKeys;
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
import java.util.UUID;

/**
 * 어휘 답안의 검증 파이프라인과 저장 (KAN-15, API 명세서 §3.5).
 * <p>
 * 검증 순서: 세션 인증 -> 멱등 키 -> 문항/선택지 -> (잠금) 완료 가드 -> 멱등 판별 -> 저장.
 * AI를 거치지 않는다 - 정답표 대조는 저장 시점에 끝난다 (§4.3, §5.7).
 * <p>
 * 완료 검사부터 저장까지는 세션 행 잠금 아래 한 트랜잭션이다 (Codex sol 리뷰 P2) -
 * 잠금이 없으면 검사와 저장 사이에 완료 전이({@code /complete}, KAN-16)가 끼어들어
 * 확정된 세션에 답안이 추가된다. 같은 문항의 동시 제출도 이 잠금으로 직렬화되므로
 * (session_id, item_id) 유니크 제약은 마지막 안전망이다.
 */
@Service
public class VocabAnswerService {

    private static final Logger log = LoggerFactory.getLogger(VocabAnswerService.class);

    private final SessionService sessionService;
    private final TestDefinitionRegistry registry;
    private final VocabAnswerRepository repository;
    private final AnalysisJobRepository analysisJobRepository;
    private final TestSessionRepository sessionRepository;
    private final TransactionTemplate transactionTemplate;

    public VocabAnswerService(SessionService sessionService, TestDefinitionRegistry registry,
                              VocabAnswerRepository repository, AnalysisJobRepository analysisJobRepository,
                              TestSessionRepository sessionRepository, TransactionTemplate transactionTemplate) {
        this.sessionService = sessionService;
        this.registry = registry;
        this.repository = repository;
        this.analysisJobRepository = analysisJobRepository;
        this.sessionRepository = sessionRepository;
        this.transactionTemplate = transactionTemplate;
    }

    VocabAnswerResponse submit(String sessionId, String itemId,
                               @Nullable String authorization, @Nullable String idempotencyKey,
                               @Nullable VocabAnswerRequest request) {
        TestSession session = sessionService.authenticateBearer(sessionId, authorization);
        String key = IdempotencyKeys.require(idempotencyKey);
        // 없는 문항(422)과 음성 문항(409)을 여기서 끊는다 (§3.5, KAN-23과 공용 규칙)
        TestDefinition.Item item = registry.requireItem(
                session.testVersion(), itemId, TestDefinition.ItemType.VOCABULARY);
        String choiceId = requireChoiceId(request);
        // 이 문항의 선택지가 아니면 거절한다 - 같은 버전의 다른 문항 선택지도 포함 (§3.5)
        boolean known = item.choices() != null && item.choices().stream()
                .anyMatch(choice -> choice.choiceId().equals(choiceId));
        if (!known) {
            throw new ApiException(ErrorCode.ITEM_NOT_IN_VERSION, "이 문항의 선택지가 아닙니다.");
        }

        boolean savedNew = Boolean.TRUE.equals(transactionTemplate.execute(tx -> {
            // 완료 가드와 저장을 세션 행 잠금으로 묶는다 - 인증 시점의 스냅샷으로 검사하면
            // 그 사이 커밋된 /complete를 놓친다. 잠금 재조회가 빈 것은 동시 삭제(만료
            // 정리)를 뜻하므로 만료와 같게 응답한다
            TestSession locked = sessionRepository.lockById(session.id())
                    .orElseThrow(() -> new ApiException(ErrorCode.SESSION_EXPIRED));
            // 만료도 잠금 아래에서 재확인한다 - 인증(스냅샷 검사)과 잠금 획득 사이에 TTL이
            // 지나면 만료된 세션에 답안이 저장된다. 완료 가드와 같은 이유의 재검사다
            if (locked.isExpired(Instant.now())) {
                throw new ApiException(ErrorCode.SESSION_EXPIRED);
            }
            // 완료 가드가 멱등 판별보다 먼저다 - 완료 뒤에는 같은 키의 재전송도 200이 아니라
            // 409를 받는다. "같은 결과 반환"(§3.5)은 진행 중 세션의 계약으로 좁혀 읽는다
            if (locked.isCompleted()) {
                throw new ApiException(ErrorCode.SESSION_COMPLETED);
            }
            var existing = repository.findBySessionIdAndItemId(session.id(), itemId);
            if (existing.isPresent()) {
                requireSameReplay(existing.get(), key, choiceId);
                return false;
            }
            // 정오는 저장 시점에 확정한다 - 정의가 불변이라(§5.4) 나중에 대조해도 같지만,
            // /complete(KAN-16)가 정답표를 다시 뒤지지 않고 이 행만 세면 되게 한다 (§4.3)
            repository.save(new VocabAnswer("va_" + UUID.randomUUID(), session.id(), itemId,
                    choiceId, choiceId.equals(item.correctChoiceId()), key, Instant.now()));
            return true;
        }));

        if (savedNew) {
            // 답안 내용(choiceId/정오)은 로그에 남기지 않는다 (§2.6의 취지 - 결과 유추 차단)
            log.info("어휘 답안 저장 sessionId={} itemId={}", session.id(), itemId);
        }
        return response(session);
    }

    /**
     * 이미 답안이 있는 문항의 처리 - 같은 키의 동일 요청만 재전송으로 인정한다 (§5.2).
     * <ul>
     *   <li>같은 키 + 같은 선택지 → 통과, 200 (중복 저장 없음 - AC "같은 결과를 반환")</li>
     *   <li>같은 키 + 다른 선택지 → 400 (키 오용 - 새 답에 새 키를 쓰는 §5.2 규칙과 동일)</li>
     *   <li>다른 키 → 409 ITEM_ALREADY_ANSWERED (재제출 거절, 2026-08-11 확정 -
     *       확정 플로우(§5.7)에 답 변경이 없다)</li>
     * </ul>
     */
    private static void requireSameReplay(VocabAnswer stored, String key, String choiceId) {
        if (!stored.idempotencyKey().equals(key)) {
            throw new ApiException(ErrorCode.ITEM_ALREADY_ANSWERED);
        }
        if (!stored.choiceId().equals(choiceId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "같은 Idempotency-Key로 다른 답이 제출되었습니다.");
        }
    }

    /**
     * 진행도 응답 (§3.5) - 어휘는 답안 저장, 음성은 업로드 시도 1건 이상을 제출로 센다
     * (2026-08-11 확정). 재전송 응답의 진행도는 저장 시점이 아니라 현재 값이다 -
     * "같은 결과"(AC)는 답안 수락에 대한 계약이고 진행도는 정보성 필드다.
     */
    private VocabAnswerResponse response(TestSession session) {
        long answered = repository.countBySessionId(session.id())
                + analysisJobRepository.countDistinctSubmittedItems(session.id());
        int total = registry.get(session.testVersion()).definition().items().size();
        return new VocabAnswerResponse(true, (int) answered, total);
    }

    private static String requireChoiceId(@Nullable VocabAnswerRequest request) {
        if (request == null || request.choiceId() == null || request.choiceId().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "choiceId가 필요합니다.");
        }
        return request.choiceId();
    }
}
