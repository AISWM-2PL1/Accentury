package app.accentury.backend.session;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.SessionTestFlow;
import app.accentury.backend.SessionTestFlow.SessionHandle;
import app.accentury.backend.analysis.AnalysisJob;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobStatus;
import app.accentury.backend.analysis.AnalysisJobTransitions;
import app.accentury.backend.analytics.DailyCounter;
import app.accentury.backend.analytics.DailyCounterRepository;
import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.result.TestResult;
import app.accentury.backend.result.TestResultRepository;
import app.accentury.backend.vocab.VocabAnswer;
import app.accentury.backend.vocab.VocabAnswerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재응시 시 이전 세션과 결과 즉시 폐기의 실행 가능한 명세 (KAN-107, SRS FR-TR-04,
 * API 명세서 §3.1과 §5.5).
 * <p>
 * {@code POST /v0/sessions}에 이전 세션의 토큰이 {@code Authorization: Bearer}로 실려
 * 오면 그 세션과 하위 데이터 전부(어휘 답안, 시도와 점수 누적분, 결과, 멱등 키 컬럼)를
 * 24시간 만료를 기다리지 않고 지운 뒤 새 세션을 발급한다. 유효하지 않은 토큰은 조용히
 * 무시된다 - 어떤 입력도 201 외의 응답으로 갈라지면 토큰 존재 여부를 알려주는 오라클이
 * 되기 때문이다.
 */
@AutoConfigureMockMvc
class SessionRetakeApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private TestSessionRepository sessionRepository;

    @Autowired
    private VocabAnswerRepository vocabAnswerRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private TestResultRepository testResultRepository;

    @Autowired
    private DailyCounterRepository countersRepository;

    @Autowired
    private AccenturyProperties properties;

    @Autowired
    private AnalysisJobTransitions transitions;

    @Autowired
    private EntityManager entityManager;

    private SessionTestFlow flow;

    @BeforeEach
    void setUpFlow() {
        flow = new SessionTestFlow(mockMvc, objectMapper, analysisJobRepository, transitions);
    }

    // === AC - 이전 토큰이 실려 오면 세션과 하위 데이터가 전부 삭제된다 ===

    @Test
    void 재응시_토큰이_오면_이전_세션과_하위_데이터가_전부_삭제된다() throws Exception {
        SessionHandle old = flow.createSession();
        seedChildren(old.id());
        // 남의 세션 데이터는 건드리면 안 된다 - 같은 테이블의 다른 행이 대조군이다.
        SessionHandle control = flow.createSession();
        seedChildren(control.id());

        retake(old.token());

        assertTrue(sessionRepository.findById(old.id()).isEmpty(), "세션 행이 즉시 삭제되어야 한다");
        assertEquals(0, vocabAnswerRepository.countBySessionId(old.id()), "어휘 답안이 함께 삭제되어야 한다");
        assertTrue(analysisJobRepository.findBySessionIdOrderByCreatedAtAscAttemptAsc(old.id()).isEmpty(),
                "시도(분석 작업)와 점수 누적분, 멱등 키 컬럼이 함께 삭제되어야 한다");
        assertTrue(testResultRepository.findBySessionId(old.id()).isEmpty(), "최종 결과가 함께 삭제되어야 한다");

        assertTrue(sessionRepository.findById(control.id()).isPresent(), "다른 세션은 남아야 한다");
        assertEquals(1, vocabAnswerRepository.countBySessionId(control.id()));
        assertEquals(2, analysisJobRepository.findBySessionIdOrderByCreatedAtAscAttemptAsc(control.id()).size());
        assertTrue(testResultRepository.findBySessionId(control.id()).isPresent());
    }

    @Test
    void 폐기_후_이전_토큰은_모르는_토큰과_같은_401이다() throws Exception {
        // 티켓 AC 원문의 410은 세션 행까지 지우는 이 설계에서는 성립하지 않는다 - 인증이
        // 먼저 끊긴다. 세션 purge 후 410이 401로 바뀌는 것은 KAN-25에서 수용한 동작이고,
        // 폐기된 토큰이 모르는 토큰과 구분되지 않아야 한다는 점에서는 401이 오히려
        // 정확하다 (2026-08-17 확정 - 티켓 AC를 401로 수정).
        SessionHandle old = flow.createSession();
        seedChildren(old.id());

        retake(old.token());

        mockMvc.perform(get("/v0/sessions/" + old.id() + "/analyses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + old.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    // === AC - 삭제와 새 세션 생성이 한 트랜잭션으로 처리된다 ===

    @Test
    void 새_세션_생성이_실패하면_폐기도_함께_되돌아간다() throws Exception {
        // 한 트랜잭션 검증(티켓 요구 3)을 운영 경로 그대로 확인한다 - campaignToken 컬럼
        // 상한(64자)을 넘는 값으로 새 세션 INSERT를 DB에서 실패시킨다 (컨트롤러의 @Valid를
        // 지나지 않는 서비스 직접 호출이라 가능하다). 폐기가 별도 트랜잭션으로 먼저
        // 커밋되는 구현이면 이전 세션과 하위 데이터가 사라진 채 실패해, 티켓이 금지한
        // "이전 결과도 새 세션도 없는 상태"가 남는다.
        SessionHandle old = flow.createSession();
        seedChildren(old.id());
        long sessionsBefore = sessionRepository.count();

        CreateSessionRequest oversized = new CreateSessionRequest("a".repeat(80), null);
        assertThrows(RuntimeException.class, () -> sessionService.create(
                oversized, "127.0.0.1", "Bearer " + old.token()));

        assertTrue(sessionRepository.findById(old.id()).isPresent(), "폐기가 함께 롤백되어야 한다");
        assertEquals(1, vocabAnswerRepository.countBySessionId(old.id()), "하위 데이터도 그대로여야 한다");
        assertEquals(2, analysisJobRepository.findBySessionIdOrderByCreatedAtAscAttemptAsc(old.id()).size());
        assertTrue(testResultRepository.findBySessionId(old.id()).isPresent());
        assertEquals(sessionsBefore, sessionRepository.count(), "새 세션도 만들어지지 않아야 한다");
    }

    // === AC - 유효하지 않은 토큰은 조용히 무시되고 최초 응시와 구분되지 않는다 ===

    @Test
    void 존재하지_않는_토큰도_201이고_응답이_최초_응시와_구분되지_않는다() throws Exception {
        long sessionsBefore = sessionRepository.count();

        // retake()가 §3.1의 5개 필드와 201을 그대로 검증한다 - 최초 응시(SessionApiTest)와 같은 형태다.
        JsonNode created = retake("st_never-issued-token");

        assertTrue(created.get("sessionId").asString().startsWith("s_"));
        assertEquals(sessionsBefore + 1, sessionRepository.count(), "삭제 없이 새 세션만 생겨야 한다");
    }

    @Test
    void 만료된_세션의_토큰도_조용히_201이고_그_행은_즉시_폐기된다() throws Exception {
        // 만료됐지만 주기 삭제 전인 세션 - FR-TR-04의 목적이 즉시 파기이므로 만료 여부로
        // 가르지 않고 지운다. 응답은 어느 경우든 같아서 삭제 여부가 밖으로 드러나지 않는다.
        String token = "st_expired_retake";
        Instant now = Instant.now();
        TestSession expired = sessionRepository.save(new TestSession(
                SessionTokens.newSessionId(), SessionTokens.hash(token),
                properties.testVersion(), properties.scoreVersion(), null, null, null,
                now.minus(31, ChronoUnit.MINUTES), now.minus(1, ChronoUnit.MINUTES)));

        retake(token);

        assertTrue(sessionRepository.findById(expired.id()).isEmpty(),
                "만료 세션도 주기 삭제를 기다리지 않고 즉시 폐기된다");
    }

    @Test
    void 형식이_틀린_Authorization_헤더는_조용히_무시된다() throws Exception {
        // Bearer가 아닌 스킴과 빈 토큰 - 401이 아니라 최초 응시와 같은 201이다 (오라클 금지).
        mockMvc.perform(post("/v0/sessions").header(HttpHeaders.AUTHORIZATION, "Token abc"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(startsWith("s_")));
        mockMvc.perform(post("/v0/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(startsWith("s_")));
    }

    // === AC - 새 세션에 이전 세션을 참조하는 필드가 없다 ===

    @Test
    void 재응시로_발급된_세션은_이전_세션과_연결되지_않는다() throws Exception {
        // 응답 필드 수는 retake()가, 엔티티 컬럼 허용 목록은 SessionServiceTest가 지킨다 -
        // 응답에도 저장소에도 이전 세션을 가리키는 자리가 없다.
        SessionHandle old = flow.createSession();

        JsonNode next = retake(old.token());

        assertNotEquals(old.id(), next.get("sessionId").asString());
        assertNotEquals(old.token(), next.get("sessionToken").asString());
    }

    // === AC - 익명 집계 카운터가 감소하지 않는다 ===

    @Test
    void 재응시는_집계_카운터를_되돌리지_않는다() throws Exception {
        // 테스트 컨텍스트에 고정 시계가 없어 카운터는 요청 시각의 KST 일자 행에 실린다 -
        // 자정을 사이에 두면 증가가 다음 날 행에 실려 헛되이 실패하므로, 하루가 바뀌지
        // 않은 실행만 유효로 치고 그때만 다시 잰다 (2026-08-17 리뷰). 재시도가 만드는
        // 추가 세션도 실제 발생한 시도라 다른 검증을 오염시키지 않는다.
        LocalDate day;
        long before;
        long after;
        do {
            day = LocalDate.now(properties.analytics().zone());
            before = sessionsStartedToday();
            SessionHandle old = flow.createSession();
            retake(old.token());
            after = sessionsStartedToday();
        } while (!day.equals(LocalDate.now(properties.analytics().zone())));

        // 최초 응시 +1, 재응시 +1 - 이전 세션이 폐기돼도 이미 센 시도는 실제로 발생한
        // 사실이라 되돌리지 않는다 (FR-AN-10). 감소가 끼면 +2가 나올 수 없다.
        assertEquals(before + 2, after);
    }

    // === 폐기 목록의 드리프트 가드 ===

    @Test
    void 세션을_참조하는_모든_엔티티가_재응시_폐기_대상이다() {
        // purgeForRetake의 삭제 목록과 seedChildren의 표본은 하드코드다 - 새 세션 하위
        // 테이블이 생겨도 그 작성자를 SessionService로 이끄는 장치가 없어, 재응시 즉시
        // 폐기가 그 테이블에서만 조용히 깨질 수 있다. sessionId 컬럼을 가진 엔티티 집합을
        // 메타모델에서 뽑아 폐기 목록과 어긋나는 순간 여기가 먼저 깨지게 한다
        // (DailyCounter 컬럼 허용 목록 테스트와 같은 방식, 2026-08-17 리뷰).
        Set<String> sessionKeyed = entityManager.getMetamodel().getEntities().stream()
                .filter(entity -> entity.getAttributes().stream()
                        .anyMatch(attribute -> attribute.getName().equals("sessionId")))
                .map(EntityType::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("VocabAnswer", "AnalysisJob", "TestResult"), sessionKeyed,
                "세션 하위 엔티티가 늘었다 - SessionService.purgeForRetake의 삭제와 "
                        + "seedChildren의 표본에 추가한 뒤 이 목록을 갱신한다");
    }

    /** 이전 토큰을 실은 재응시 호출 - 응답이 §3.1의 최초 응시와 같은 형태(201, 5개 필드)임을 함께 검증한다. */
    private JsonNode retake(String previousToken) throws Exception {
        String body = mockMvc.perform(post("/v0/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + previousToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$").value(aMapWithSize(5)))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /**
     * 티켓이 나열한 삭제 대상의 표본 - 어휘 답안(KAN-15), 진행 중/종결 시도(KAN-24, 멱등 키
     * 컬럼과 점수 누적분 겸함), 최종 결과(KAN-25). 멱등 키는 별도 테이블이 아니라 답안과
     * 시도 행의 컬럼이므로 행 삭제로 함께 사라진다 (§2.2).
     */
    private void seedChildren(String sessionId) {
        Instant now = Instant.now();
        vocabAnswerRepository.save(new VocabAnswer("va_" + UUID.randomUUID(), sessionId,
                "w1", "w1a", true, "ik-vocab", now));
        analysisJobRepository.save(new AnalysisJob("a_" + UUID.randomUUID(), sessionId,
                "v1", 1, "ik-voice-1", AnalysisJobStatus.COMPLETED, now));
        analysisJobRepository.save(new AnalysisJob("a_" + UUID.randomUUID(), sessionId,
                "v2", 1, "ik-voice-2", AnalysisJobStatus.PROCESSING, now));
        testResultRepository.save(new TestResult("r_" + UUID.randomUUID(), sessionId,
                properties.testVersion(), properties.scoreVersion(), 80, 80, 80,
                "HONORARY", "명예주민", 4, 5, now, now.plus(24, ChronoUnit.HOURS)));
    }

    /** 같은 DB를 다른 테스트와 함께 쓰므로 절대값이 아니라 증가분을 본다 (AnalyticsCountersIntegrationTest와 같은 규칙). */
    private long sessionsStartedToday() {
        String id = DailyCounter.idOf(LocalDate.now(properties.analytics().zone()),
                properties.testVersion(), properties.scoreVersion());
        return countersRepository.findById(id).map(DailyCounter::sessionsStarted).orElse(0L);
    }
}
