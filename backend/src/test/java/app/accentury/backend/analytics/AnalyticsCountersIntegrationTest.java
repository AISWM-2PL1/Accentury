package app.accentury.backend.analytics;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.SessionTestFlow;
import app.accentury.backend.SessionTestFlow.SessionHandle;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobTransitions;
import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.result.TestResultRepository;
import app.accentury.backend.result.TestResultRetention;
import app.accentury.backend.session.SessionService;
import app.accentury.backend.session.TestSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static app.accentury.backend.SessionTestFlow.CORRECT_CHOICES;
import static app.accentury.backend.SessionTestFlow.completeUrl;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 익명 집계 카운터의 실행 가능한 명세 (KAN-106, SRS FR-AN-10).
 * <p>
 * 세션 생성과 완료는 실제 API로 하고(CompleteApiTest와 같은 구성), 카운터는 저장된 행을
 * 직접 읽어 확인한다. 같은 DB를 다른 테스트가 함께 쓰므로 <b>절대값이 아니라 증가분</b>을
 * 본다 - 실행 순서에 기대지 않기 위해서다.
 */
@AutoConfigureMockMvc
class AnalyticsCountersIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DailyCounterRepository countersRepository;

    @Autowired
    private DailyCounterStore store;

    @Autowired
    private AnalyticsCounters counters;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private AnalysisJobTransitions transitions;

    @Autowired
    private TestResultRepository resultRepository;

    @Autowired
    private TestSessionRepository sessionRepository;

    @Autowired
    private TestResultRetention resultRetention;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private AccenturyProperties properties;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private SessionTestFlow flow;

    @BeforeEach
    void setUpFlow() {
        flow = new SessionTestFlow(mockMvc, objectMapper, analysisJobRepository, transitions);
    }

    // === AC - 세션 생성과 결과 생성 시 각각 카운터가 증가한다 ===

    @Test
    void 세션_생성은_응시_시도를_1_올린다() throws Exception {
        Snapshot before = snapshot();

        flow.createSession();

        Snapshot after = snapshot();
        assertEquals(1, after.sessionsStarted() - before.sessionsStarted());
        assertEquals(0, after.sessionsCompleted() - before.sessionsCompleted(),
                "시작만으로는 완주가 늘지 않는다");
    }

    @Test
    void 결과_확정은_완주와_등급과_점수를_함께_올린다() throws Exception {
        SessionHandle session = flow.createSession();
        Snapshot before = snapshot();
        // 억양 75(원점수 평균), 단어 60(3/5 정답), 종합 (75x2+60)/3 = 70 → 명예주민
        flow.answerVocab(session, Map.of("w1", "w1a", "w2", "w2b", "w3", "w3a", "w4", "w4a", "w5", "w5b"));
        flow.completeVoice(session, Map.of("v1", 60, "v2", 70, "v3", 80, "v4", 90, "v5", 75));

        flow.complete(session, "counted");

        Snapshot after = snapshot();
        assertEquals(1, after.sessionsCompleted() - before.sessionsCompleted());
        assertEquals(1, after.tierHonorary() - before.tierHonorary());
        assertEquals(0, after.tierNative() - before.tierNative());
        assertEquals(75, after.intonationSum() - before.intonationSum());
        assertEquals(60, after.vocabularySum() - before.vocabularySum());
        assertEquals(70, after.overallSum() - before.overallSum());
        assertEquals(1, after.scoredCount() - before.scoredCount());
    }

    @Test
    void 완료_재시도는_완주를_두_번_세지_않는다() throws Exception {
        // 완료는 자연 멱등이다 (§3.6 - 재시도는 READY 재확인) - 카운터도 같아야 한다
        SessionHandle session = flow.createSession();
        flow.answerVocab(session, CORRECT_CHOICES);
        flow.completeVoice(session);
        flow.complete(session, "first");
        Snapshot before = snapshot();

        flow.complete(session, "second");
        flow.complete(session, "third");

        Snapshot after = snapshot();
        assertEquals(0, after.sessionsCompleted() - before.sessionsCompleted());
        assertEquals(0, after.scoredCount() - before.scoredCount());
    }

    @Test
    void 완주하지_못한_세션은_완주로_세지_않는다() throws Exception {
        SessionHandle session = flow.createSession();
        Snapshot before = snapshot();
        flow.answerVocab(session, CORRECT_CHOICES); // 음성 5문항 미제출

        mockMvc.perform(post(completeUrl(session))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .header("Idempotency-Key", "incomplete"))
                .andExpect(status().isUnprocessableContent());

        Snapshot after = snapshot();
        assertEquals(0, after.sessionsCompleted() - before.sessionsCompleted(),
                "시도는 이미 셌지만 완주는 결과가 확정될 때만 센다");
    }

    // === AC - 동시 요청에서 카운터가 유실되지 않는다 ===

    @Test
    void 동시_증가가_몰려도_유실되지_않는다() throws Exception {
        // 첫 증가(INSERT)와 나머지(UPDATE)가 뒤섞이는 순간이 가장 위험하다. 다른 테스트가
        // 이미 오늘 행을 만들어 뒀으면 그 갈래가 통째로 안 걸리므로, 이 실행에만 있는
        // 버전 문자열을 써서 <b>행 부재를 보장</b>한다 (Fable 리뷰 P3)
        String testVersion = "gn-race-" + UUID.randomUUID().toString().substring(0, 8);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        Instant at = Instant.now();
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        counters.recordSessionStarted(at, testVersion, properties.scoreVersion());
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "동시 증가가 시간 안에 끝나야 한다");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, failures.get());
        String id = DailyCounter.idOf(LocalDate.now(properties.analytics().zone()),
                testVersion, properties.scoreVersion());
        assertEquals(threads, countersRepository.findById(id).orElseThrow().sessionsStarted(),
                "조회 후 저장이면 증가가 서로를 덮어써 이 수가 모자란다");
    }

    @Test
    void 같은_키를_두_번_만들면_두_번째는_실패한다() {
        // 첫 행 생성 경합의 복구는 "INSERT가 정말 예외로 끝난다"에 통째로 기대고 있다.
        // 조용히 성공하거나 절대값으로 덮어쓰면 그날의 증가가 통째로 사라지므로,
        // 가짜 저장소가 아니라 진짜 DB에서 이 성질을 못박는다 (Fable 리뷰 P3)
        LocalDate day = LocalDate.of(2026, 5, 5);
        String testVersion = "gn-dup-" + UUID.randomUUID().toString().substring(0, 8);
        String id = DailyCounter.idOf(day, testVersion, "sv-0.3");
        store.insert(day, testVersion, "sv-0.3", CounterDelta.sessionStarted());

        assertThrows(RuntimeException.class,
                () -> store.insert(day, testVersion, "sv-0.3", CounterDelta.sessionStarted()),
                "유니크 제약 위반이 예외로 올라와야 UPDATE 복귀 갈래가 성립한다");

        // 실패한 두 번째는 흔적을 남기지 않았고, 복구 갈래(UPDATE)는 그대로 동작한다
        assertEquals(1, countersRepository.findById(id).orElseThrow().sessionsStarted());
        assertTrue(store.increment(id, CounterDelta.sessionStarted()));
        assertEquals(2, countersRepository.findById(id).orElseThrow().sessionsStarted());
    }

    @Test
    void 버전에_키_구분자가_들어가면_남의_행을_건드리지_않고_버린다() {
        // 서로 다른 키 셋이 같은 식별자로 접히면 조용히 남의 행에 합산된다 (Fable 리뷰 P3).
        // 증가는 실패하지만 사용자 요청 경로와 마찬가지로 예외는 새지 않는다
        Instant at = Instant.now();
        assertDoesNotThrow(() -> counters.recordSessionStarted(at, "gn-a|b", "sv-0.3"));

        // ("gn-a|b", "sv-0.3")과 ("gn-a", "b|sv-0.3")이 함께 접히던 자리다
        String collidingId = LocalDate.now(properties.analytics().zone()) + "|gn-a|b|sv-0.3";
        assertTrue(countersRepository.findById(collidingId).isEmpty());
    }

    // === AC - 개인 식별 가능 정보를 저장하지 않는다 ===

    @Test
    void 집계_행에는_허용된_숫자와_키만_있다() {
        // 컬럼 목록을 통째로 고정한다 - 나중에 "세션 ID 하나쯤"이 늘면 이 테스트가 먼저 깨진다.
        // 세션 ID, 토큰, IP, 개별 점수 행은 어떤 이름으로도 여기 들어올 수 없다 (티켓 핵심 제약)
        Set<String> attributes = entityManager.getMetamodel().entity(DailyCounter.class)
                .getAttributes().stream().map(Attribute::getName).collect(Collectors.toSet());

        assertEquals(Set.of("id", "statDate", "testVersion", "scoreVersion",
                        "sessionsStarted", "sessionsCompleted",
                        "tierOutsider", "tierTraveler", "tierWannabe", "tierHonorary", "tierNative",
                        "intonationSum", "vocabularySum", "overallSum", "scoredCount"),
                attributes);
    }

    @Test
    void 식별자는_세션과_무관한_일자와_버전_조합이다() throws Exception {
        SessionHandle session = flow.createSession();

        DailyCounter row = countersRepository.findById(todayId()).orElseThrow();
        assertEquals(todayId(), row.id());
        assertTrue(row.id().contains(properties.testVersion()));
        assertFalse(row.id().contains(session.id()), "세션 ID가 식별자에 새면 안 된다");
    }

    // === AC - 개인 결과가 24시간 후 파기돼도 집계는 유지된다 ===

    @Test
    void 결과와_세션이_파기돼도_카운터는_남는다() throws Exception {
        SessionHandle session = flow.createSession();
        flow.answerVocab(session, CORRECT_CHOICES);
        flow.completeVoice(session);
        flow.complete(session, "retention");
        Snapshot afterComplete = snapshot();
        assertNotNull(resultRepository.findBySessionId(session.id()).orElseThrow());

        // 24시간이 지난 상태를 만든다 - 행이 저장 시점에 확정한 expires_at을 과거로 되돌린다
        expire(session.id());
        resultRetention.purgeExpired();
        sessionService.purgeExpired();

        assertTrue(resultRepository.findBySessionId(session.id()).isEmpty(), "개인 결과는 파기된다");
        assertTrue(sessionRepository.findById(session.id()).isEmpty(), "세션도 파기된다");
        assertEquals(afterComplete, snapshot(), "집계 카운터는 그대로 남아야 한다 (NFR-PR-03)");
    }

    // === 조립 ===

    /** 세션과 결과의 수명을 과거로 되돌려 24시간 경과를 흉내낸다 */
    private void expire(String sessionId) {
        transactionTemplate.executeWithoutResult(tx -> {
            Instant past = Instant.now().minusSeconds(3_600);
            entityManager.createQuery(
                            "update TestResult r set r.expiresAt = :past where r.sessionId = :sessionId")
                    .setParameter("past", past).setParameter("sessionId", sessionId).executeUpdate();
            entityManager.createQuery("update TestSession s set s.expiresAt = :past where s.id = :id")
                    .setParameter("past", past).setParameter("id", sessionId).executeUpdate();
        });
    }

    private String todayId() {
        return DailyCounter.idOf(LocalDate.now(properties.analytics().zone()),
                properties.testVersion(), properties.scoreVersion());
    }

    /** 오늘 행의 값 - 아직 없으면 전부 0이다 */
    private Snapshot snapshot() {
        return countersRepository.findById(todayId())
                .map(c -> new Snapshot(c.sessionsStarted(), c.sessionsCompleted(),
                        c.tierHonorary(), c.tierNative(),
                        c.intonationSum(), c.vocabularySum(), c.overallSum(), c.scoredCount()))
                .orElseGet(() -> new Snapshot(0, 0, 0, 0, 0, 0, 0, 0));
    }

    private record Snapshot(long sessionsStarted, long sessionsCompleted,
                            long tierHonorary, long tierNative,
                            long intonationSum, long vocabularySum, long overallSum, long scoredCount) {
    }
}
