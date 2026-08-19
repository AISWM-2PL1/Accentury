package app.accentury.backend.session;

import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analytics.AnalyticsCounters;
import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import app.accentury.backend.common.RateLimits;
import app.accentury.backend.result.TestResultRepository;
import app.accentury.backend.testdefinition.TestDefinition;
import app.accentury.backend.testdefinition.TestDefinitionRegistry;
import app.accentury.backend.vocab.VocabAnswerRepository;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 익명 세션의 생성과 인증, 만료 정리, 재응시 즉시 폐기 (KAN-9, KAN-107).
 * <p>
 * 세션 저장소는 PostgreSQL이다 (2026-07-30 확정, §2.1) - {@code expires_at} 컬럼 +
 * 요청 시 만료 검사 + 주기 삭제로 TTL을 구현한다. Redis 전환은 BE 다중 인스턴스
 * 또는 폴링 부하 증가 시점에 검토한다.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final TestSessionRepository repository;
    private final VocabAnswerRepository vocabAnswerRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final TestResultRepository testResultRepository;
    private final TestDefinitionRegistry testDefinitions;
    private final AccenturyProperties properties;
    private final RateLimits rateLimits;
    private final AnalyticsCounters counters;
    private final TransactionTemplate transactionTemplate;

    public SessionService(TestSessionRepository repository,
                          VocabAnswerRepository vocabAnswerRepository,
                          AnalysisJobRepository analysisJobRepository,
                          TestResultRepository testResultRepository,
                          TestDefinitionRegistry testDefinitions,
                          AccenturyProperties properties,
                          RateLimits rateLimits, AnalyticsCounters counters,
                          TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.vocabAnswerRepository = vocabAnswerRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.testResultRepository = testResultRepository;
        this.testDefinitions = testDefinitions;
        this.properties = properties;
        this.rateLimits = rateLimits;
        this.counters = counters;
        // 폐기+생성은 자신이 커밋 지점이어야 한다 (REQUIRES_NEW) - 바깥 트랜잭션에 합류하면
        // "카운터는 커밋 뒤" 불변식이 깨져, 바깥이 롤백해도 존재한 적 없는 세션이 집계에
        // 남는다 (2026-08-17 리뷰). 공용 템플릿 빈의 전파는 바꾸지 않도록 사본을 쓴다.
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 새 익명 세션을 만든다. 재응시도 이 호출이며, 이전 세션과 어떤 이력도 연결하지 않는다 (KAN-9 AC).
     * <p>
     * 인증이 없는 유일한 쓰기 경로라(§2.1) IP 단위 요청 제한을 건다 (§2.5, KAN-28) -
     * 호출 한 번마다 세션 행과 토큰이 생기므로, 막지 않으면 반복 호출만으로 저장소를
     * 채울 수 있다. 검사는 저장보다 먼저다.
     * <p>
     * 재응시(다시 테스트하기)는 이전 세션의 토큰을 {@code Authorization: Bearer}로 함께
     * 보낸다 (KAN-107, FR-TR-04, §3.1) - 이전 세션과 하위 데이터 전부를 즉시 폐기한 뒤 새
     * 세션을 만들고, 폐기와 생성은 한 트랜잭션이다. 삭제만 커밋되고 생성이 실패하면
     * 사용자에게 이전 결과도 새 세션도 없는 상태가 남기 때문이다 (티켓 요구 3).
     * 유효하지 않은 토큰은 조용히 무시한다 - {@link #retakeTokenHash} 참고.
     * <p>
     * 성공한 호출은 익명 집계의 응시 시도 1건이 된다 (KAN-106, FR-AN-10) - 세션과 무관한
     * 숫자만 늘어나고, 그 증가가 실패해도 세션 생성은 성공한다. 재응시 폐기가 있어도
     * 카운터는 되돌리지 않는다 - 이전 시도와 완주는 실제로 발생한 사실이다 (KAN-107 AC).
     * <p>
     * 고정할 두 버전은 활성 정의 스냅샷 <b>한 번</b>에서 함께 꺼낸다 (KAN-26) - 나눠 읽으면
     * 그사이 활성 전환이 끼어들어 한 세션이 A의 문항과 B의 채점식에 걸릴 수 있다. 활성 전환은
     * 이 지점 뒤로는 이 세션에 영향을 주지 않는다 (§5.4, KAN-26 AC).
     *
     * @param clientIp            요청 제한의 기준 IP - {@link app.accentury.backend.common.ClientIps}가
     *                            신뢰 프록시 규칙으로 정한 값
     * @param authorizationHeader 재응시일 때만 실려 오는 이전 세션의 {@code Authorization} 헤더 -
     *                            이 엔드포인트는 인증 불필요이므로(§2.1) 없으면 최초 응시다.
     */
    public SessionResponse create(@Nullable CreateSessionRequest request, String clientIp,
                                  @Nullable String authorizationHeader) {
        rateLimits.check(RateLimits.Scope.SESSION_CREATE, clientIp);

        String previousTokenHash = retakeTokenHash(authorizationHeader);
        TestDefinition active = testDefinitions.active().definition();
        String testVersion = active.testVersion();
        String scoreVersion = active.scoreVersion();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.session().ttl());
        String sessionId = SessionTokens.newSessionId();
        String token = SessionTokens.newToken();

        CreateSessionRequest.Client client = request != null ? request.client() : null;
        @Nullable PurgeSummary purged = transactionTemplate.execute(tx -> {
            PurgeSummary summary = null;
            if (previousTokenHash != null) {
                // 잠금 조회가 빈 것은 모르는 토큰이거나, 동시 재응시(더블탭)에 진 것이거나,
                // 주기 삭제가 세션 행을 이미 지운 뒤다 - 어느 쪽이든 조용히 새 세션만 만든다
                // (마지막 경우의 잔존 데이터는 purgeForRetake javadoc의 수용 한계 참고).
                // 더블탭의 서버 측 방지는 하지 않는다 (2026-08-17 확정 - 피해가 고아 세션
                // 1개와 카운터 1건이라 클라이언트 버튼 방지로 충분하다). 만료됐지만 주기
                // 삭제 전인 세션은 여기서 지운다 - FR-TR-04의 목적이 즉시 파기이므로
                // 만료 여부로 가르지 않는다.
                summary = repository.lockByTokenHash(previousTokenHash)
                        .map(this::purgeForRetake)
                        .orElse(null);
            }
            repository.save(new TestSession(
                    sessionId,
                    SessionTokens.hash(token),
                    testVersion,
                    scoreVersion,
                    client != null && client.platform() != null ? client.platform().name() : null,
                    client != null ? client.appVersion() : null,
                    request != null ? request.campaignToken() : null,
                    now,
                    expiresAt));
            return summary;
        });

        if (purged != null) {
            // 폐기 로그는 커밋이 확정된 뒤에만 남긴다 - 롤백됐는데 파기했다는 거짓 기록이
            // 남으면 안 된다 (로그와 카운터는 커밋 뒤라는 규칙, CompletionService와 같다).
            log.info("재응시로 이전 세션 즉시 폐기 sessionId={} answers={} attempts={} results={}",
                    purged.sessionId(), purged.answers(), purged.attempts(), purged.results());
        }

        // 토큰은 로그에 남기지 않는다 (§2.6, NFR-SC-07).
        log.info("세션 생성 sessionId={} platform={} testVersion={}",
                sessionId, client != null ? client.platform() : null, testVersion);

        // 응시 시도 1건 (KAN-106) - 폐기+생성 트랜잭션이 커밋된 뒤다.
        // 실패는 카운터 쪽에서 삼킨다 - 통계가 세션 생성을 막으면 안 된다 (FR-AN-10).
        counters.recordSessionStarted(now, testVersion, scoreVersion);

        return new SessionResponse(sessionId, token, testVersion, scoreVersion, expiresAt);
    }

    /**
     * 이전 세션과 하위 데이터 전부를 즉시 폐기한다 (KAN-107, FR-TR-04, §5.5).
     * <p>
     * 삭제 대상은 어휘 답안(KAN-15), 음성 시도와 분석 상태 및 문항별 점수 누적분(KAN-24 -
     * {@code analysis_job} 행이 셋을 겸한다), 최종 결과(KAN-25), 그리고 세션 행이다.
     * 멱등 키는 별도 테이블이 아니라 답안/시도 행의 컬럼이므로 함께 사라진다 (§2.2).
     * baseline(KAN-123)이 세션 FK에 ON DELETE CASCADE를 확정한 뒤에도 명시적 벌크
     * 삭제를 유지한다 (2026-08-18 확정) - 잠금 획득 순서를 코드가 통제하고 삭제 건수를
     * 로그로 남긴다. CASCADE는 코드가 삭제를 빠뜨렸을 때의 마지막 안전망이다.
     * <p>
     * 세션 행 잠금({@link TestSessionRepository#lockByTokenHash}) 아래에서만 호출된다 -
     * 제출 쓰기(KAN-15/23)와 완료 전이(KAN-16)가 같은 잠금으로 직렬화되므로, 폐기와
     * 경합한 제출은 잠금 해제 후 세션이 없어 401로 끝나고 고아 행을 남기지 못한다.
     * PROCESSING 시도의 행이 지워져도 늦게 도착한 AI 결과는 조건부 UPDATE 0행으로
     * 버려진다 ({@link app.accentury.backend.analysis.AnalysisJobTransitions}).
     * <p>
     * 새 세션에는 이전 세션을 참조하는 필드를 두지 않는다 (KAN-9 AC - 엔티티 필드
     * 허용 목록 테스트가 지킨다). 폐기 로그는 커밋이 확정된 뒤 {@link #create}가 남긴다 -
     * 로그의 세션 ID는 운영 진단용 단명 기록이고, 영속 저장소에는 연결이 남지 않는다.
     * 세션 하위 테이블을 새로 만들면 여기 삭제 한 줄도 함께 추가해야 한다 - 훅
     * 인터페이스로 의존을 역전하라는 지적(2026-08-17 리뷰)은 기각한다: 프로토타입
     * 범위에서 하위 테이블은 티켓이 나열한 이 셋으로 닫혀 있다.
     * <p>
     * 남는 한계 셋은 수용한다 (2026-08-17 리뷰 기각 근거):
     * <ul>
     *   <li>타이밍 부채널 - 유효한 토큰은 삭제 4문장, 모르는 토큰은 SELECT 1번이라 처리
     *       시간이 갈라진다. 토큰이 256비트 난수라 열거가 불가능하고, 폐기라는 쓰기 자체를
     *       없앨 수 없어 더미 쓰기 같은 완화는 프로토타입 과잉이다.</li>
     *   <li>벌크 문장끼리의 드문 데드락 - 정리 잡의 벌크 문장과 잠금 획득 순서가 어긋날
     *       수 있다. 다문장 잠금 보유는 스위퍼 쪽에서 끊었고({@code AnalysisJobTimeout}),
     *       남는 단문장끼리의 경우는 DB가 한쪽을 끊으므로 재응시 재시도로 회복된다.
     *       baseline(KAN-123) 이후에는 {@link #purgeExpired}의 CASCADE 삭제도 하위
     *       3테이블 행 잠금을 세션 단위 순서로 잡아 이 조합에 새로 들어온다 - 단일
     *       인스턴스에서는 스케줄이 겹치지 않고 양쪽 다 주기 잡이라 다음 주기에 회복되지만,
     *       다중 인스턴스 배포로 겹침이 상시화되면 재검토한다 (2026-08-18 리뷰).</li>
     *   <li>주기 삭제 뒤의 재응시 - {@link #purgeExpired}가 세션 행을 이미 지운
     *       뒤의 재응시는 토큰으로 세션을 못 찾아 이 폐기가 조용히 건너뛰어진다.
     *       하위 행 잔존은 baseline(KAN-123)의 ON DELETE CASCADE가 세션 행 삭제와 함께
     *       지우면서 닫혔다 - 이전에 "다음 수"로 적어 둔 강화가 그 티켓으로 실현됐다.</li>
     * </ul>
     */
    private PurgeSummary purgeForRetake(TestSession previous) {
        long answers = vocabAnswerRepository.deleteBySessionId(previous.id());
        long attempts = analysisJobRepository.deleteBySessionId(previous.id());
        long results = testResultRepository.deleteBySessionId(previous.id());
        repository.delete(previous);
        return new PurgeSummary(previous.id(), answers, attempts, results);
    }

    /** 재응시 폐기의 삭제 건수 - 로그는 커밋 뒤에만 남기므로 트랜잭션 밖으로 들고 나온다. */
    private record PurgeSummary(String sessionId, long answers, long attempts, long results) {}

    /**
     * 재응시 요청의 {@code Authorization} 헤더에서 이전 토큰의 해시를 꺼낸다 (KAN-107).
     * <p>
     * 헤더 부재, 형식 오류, 빈 토큰 전부 401이 아니라 조용한 무시(null)다 - 어떤 입력도
     * 201 외의 응답으로 갈라지면 이 엔드포인트가 토큰 존재 여부를 알려주는 오라클이 된다
     * (티켓 요구 4). 모르는 토큰과 폐기된 토큰, 만료 후 삭제된 토큰이 전부 같은 응답을
     * 받아야 최초 응시와 재응시가 구분되지 않는다.
     */
    private static @Nullable String retakeTokenHash(@Nullable String authorizationHeader) {
        String token = bearerTokenOrNull(authorizationHeader);
        return token == null ? null : SessionTokens.hash(token);
    }

    /**
     * {@code Authorization: Bearer {token}} 헤더로 인증한다 (§2.1, §2.2) -
     * 인증 필요 API(KAN-23, 24, 15, 16, 25)의 공용 진입점.
     * 헤더 부재나 형식 오류도 401 SESSION_EXPIRED다 - 미인증과 만료를 구분해주지 않는다.
     */
    @Transactional(readOnly = true)
    public TestSession authenticateBearer(String sessionId, @Nullable String authorizationHeader) {
        return authenticate(sessionId, bearerToken(authorizationHeader));
    }

    /**
     * {@code /result} 전용 인증 (KAN-25, 2026-08-14 확정) - 완료된 세션은 만료됐어도
     * 통과시켜 결과 만료 판정(410 RESULT_EXPIRED)이 세션 만료(401)보다 먼저 서게 한다.
     * <p>
     * 완료 시 세션 수명이 결과 수명과 같아지므로({@link TestSession#markCompleted}) 이
     * 완화가 실제로 여는 구간은 만료 후 세션 행이 주기 삭제되기 전까지다 - 삭제된 뒤는
     * 모르는 토큰과 같은 401이고, 그 안내 문구도 410처럼 재응시로 이끈다.
     * <p>
     * {@link #authenticate}의 보안 규칙은 유지한다: 만료 토큰과 모르는 토큰은 구분되면
     * 안 되므로, 만료된 토큰을 다른 세션 경로에 대면 403이 아니라 401이다. 미완료
     * 세션의 만료 검사도 그대로다 - 완화는 완료된 세션의 자기 결과 조회에만 적용된다.
     * <p>
     * 완화 자체가 구분 금지 규칙 위반이라는 지적(Codex sol 리뷰 P1)은 기각한다
     * (2026-08-14 확정 설계) - 구분이 생기는 조합은 자기 sessionId + 자기 토큰뿐이라
     * 제3자의 저장소 상태 탐지에 쓸 수 없고, 소유자에게 드러나는 정보도 "내 결과가
     * 만료됐다"라는 제품 의도(§3.7의 410 안내) 그 자체다.
     */
    @Transactional(readOnly = true)
    public TestSession authenticateBearerForResult(String sessionId, @Nullable String authorizationHeader) {
        TestSession session = repository.findByTokenHash(SessionTokens.hash(bearerToken(authorizationHeader)))
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_EXPIRED));
        if (session.isExpired(Instant.now())
                && !(session.isCompleted() && session.id().equals(sessionId))) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED);
        }
        if (!session.id().equals(sessionId)) {
            throw new ApiException(ErrorCode.SESSION_FORBIDDEN);
        }
        return session;
    }

    /** 헤더에서 Bearer 토큰을 꺼낸다. 부재나 형식 오류는 401 - 미인증과 만료를 구분해주지 않는다. */
    private static String bearerToken(@Nullable String authorizationHeader) {
        String token = bearerTokenOrNull(authorizationHeader);
        if (token == null) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED);
        }
        return token;
    }

    /**
     * Bearer 헤더 파싱의 유일한 정의 - 인증({@link #bearerToken})과 재응시
     * ({@link #retakeTokenHash})가 같은 파서를 쓴다 (2026-08-17 리뷰). 규칙이 한쪽만
     * 바뀌면 인증은 받는 헤더를 재응시가 조용히 놓쳐 폐기가 무증상으로 멈춘다.
     * 부재, 형식 오류, 빈 토큰은 null - 실패 응답은 호출부가 정한다.
     */
    private static @Nullable String bearerTokenOrNull(@Nullable String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorizationHeader.substring(7).strip();
        return token.isEmpty() ? null : token;
    }

    /**
     * 토큰이 해당 세션의 것인지 검증한다.
     * <ul>
     *   <li>모르는 토큰과 만료된 토큰 → 401 SESSION_EXPIRED - 이 둘은 어떤 경로로도 구분되면
     *       안 된다. 주기 삭제 전후의 만료 토큰이 다른 응답을 받으면 저장소 상태를 추측하는
     *       단서가 되므로, 만료 검사는 반드시 세션 ID 비교보다 먼저다 (Codex sol 리뷰 P1).</li>
     *   <li>유효한데 다른 세션의 토큰 → 403 SESSION_FORBIDDEN (§2.1 - 경로 {sessionId}와 토큰 세션 불일치)</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public TestSession authenticate(String sessionId, String sessionToken) {
        TestSession session = repository.findByTokenHash(SessionTokens.hash(sessionToken))
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_EXPIRED));
        if (session.isExpired(Instant.now())) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED);
        }
        if (!session.id().equals(sessionId)) {
            throw new ApiException(ErrorCode.SESSION_FORBIDDEN);
        }
        return session;
    }

    /**
     * 만료 세션 주기 삭제 (§2.1). 요청 시 만료 검사가 이미 접근을 막고 있으므로
     * 이 잡은 저장소 크기 관리용이다 - 실패해도 보안에 영향 없다.
     * <p>
     * baseline(KAN-123)의 ON DELETE CASCADE 이후 이 DELETE는 하위 3테이블(답안, 시도,
     * 결과)도 함께 지운다. 미완주 세션의 하위 행 수명이 테이블별 24시간 보존에서 세션
     * TTL(30분) 언저리로 당겨지는데, 조회 경로가 전부 세션 인증을 요구해 사용자 영향은
     * 없고 §5.5 보존 한도의 안쪽으로 움직이는 방향이다. 완주 세션은 만료가 결과 만료와
     * 같아({@link TestSession#markCompleted}) 결과 수명이 줄지 않는다.
     */
    @Scheduled(initialDelay = 10, fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void purgeExpired() {
        long removed = repository.deleteByExpiresAtBefore(Instant.now());
        if (removed > 0) {
            log.info("만료 세션 {}건 삭제", removed);
        }
    }
}
