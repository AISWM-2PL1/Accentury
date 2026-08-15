package app.accentury.backend.result;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.analysis.AnalysisJob;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobStatus;
import app.accentury.backend.analysis.AnalysisJobTransitions;
import app.accentury.backend.common.AccenturyProperties;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v0/sessions/{sid}/complete}의 실행 가능한 명세 (KAN-16, API 명세서 §3.6).
 * <p>
 * 어휘 답안은 실제 제출 API(KAN-15)로 넣고, 음성 시도는 repository로 심은 뒤 실제 전이
 * 경로({@link AnalysisJobTransitions})로 종결한다. 정답표는 seed({@code gn-2026.08.1.json})의
 * 정본을 따른다 - w1a, w2b, w3a, w4b, w5a (2026-08-05 확정 더미 세트, KAN-10).
 */
@AutoConfigureMockMvc
class CompleteApiTest extends IntegrationTest {

    /** seed 정본의 어휘 정답표 - 전부 이대로 제출하면 단어 점수 100이다 */
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
    private AccenturyProperties properties;

    // === 완료와 결과 확정 (§3.6, AC - 재시도 중복 생성 없음) ===

    @Test
    void 전_문항이_갖춰지면_READY와_결과가_1회_확정된다() throws Exception {
        SessionHandle session = createSession();
        answerVocab(session, Map.of("w1", "w1a", "w2", "w2b", "w3", "w3a", "w4", "w4a", "w5", "w5b"));
        completeVoice(session, Map.of("v1", 60, "v2", 70, "v3", 80, "v4", 90, "v5", 75));

        MvcResult ready = mockMvc.perform(complete(session, "first"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andReturn();
        // READY에는 점수/등급/대기 필드가 없다 - 결과 공개는 /result 한 곳이다 (§3.7)
        assertEquals(Set.of("status"), fieldNames(ready));

        // 확정 검증: sv-0.3 - 억양 75(원점수 평균), 단어 60(3/5 정답), 종합 (75x2+60)/3 = 70 → 명예주민
        TestResult result = resultRepository.findBySessionId(session.id()).orElseThrow();
        assertEquals(75, result.intonation());
        assertEquals(60, result.vocabulary());
        assertEquals(70, result.overall());
        assertEquals("HONORARY", result.tierCode());
        assertEquals("명예주민", result.tierName());
        assertEquals(4, result.tierRank());
        assertEquals(5, result.tierCount());
        assertEquals("gn-2026.08.1", result.testVersion());
        assertEquals("sv-0.3", result.scoreVersion());
        // 결과 수명은 저장 시점에 확정된다 - /result 응답의 expiresAt이자 정리 기준 (§3.7, §5.5)
        assertEquals(result.createdAt().plus(properties.analysis().retention()), result.expiresAt());
        TestSession completed = sessionRepository.findById(session.id()).orElseThrow();
        assertNotNull(completed.completedAt());
        // 완료 시 토큰 수명이 결과 수명까지 연장된다 (2026-08-14 확정, KAN-25) - 30분 TTL
        // 그대로면 결과 재조회(§5.5)와 만료 410 안내가 전부 401에 막힌다
        assertEquals(result.expiresAt(), completed.expiresAt());

        // 재시도(다른 키여도)는 READY 재확인일 뿐 결과를 다시 만들지 않는다 (AC) -
        // 같은 행이 그대로 남아 있어야 한다 (세션당 유니크 제약은 마지막 안전망)
        mockMvc.perform(complete(session, "retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        assertEquals(result.id(), resultRepository.findBySessionId(session.id()).orElseThrow().id());
    }

    @Test
    void 재녹음_시도가_여럿이어도_최신_성공_1건으로_완료된다() throws Exception {
        // AC - 시도가 여러 개인 문항도 최신 성공 시도 1건이 있으면 완료로 인정된다 (§5.1)
        SessionHandle session = createSession();
        answerVocab(session, CORRECT_CHOICES);
        Instant base = Instant.now();
        // v1: 성공 후 재녹음이 실패 - 성공이 살아 있으므로 완료다 (§3.4 대표 상태 규칙 2)
        completeJob(saveJob(session, "v1", 1, base), 100);
        failJob(saveJob(session, "v1", 2, base.plusMillis(10)), "AUDIO_TOO_QUIET");
        // v2: 성공이 둘 - 채점 대상은 최신 성공이다
        completeJob(saveJob(session, "v2", 1, base), 0);
        completeJob(saveJob(session, "v2", 2, base.plusMillis(10)), 100);
        completeVoice(session, Map.of("v3", 100, "v4", 100, "v5", 100));

        mockMvc.perform(complete(session, "multi-attempt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        // v2가 이전 성공(0)으로 집계됐다면 억양 80, 종합 87이 된다 - 100이면 최신 성공이 이겼다
        TestResult result = resultRepository.findBySessionId(session.id()).orElseThrow();
        assertEquals(100, result.intonation());
        assertEquals(100, result.overall());
        assertEquals("NATIVE", result.tierCode());
    }

    // === 완주 판정 갈래 (§3.6, 우선순위: 미제출 > 실패 > 분석 중) ===

    @Test
    void 미제출_문항이_있으면_422와_missingItems다() throws Exception {
        // AC - 10문항 중 하나라도 누락되면 거절되고 누락 문항이 식별된다
        SessionHandle session = createSession();
        answerVocab(session, Map.of("w1", "w1a", "w2", "w2b", "w3", "w3a", "w4", "w4b")); // w5 미제출
        completeVoice(session, Map.of("v1", 75, "v2", 75, "v3", 75, "v4", 75)); // v5 미제출

        mockMvc.perform(complete(session, "missing"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RESULT_INCOMPLETE"))
                .andExpect(jsonPath("$.retryable").value(false))
                // 문항 순서는 정의의 seq 순서다 - v5(seq 9)가 w5(seq 10)보다 앞
                .andExpect(jsonPath("$.missingItems", hasSize(2)))
                .andExpect(jsonPath("$.missingItems[0]").value("v5"))
                .andExpect(jsonPath("$.missingItems[1]").value("w5"))
                .andExpect(jsonPath("$.retakeItems").doesNotExist())
                .andExpect(jsonPath("$.pendingItems").doesNotExist());

        assertNull(sessionRepository.findById(session.id()).orElseThrow().completedAt());
        assertTrue(resultRepository.findBySessionId(session.id()).isEmpty());
    }

    @Test
    void 분석_중_문항이_있으면_PROCESSING과_pendingItems다() throws Exception {
        SessionHandle session = createSession();
        answerVocab(session, CORRECT_CHOICES);
        completeVoice(session, Map.of("v1", 75, "v2", 75, "v4", 75, "v5", 75));
        saveJob(session, "v3", 1, Instant.now()); // 아직 분석 중

        mockMvc.perform(complete(session, "pending"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.pendingItems", hasSize(1)))
                .andExpect(jsonPath("$.pendingItems[0]").value("v3"))
                .andExpect(jsonPath("$.pollAfterMs").value(800));

        // 완료 전이가 일어나지 않았다 - 분석이 끝나면 다음 호출이 확정한다
        assertNull(sessionRepository.findById(session.id()).orElseThrow().completedAt());
        assertTrue(resultRepository.findBySessionId(session.id()).isEmpty());
    }

    @Test
    void 전부_실패한_문항은_409_RESULT_RETAKE_REQUIRED다() throws Exception {
        // 성공도 진행 중도 없는 문항은 기다려도 안 바뀐다 - 재녹음(새 시도)으로만 풀린다
        SessionHandle session = createSession();
        answerVocab(session, CORRECT_CHOICES);
        completeVoice(session, Map.of("v1", 75, "v3", 75, "v4", 75, "v5", 75));
        Instant base = Instant.now();
        failJob(saveJob(session, "v2", 1, base), "AUDIO_TOO_QUIET");
        failJob(saveJob(session, "v2", 2, base.plusMillis(10)), "AUDIO_TOO_QUIET");

        mockMvc.perform(complete(session, "retake"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESULT_RETAKE_REQUIRED"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.retakeItems", hasSize(1)))
                .andExpect(jsonPath("$.retakeItems[0]").value("v2"));

        assertNull(sessionRepository.findById(session.id()).orElseThrow().completedAt());
    }

    @Test
    void 최신_시도가_분석_중이면_이전_성공이_있어도_PROCESSING이다() throws Exception {
        // §3.4 대표 상태 규칙 1과 정합 - 새 결과가 채점 대상을 갈아치울 수 있으므로 기다린다.
        // 여기서 완료하면 대기 화면은 PROCESSING이라는데 결과는 확정되는 모순이 생긴다
        SessionHandle session = createSession();
        answerVocab(session, CORRECT_CHOICES);
        completeVoice(session, Map.of("v2", 75, "v3", 75, "v4", 75, "v5", 75));
        Instant base = Instant.now();
        completeJob(saveJob(session, "v1", 1, base), 75);
        saveJob(session, "v1", 2, base.plusMillis(10)); // 재녹음이 아직 분석 중

        mockMvc.perform(complete(session, "newer-processing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.pendingItems", hasSize(1)))
                .andExpect(jsonPath("$.pendingItems[0]").value("v1"));
    }

    @Test
    void 성공보다_새로운_시도가_분석_중이면_이후_실패가_있어도_PROCESSING이다() throws Exception {
        // [성공, 분석 중, 실패] - 분석 중인 2차가 나중에 성공하면 채점 대상(최신 성공)이
        // 바뀐다. 여기서 확정하면 옛 점수가 불변 결과로 박제된다 (Codex sol 리뷰 P1)
        SessionHandle session = createSession();
        answerVocab(session, CORRECT_CHOICES);
        completeVoice(session, Map.of("v2", 75, "v3", 75, "v4", 75, "v5", 75));
        Instant base = Instant.now();
        completeJob(saveJob(session, "v1", 1, base), 75);
        saveJob(session, "v1", 2, base.plusMillis(10)); // 재녹음이 아직 분석 중
        failJob(saveJob(session, "v1", 3, base.plusMillis(20)), "AUDIO_TOO_QUIET");

        mockMvc.perform(complete(session, "stale-success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.pendingItems", hasSize(1)))
                .andExpect(jsonPath("$.pendingItems[0]").value("v1"));

        assertNull(sessionRepository.findById(session.id()).orElseThrow().completedAt());
        assertTrue(resultRepository.findBySessionId(session.id()).isEmpty());
    }

    @Test
    void 판정_우선순위는_미제출_실패_분석중_순이다() throws Exception {
        // 미제출이 있으면 422가 먼저다 - 제출부터 해야 나머지 갈래가 의미 있다
        SessionHandle mixed = createSession();
        answerVocab(mixed, CORRECT_CHOICES);
        completeVoice(mixed, Map.of("v4", 75, "v5", 75));
        failJob(saveJob(mixed, "v2", 1, Instant.now()), "AUDIO_TOO_QUIET");
        saveJob(mixed, "v3", 1, Instant.now()); // 분석 중, v1은 미제출

        mockMvc.perform(complete(mixed, "mixed-1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RESULT_INCOMPLETE"))
                .andExpect(jsonPath("$.missingItems", hasSize(1)))
                .andExpect(jsonPath("$.missingItems[0]").value("v1"));

        // 미제출이 없으면 실패가 분석 중보다 먼저다 - 기다리는 동안 재녹음을 시작할 수 있게
        SessionHandle failedAndPending = createSession();
        answerVocab(failedAndPending, CORRECT_CHOICES);
        completeVoice(failedAndPending, Map.of("v1", 75, "v4", 75, "v5", 75));
        failJob(saveJob(failedAndPending, "v2", 1, Instant.now()), "AUDIO_TOO_QUIET");
        saveJob(failedAndPending, "v3", 1, Instant.now());

        mockMvc.perform(complete(failedAndPending, "mixed-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESULT_RETAKE_REQUIRED"))
                .andExpect(jsonPath("$.retakeItems", hasSize(1)))
                .andExpect(jsonPath("$.retakeItems[0]").value("v2"));
    }

    // === 완료 뒤의 세션 (KAN-15/23 가드와의 정합) ===

    @Test
    void 완료된_세션에는_같은_키의_답안_재전송도_409_SESSION_COMPLETED다() throws Exception {
        SessionHandle session = createSession();
        answerVocab(session, CORRECT_CHOICES);
        completeVoice(session, Map.of("v1", 75, "v2", 75, "v3", 75, "v4", 75, "v5", 75));
        mockMvc.perform(complete(session, "then-submit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        // 완료 가드가 멱등 판별보다 먼저다 (KAN-15) - 확정 후에는 재전송도 거절된다
        mockMvc.perform(post("/v0/sessions/" + session.id() + "/vocab-items/w1/answer")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"choiceId\": \"w1a\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .header("Idempotency-Key", "vocab-w1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_COMPLETED"));
    }

    // === 필수 헤더 (§2.2) ===

    @Test
    void Idempotency_Key가_없으면_400이다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(post(url(session))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // === 인증 (§2.1) ===

    @Test
    void 인증_헤더가_없거나_모르는_토큰이면_401이다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(post(url(session)).header("Idempotency-Key", "no-auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

        mockMvc.perform(post(url(session))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer st_wrong")
                        .header("Idempotency-Key", "bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void 다른_세션의_토큰이면_403이다() throws Exception {
        SessionHandle mine = createSession();
        SessionHandle other = createSession();

        mockMvc.perform(post(url(mine))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.token())
                        .header("Idempotency-Key", "cross-session"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SESSION_FORBIDDEN"));
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
        return "/v0/sessions/" + session.id() + "/complete";
    }

    private RequestBuilder complete(SessionHandle session, String idempotencyKey) {
        return post(url(session))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .header("Idempotency-Key", idempotencyKey);
    }

    /** 어휘 답안을 실제 제출 API(KAN-15)로 넣는다 */
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

    /** 문항마다 시도 1건을 심고 주어진 원점수로 성공 종결한다 */
    private void completeVoice(SessionHandle session, Map<String, Integer> scoreByItem) {
        Instant base = Instant.now();
        for (Map.Entry<String, Integer> entry : scoreByItem.entrySet()) {
            completeJob(saveJob(session, entry.getKey(), 1, base), entry.getValue());
        }
    }

    private AnalysisJob saveJob(SessionHandle session, String itemId, int attempt, Instant createdAt) {
        // ID는 운영과 같은 형식(a_ + UUID, 컬럼 40자 이내)이고, 시도 구분은 키와 attempt가 한다
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

    private Set<String> fieldNames(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        Set<String> names = new HashSet<>();
        json.propertyNames().forEach(names::add);
        return names;
    }
}
