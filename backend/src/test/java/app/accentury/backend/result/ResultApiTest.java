package app.accentury.backend.result;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.analysis.AnalysisJob;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobStatus;
import app.accentury.backend.analysis.AnalysisJobTransitions;
import app.accentury.backend.session.SessionService;
import app.accentury.backend.session.TestSession;
import app.accentury.backend.session.TestSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /v0/sessions/{sid}/result}의 실행 가능한 명세 (KAN-25, API 명세서 §3.7).
 * <p>
 * 어휘 답안은 실제 제출 API(KAN-15)로 넣고, 음성 시도는 repository로 심은 뒤 실제 전이
 * 경로({@link AnalysisJobTransitions})로 종결하며, 결과 확정은 실제 {@code /complete}
 * (KAN-16)로 만든다 - CompleteApiTest와 같은 구성이다. 시간 경과(만료)는 행의
 * {@code expires_at}을 과거로 되돌려 흉내낸다.
 */
@AutoConfigureMockMvc
class ResultApiTest extends IntegrationTest {

    /** seed 정본의 어휘 정답표 - 전부 이대로 제출하면 단어 점수 100이다. */
    private static final Map<String, String> CORRECT_CHOICES =
            Map.of("w1", "w1a", "w2", "w2b", "w3", "w3a", "w4", "w4b", "w5", "w5a");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    // === READY 응답 (§3.7, AC - 반복 조회 동일 결과, test/score version 포함) ===

    @Test
    void 확정된_결과는_READY_전체_스키마로_반환된다() throws Exception {
        SessionHandle session = createSession();
        // 억양 75(원점수 평균), 단어 60(3/5 정답), 종합 (75x2+60)/3 = 70 → 명예주민
        answerVocab(session, Map.of("w1", "w1a", "w2", "w2b", "w3", "w3a", "w4", "w4a", "w5", "w5b"));
        completeVoice(session, Map.of("v1", 60, "v2", 70, "v3", 80, "v4", 90, "v5", 75));
        complete(session);

        MvcResult ready = mockMvc.perform(result(session))
                .andExpect(status().isOk())
                // 점수가 실리는 개인 결과라 캐시 금지다 - 만료(410) 전환이 가려져도 안 된다.
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.scores.intonation").value(75))
                .andExpect(jsonPath("$.scores.vocabulary").value(60))
                .andExpect(jsonPath("$.scores.overall").value(70))
                .andExpect(jsonPath("$.tier.code").value("HONORARY"))
                .andExpect(jsonPath("$.tier.name").value("명예주민"))
                .andExpect(jsonPath("$.tier.rank").value(4))
                .andExpect(jsonPath("$.tier.of").value(5))
                // 코멘트와 공유 자산은 설정(application.yml) 값 그대로다 - 앱 배포 없이 교체 (§3.7)
                .andExpect(jsonPath("$.comment").value("억양은 거의 토박이인데 단어에서 들켰습니다."))
                .andExpect(jsonPath("$.share.imageUrl").value("https://static.accentury.app/tier/honorary.png"))
                .andExpect(jsonPath("$.share.text").value("나는 명예주민! 너도 시도해볼래?"))
                .andExpect(jsonPath("$.share.webTestUrl").value("https://accentury.app/t?c=kko_share"))
                // AC - 결과에 test version과 score version이 포함된다.
                .andExpect(jsonPath("$.testVersion").value("gn-2026.08.1"))
                .andExpect(jsonPath("$.scoreVersion").value("sv-0.3"))
                .andReturn();

        // §3.7 스키마 그대로다 - 발음, 리듬, 백분위 같은 범위 밖 필드가 새면 여기서 잡힌다.
        assertEquals(Set.of("status", "scores", "tier", "comment", "share",
                "testVersion", "scoreVersion", "expiresAt"), fieldNames(ready));
        JsonNode json = objectMapper.readTree(ready.getResponse().getContentAsString());
        TestResult stored = resultRepository.findBySessionId(session.id()).orElseThrow();
        assertEquals(stored.expiresAt(), Instant.parse(json.get("expiresAt").asString()));

        // AC - 동일 세션 반복 조회는 같은 결과다 (확정 행이 불변이라 본문이 그대로다).
        MvcResult again = mockMvc.perform(result(session)).andExpect(status().isOk()).andReturn();
        assertEquals(ready.getResponse().getContentAsString(), again.getResponse().getContentAsString());
    }

    @Test
    void 재녹음한_문항은_최신_성공_시도_1건으로만_집계된_결과가_나온다() throws Exception {
        // AC - 재녹음 세션도 문항 수 5로 집계된다 (중복 시도 미반영). 정상 흐름은 attempt 1이라
        // 분석 실패 후 재녹음(예외 경로)으로 시나리오를 잡는다 (KAN-25 코멘트 2026-08-09).
        SessionHandle session = createSession();
        answerVocab(session, CORRECT_CHOICES);
        Instant base = Instant.now();
        // v1: 실패 후 재녹음 성공 - 채점 대상은 성공한 2차다.
        failJob(saveJob(session, "v1", 1, base), "AUDIO_TOO_QUIET");
        completeJob(saveJob(session, "v1", 2, base.plusMillis(10)), 100);
        // v2: 성공이 둘 - 옛 성공(0)이 아니라 최신 성공(100)이다.
        completeJob(saveJob(session, "v2", 1, base), 0);
        completeJob(saveJob(session, "v2", 2, base.plusMillis(10)), 100);
        completeVoice(session, Map.of("v3", 100, "v4", 100, "v5", 100));
        complete(session);

        // 이전 시도가 끼면 100이 나올 수 없다 - 5문항 합산에 최신 성공만 들어갔다는 증거다.
        mockMvc.perform(result(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scores.intonation").value(100))
                .andExpect(jsonPath("$.scores.vocabulary").value(100))
                .andExpect(jsonPath("$.scores.overall").value(100))
                .andExpect(jsonPath("$.tier.code").value("NATIVE"));
    }

    // === 미확정 세션의 갈래 (§3.7, /complete와 같은 판정 - 2026-08-14 확정) ===

    @Test
    void 미제출_문항이_있으면_422_RESULT_INCOMPLETE다() throws Exception {
        // AC - 모든 필수 문항(음성 5 + 어휘 5) 완료 전에는 최종 결과를 만들지 않는다.
        SessionHandle session = createSession();
        answerVocab(session, Map.of("w1", "w1a", "w2", "w2b", "w3", "w3a", "w4", "w4b")); // w5 미제출
        completeVoice(session, Map.of("v1", 75, "v2", 75, "v3", 75, "v4", 75)); // v5 미제출

        mockMvc.perform(result(session))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RESULT_INCOMPLETE"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.missingItems", hasSize(2)))
                .andExpect(jsonPath("$.missingItems[0]").value("v5"))
                .andExpect(jsonPath("$.missingItems[1]").value("w5"))
                .andExpect(jsonPath("$.retakeItems").doesNotExist())
                .andExpect(jsonPath("$.pendingItems").doesNotExist());

        assertTrue(resultRepository.findBySessionId(session.id()).isEmpty());
    }

    @Test
    void 전부_실패한_문항이_있으면_409_RESULT_RETAKE_REQUIRED다() throws Exception {
        // 존재하지 않는 점수로 임시 결과를 만들지 않는다 - 복구 정보(재수행 itemId)만 준다 (§3.7).
        SessionHandle session = createSession();
        answerVocab(session, CORRECT_CHOICES);
        completeVoice(session, Map.of("v1", 75, "v3", 75, "v4", 75, "v5", 75));
        Instant base = Instant.now();
        failJob(saveJob(session, "v2", 1, base), "AUDIO_TOO_QUIET");
        failJob(saveJob(session, "v2", 2, base.plusMillis(10)), "AUDIO_TOO_QUIET");

        mockMvc.perform(result(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESULT_RETAKE_REQUIRED"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.retakeItems", hasSize(1)))
                .andExpect(jsonPath("$.retakeItems[0]").value("v2"));
    }

    @Test
    void 분석_중_문항이_있으면_409_RESULT_NOT_READY와_pendingItems다() throws Exception {
        SessionHandle session = createSession();
        answerVocab(session, CORRECT_CHOICES);
        completeVoice(session, Map.of("v1", 75, "v2", 75, "v4", 75, "v5", 75));
        saveJob(session, "v3", 1, Instant.now()); // 아직 분석 중

        mockMvc.perform(result(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESULT_NOT_READY"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.pendingItems", hasSize(1)))
                .andExpect(jsonPath("$.pendingItems[0]").value("v3"));
    }

    @Test
    void 전_문항이_갖춰져도_complete_전에는_409_RESULT_NOT_READY다() throws Exception {
        // 결과 생성은 /complete만 한다 (2026-08-13 확정 - KAN-25는 조회만). 조회가 만들어주면
        // 완료 전이(세션 잠금, completed_at)를 우회한 결과가 생긴다.
        SessionHandle session = createSession();
        answerVocab(session, CORRECT_CHOICES);
        completeVoice(session, Map.of("v1", 75, "v2", 75, "v3", 75, "v4", 75, "v5", 75));

        mockMvc.perform(result(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESULT_NOT_READY"))
                // 기다릴 문항이 없다는 뜻 그대로 빈 목록이다 - 확정은 /complete 호출이 맡는다 (§5.7).
                .andExpect(jsonPath("$.pendingItems", hasSize(0)));

        assertTrue(resultRepository.findBySessionId(session.id()).isEmpty());
    }

    // === 만료와 삭제 (AC - 24시간 경과 시 삭제와 410, §5.5) ===

    @Test
    void 보관_기간이_지나면_삭제_전이든_후든_410이고_세션_정리_뒤엔_401이다() throws Exception {
        SessionHandle session = createSession();
        answerVocab(session, CORRECT_CHOICES);
        completeVoice(session, Map.of("v1", 75, "v2", 75, "v3", 75, "v4", 75, "v5", 75));
        complete(session);
        expireResultAndSession(session);

        // 정리 잡이 돌기 전 - 행은 남아 있지만 만료 시각이 지났으므로 같은 410이다.
        // 세션 토큰도 함께 만료됐지만 완료 세션은 결과 만료 판정이 먼저다 (2026-08-14 확정).
        mockMvc.perform(result(session))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("RESULT_EXPIRED"))
                .andExpect(jsonPath("$.retryable").value(false));

        // AC - 24시간 경과 후 결과가 삭제되고 410과 다시 테스트 안내가 반환된다.
        resultRetention.purgeExpired();
        assertTrue(resultRepository.findBySessionId(session.id()).isEmpty());
        mockMvc.perform(result(session))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("RESULT_EXPIRED"));

        // 세션 행까지 정리되면 모르는 토큰과 같은 401이다 - 저장소 상태를 흘리지 않는다.
        sessionService.purgeExpired();
        mockMvc.perform(result(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    // === 인증 (§2.1, AC - 다른 세션 토큰으로 조회 불가) ===

    @Test
    void 인증_헤더가_없거나_모르는_토큰이면_401이다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(get(url(session)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

        mockMvc.perform(get(url(session)).header(HttpHeaders.AUTHORIZATION, "Bearer st_wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void 다른_세션의_토큰이면_403이고_만료된_타인_토큰이면_401이다() throws Exception {
        // AC - 다른 세션 토큰으로 결과를 조회할 수 없다.
        SessionHandle mine = createSession();
        answerVocab(mine, CORRECT_CHOICES);
        completeVoice(mine, Map.of("v1", 75, "v2", 75, "v3", 75, "v4", 75, "v5", 75));
        complete(mine);
        SessionHandle other = createSession();

        mockMvc.perform(get(url(mine)).header(HttpHeaders.AUTHORIZATION, "Bearer " + other.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SESSION_FORBIDDEN"));

        // 만료된 완료 세션의 통과(410 판정용)는 자기 결과 경로에서만이다 - 만료 토큰을
        // 다른 세션 경로에 대면 모르는 토큰과 같은 401이다 (만료/미지 구분 금지 규칙 유지).
        expireResultAndSession(mine);
        mockMvc.perform(get(url(other)).header(HttpHeaders.AUTHORIZATION, "Bearer " + mine.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void 만료된_미완료_세션은_401이다() throws Exception {
        // 완료 세션의 만료 통과가 진행 중 세션까지 열리면 안 된다 - 30분 TTL은 그대로다 (§2.1).
        SessionHandle session = createSession();
        answerVocab(session, CORRECT_CHOICES);
        expireSession(session);

        mockMvc.perform(result(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    // === 헬퍼 ===

    private record SessionHandle(String id, String token) {
    }

    private SessionHandle createSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/v0/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new SessionHandle(json.get("sessionId").asString(), json.get("sessionToken").asString());
    }

    private static String url(SessionHandle session) {
        return "/v0/sessions/" + session.id() + "/result";
    }

    private RequestBuilder result(SessionHandle session) {
        return get(url(session)).header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token());
    }

    /** 실제 /complete(KAN-16)로 결과를 확정한다. */
    private void complete(SessionHandle session) throws Exception {
        mockMvc.perform(post("/v0/sessions/" + session.id() + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .header("Idempotency-Key", "complete-" + session.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    /** 어휘 답안을 실제 제출 API(KAN-15)로 넣는다. */
    private void answerVocab(SessionHandle session, Map<String, String> choiceByItem) throws Exception {
        for (Map.Entry<String, String> entry : choiceByItem.entrySet()) {
            mockMvc.perform(post("/v0/sessions/" + session.id() + "/vocab-items/"
                            + entry.getKey() + "/answer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"choiceId\": \"" + entry.getValue() + "\"}")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                            .header("Idempotency-Key", "vocab-" + entry.getKey()))
                    .andExpect(status().isOk());
        }
    }

    /** 문항마다 시도 1건을 심고 주어진 원점수로 성공 종결한다. */
    private void completeVoice(SessionHandle session, Map<String, Integer> scoreByItem) {
        Instant base = Instant.now();
        for (Map.Entry<String, Integer> entry : scoreByItem.entrySet()) {
            completeJob(saveJob(session, entry.getKey(), 1, base), entry.getValue());
        }
    }

    private AnalysisJob saveJob(SessionHandle session, String itemId, int attempt, Instant createdAt) {
        return analysisJobRepository.save(new AnalysisJob(
                "a_" + UUID.randomUUID(), session.id(), itemId,
                attempt, "k-" + itemId + "-" + attempt, AnalysisJobStatus.PROCESSING, createdAt));
    }

    private void completeJob(AnalysisJob job, int intonationScore) {
        transitions.complete(job.id(), intonationScore, "OK", "stub-0.1", "sv-0.3");
    }

    private void failJob(AnalysisJob job, String errorCode) {
        transitions.fail(job.id(), AnalysisJobStatus.RETRYABLE_FAILED, errorCode);
    }

    /**
     * 세션의 expires_at을 과거로 되돌린다 - 미완료 세션의 30분 TTL 경과를 흉내낸다.
     * 지우고 다시 만드는 것은 TestSession이 Persistable이라 기존 id의 새 인스턴스를
     * save()하면 merge가 아니라 persist로 가서 중복 키로 터지기 때문이다.
     * <p>
     * 주의 - 세션 FK가 ON DELETE CASCADE라(KAN-123) 이 삭제가 그 세션의 답안, 시도,
     * 결과를 함께 지운다. 하위 행이 남아 있어야 하는 검증에는 쓸 수 없다 - 그런 경우는
     * {@link #expireResultAndSession}처럼 UPDATE 경로로 만료를 흉내내야 한다.
     */
    private void expireSession(SessionHandle session) {
        TestSession stored = sessionRepository.findById(session.id()).orElseThrow();
        sessionRepository.delete(stored);
        sessionRepository.flush();
        sessionRepository.save(new TestSession(stored.id(), stored.tokenHash(),
                stored.testVersion(), stored.scoreVersion(), stored.platform(), stored.appVersion(),
                stored.campaignToken(), stored.createdAt(), Instant.now().minusSeconds(1)));
    }

    /** 완료된 세션과 결과의 expires_at을 과거로 되돌린다 - 24시간 경과를 흉내낸다 (§5.5). */
    private void expireResultAndSession(SessionHandle session) {
        Instant past = Instant.now().minusSeconds(1);
        TestResult stored = resultRepository.findBySessionId(session.id()).orElseThrow();
        resultRepository.save(new TestResult(stored.id(), stored.sessionId(),
                stored.testVersion(), stored.scoreVersion(),
                stored.intonation(), stored.vocabulary(), stored.overall(),
                stored.tierCode(), stored.tierName(), stored.tierRank(), stored.tierCount(),
                stored.createdAt(), past));
        TestSession storedSession = sessionRepository.findById(session.id()).orElseThrow();
        storedSession.markCompleted(storedSession.completedAt(), past);
        sessionRepository.save(storedSession);
    }

    private Set<String> fieldNames(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        Set<String> names = new HashSet<>();
        json.propertyNames().forEach(names::add);
        return names;
    }
}
