package app.accentury.backend.feedback;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import app.accentury.backend.common.IdempotencyKeys;
import app.accentury.backend.common.RateLimits;
import app.accentury.backend.result.TestResult;
import app.accentury.backend.result.TestResultRepository;
import app.accentury.backend.session.SessionService;
import app.accentury.backend.session.TestSession;
import app.accentury.backend.session.TestSessionRepository;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 이용 후기의 검증 파이프라인과 저장 (KAN-211, 2026-09-15 결정).
 * <p>
 * 검증 순서: 세션 인증 -> 세션 요청 제한 -> 멱등 키 -> 본문 -> (잠금) 완료 가드 ->
 * 멱등 판별 -> 결과 스냅샷 -> 저장. 완료 검사부터 저장까지는 세션 행 잠금 아래 한
 * 트랜잭션이다 ({@code VocabAnswerService}와 같은 이유) - 잠금이 없으면 같은 세션의 동시
 * 제출 둘이 나란히 "기존 행 없음"을 읽고 둘 다 저장을 시도해, 계약상 409여야 할 요청이
 * 유니크 제약 위반(500)으로 끝난다.
 * <p>
 * 저장하는 것은 입력 세 가지(별점, 본문, 이메일)와 세션/결과 스냅샷이다 - 스냅샷을 왜 복사하는지는
 * {@link SessionFeedback}의 javadoc이 정본이다. 본문과 이메일은 어떤 로그에도 남기지 않는다 (§2.6).
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    /** 본문 길이 상한 - {@code session_feedback.body} 컬럼 길이와 같다 (넘으면 400이 아니라 500이 된다). */
    static final int MAX_BODY_LENGTH = 500;

    /** 이메일 길이 상한 - 컬럼 길이이자 주소의 실질 상한(RFC 5321의 forward-path 256에서 꺾쇠 둘을 뺀 값)이다. */
    static final int MAX_EMAIL_LENGTH = 254;

    /**
     * 회신 이메일의 형식 검사 - "골뱅이 하나와 점 하나가 있는 한 덩어리"까지만 본다.
     * <p>
     * 엄밀한 RFC 5322 검증을 하지 않는 것은 의도다. 이 값의 쓰임은 개발팀이 답장을 보내는 것
     * 하나뿐이라, 형식이 맞는데 거절당하는 쪽(정상 주소를 놓친다)이 형식이 틀린 값 하나를
     * 그냥 저장하는 쪽보다 나쁘다. 오타는 어차피 검증으로 걸러지지 않는다.
     */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final SessionService sessionService;
    private final SessionFeedbackRepository repository;
    private final TestResultRepository testResultRepository;
    private final TestSessionRepository sessionRepository;
    private final TransactionTemplate transactionTemplate;
    private final RateLimits rateLimits;

    public FeedbackService(SessionService sessionService, SessionFeedbackRepository repository,
                           TestResultRepository testResultRepository,
                           TestSessionRepository sessionRepository,
                           TransactionTemplate transactionTemplate, RateLimits rateLimits) {
        this.sessionService = sessionService;
        this.repository = repository;
        this.testResultRepository = testResultRepository;
        this.sessionRepository = sessionRepository;
        this.transactionTemplate = transactionTemplate;
        this.rateLimits = rateLimits;
    }

    /** 제출의 산출물 - 새로 저장했는지 여부다. 컨트롤러가 이것으로 201과 200을 가른다. */
    record SubmitOutcome(boolean savedNew) {
    }

    SubmitOutcome submit(String sessionId, @Nullable String authorization,
                         @Nullable String idempotencyKey, @Nullable FeedbackRequest request) {
        // 결과 조회와 같은 인증 규칙이다 (KAN-25의 authenticateBearerForResult).
        // 후기는 결과 화면에서 쓰는데 그 화면은 세션 expiresAt이 지난 뒤에도 열려 있다
        // (완료 세션의 결과는 24시간 보관, §5.5). 여기서 authenticateBearer를 쓰면 결과는
        // 보이는데 후기만 401이 되는 모순이 생긴다.
        TestSession session = sessionService.authenticateBearerForResult(sessionId, authorization);
        // 인증 뒤에만 닿는 경로라 세션이 키다 (§2.5, KAN-28) - 세션당 후기는 하나이므로
        // 그 이상은 재전송이거나 남용이다.
        rateLimits.check(RateLimits.Scope.FEEDBACK, session.id());
        String key = IdempotencyKeys.require(idempotencyKey);
        String body = requireBody(request);
        Short rating = validRating(request);
        String contactEmail = validContactEmail(request);

        SubmitOutcome outcome = Objects.requireNonNull(transactionTemplate.execute(tx -> {
            // 잠금 재조회가 빈 것은 세션 행이 이미 정리됐다는 뜻이다 - 만료와 같게 응답한다
            // (인증이 통과한 것은 그 사이 삭제가 끼어들었다는 뜻이므로).
            TestSession locked = sessionRepository.lockById(session.id())
                    .orElseThrow(() -> new ApiException(ErrorCode.SESSION_EXPIRED));
            // 만료는 여기서 다시 보지 않는다 - 완료된 세션은 만료 뒤에도 결과를 볼 수 있고,
            // 후기는 그 결과를 보고 쓰는 것이다 (인증 규칙과 같은 판단).
            if (!locked.isCompleted()) {
                // 결과를 보고 쓴 후기여야 한다 - 테스트 도중의 후기는 받지 않는다.
                throw new ApiException(ErrorCode.RESULT_NOT_READY);
            }
            var existing = repository.findBySessionId(session.id());
            if (existing.isPresent()) {
                // 같은 키면 재전송이므로 저장 없이 200이다. 어휘 답안(§3.5)과 달리 본문을
                // 대조하지 않는다 - 후기에는 "같은 키로 다른 답"이라는 오용이 없고(고칠 답이
                // 없다), 화면이 재전송 때 본문을 한 글자라도 다시 만들면 400이 나는 편이
                // 사용자에게 훨씬 나쁘다. 같은 키는 같은 요청으로 본다.
                if (!existing.get().idempotencyKey().equals(key)) {
                    throw new ApiException(ErrorCode.FEEDBACK_ALREADY_SUBMITTED);
                }
                return new SubmitOutcome(false);
            }
            // 완료됐는데 결과 행이 없는 것은 이론상의 상태다 (/complete가 한 트랜잭션에서
            // 확정하므로). 그래도 등급 스냅샷 없이 저장하면 나중에 읽을 수 없는 후기가 되므로
            // 완료 전과 같은 409로 돌려보낸다.
            TestResult result = testResultRepository.findBySessionId(session.id())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESULT_NOT_READY));
            repository.save(new SessionFeedback("fb_" + UUID.randomUUID(), session.id(), key,
                    rating, body, contactEmail, result.tierCode(), locked.testVersion(),
                    locked.scoreVersion(), locked.platform(), locked.traffic(), Instant.now()));
            return new SubmitOutcome(true);
        }));

        if (outcome.savedNew()) {
            // 본문과 이메일은 로그에 남기지 않는다 (§2.6) - 후기는 사용자가 쓴 자유 서술이라
            // 무엇이 들어 있을지 알 수 없고, 이메일은 이 서비스가 받는 유일한 개인 식별 정보다.
            // 길이와 연락처 유무만 남긴다 - 저장이 됐는지, 빈 후기가 새고 있지는 않은지를
            // 보는 데는 그것으로 충분하다.
            log.info("후기 저장 sessionId={} rating={} bodyLength={} hasContact={}",
                    session.id(), rating, body.length(), contactEmail != null);
        }
        return outcome;
    }

    /** 본문은 필수다 - 공백만 보낸 것은 안 보낸 것과 같게 본다. 저장은 trim한 값이다. */
    private static String requireBody(@Nullable FeedbackRequest request) {
        if (request == null || request.body() == null || request.body().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "후기 내용을 입력해 주세요.");
        }
        String body = request.body().trim();
        if (body.length() > MAX_BODY_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "후기가 너무 깁니다. (최대 " + MAX_BODY_LENGTH + "자)");
        }
        return body;
    }

    /** 별점은 선택이고, 보냈다면 1~5다. 저장 컬럼이 smallint라 여기서 한 번 좁힌다. */
    private static @Nullable Short validRating(@Nullable FeedbackRequest request) {
        if (request == null || request.rating() == null) {
            return null;
        }
        int rating = request.rating();
        if (rating < 1 || rating > 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "별점은 1에서 5 사이여야 합니다.");
        }
        return (short) rating;
    }

    /** 이메일은 선택이다 - 빈 문자열은 미입력으로 접는다 (폼의 빈 칸을 400으로 돌려줄 이유가 없다). */
    private static @Nullable String validContactEmail(@Nullable FeedbackRequest request) {
        if (request == null || request.contactEmail() == null || request.contactEmail().isBlank()) {
            return null;
        }
        String email = request.contactEmail().trim();
        if (email.length() > MAX_EMAIL_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "이메일 주소가 너무 깁니다. (최대 " + MAX_EMAIL_LENGTH + "자)");
        }
        if (!EMAIL.matcher(email).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "이메일 주소 형식이 올바르지 않습니다.");
        }
        return email;
    }
}
