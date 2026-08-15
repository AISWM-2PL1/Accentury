package app.accentury.backend.vocab;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.analysis.AnalysisJob;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobStatus;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v0/sessions/{sid}/vocab-items/{itemId}/answer}의 실행 가능한 명세
 * (KAN-15, API 명세서 §3.5).
 * <p>
 * 정답표는 seed({@code gn-2026.08.1.json})의 정본을 따른다 - w1 정답 = w1a, w2 정답 = w2b
 * (2026-08-05 확정 더미 세트, KAN-10).
 */
@AutoConfigureMockMvc
class VocabAnswerApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VocabAnswerRepository vocabAnswerRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private TestSessionRepository sessionRepository;

    // === 정상 흐름 (§3.5) ===

    @Test
    void 정상_답변은_200과_진행도를_반환한다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(answer(session, "w1", "ok-1", body("w1a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.answeredCount").value(1))
                .andExpect(jsonPath("$.totalCount").value(10));
    }

    @Test
    void 정오가_저장되고_응답에는_노출되지_않는다() throws Exception {
        // 어휘 채점은 AI를 거치지 않는다 - 서버 정답표 대조 결과가 행에 남고(KAN-21 입력),
        // 정답이든 오답이든 클라이언트가 받는 필드는 같다 (KAN-13 - 정오 미노출)
        SessionHandle session = createSession();

        MvcResult correct = mockMvc.perform(answer(session, "w1", "c-1", body("w1a")))
                .andExpect(status().isOk()).andReturn();
        MvcResult wrong = mockMvc.perform(answer(session, "w2", "c-2", body("w2a")))
                .andExpect(status().isOk()).andReturn();

        assertTrue(vocabAnswerRepository.findBySessionIdAndItemId(session.id(), "w1")
                .orElseThrow().correct());
        assertFalse(vocabAnswerRepository.findBySessionIdAndItemId(session.id(), "w2")
                .orElseThrow().correct());
        assertEquals(fieldNames(correct), fieldNames(wrong));
        assertEquals(Set.of("accepted", "answeredCount", "totalCount"), fieldNames(correct));
    }

    @Test
    void 음성_업로드가_있던_문항도_진행도에_센다() throws Exception {
        // 진행도는 전체 10문항 기준이다 (2026-08-11 확정) - 음성은 §3.4 대표 상태의
        // "NOT_SUBMITTED 아님"과 같은 기준이라, 재녹음 중복은 문항 1개로 접히고
        // 실패한 시도만 있는 문항도 제출된 것으로 센다
        SessionHandle session = createSession();
        Instant now = Instant.now();
        analysisJobRepository.save(new AnalysisJob("a_test-1", session.id(), "v1", 1, "k1",
                AnalysisJobStatus.PROCESSING, now));
        analysisJobRepository.save(new AnalysisJob("a_test-2", session.id(), "v1", 2, "k2",
                AnalysisJobStatus.PROCESSING, now));
        AnalysisJob failedOnly = new AnalysisJob("a_test-3", session.id(), "v2", 1, "k3",
                AnalysisJobStatus.PROCESSING, now);
        failedOnly.markRetryableFailed("AUDIO_TOO_QUIET");
        analysisJobRepository.save(failedOnly);

        mockMvc.perform(answer(session, "w1", "with-voice", body("w1a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answeredCount").value(3))
                .andExpect(jsonPath("$.totalCount").value(10));
    }

    // === 멱등과 재제출 (§5.2, AC) ===

    @Test
    void 같은_키의_재전송은_중복_답변을_만들지_않는다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(answer(session, "w1", "same-key", body("w1a")))
                .andExpect(status().isOk());
        mockMvc.perform(answer(session, "w1", "same-key", body("w1a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        assertEquals(1, vocabAnswerRepository.countBySessionId(session.id()));
    }

    @Test
    void 같은_키로_다른_답을_보내면_400이다() throws Exception {
        // 키 오용 - 새 답에는 새 키를 쓴다 (§5.2). 저장된 답은 바뀌지 않는다
        SessionHandle session = createSession();
        mockMvc.perform(answer(session, "w1", "reused", body("w1a")))
                .andExpect(status().isOk());

        mockMvc.perform(answer(session, "w1", "reused", body("w1b")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertEquals("w1a", vocabAnswerRepository.findBySessionIdAndItemId(session.id(), "w1")
                .orElseThrow().choiceId());
    }

    @Test
    void 새_키의_재제출은_409_ITEM_ALREADY_ANSWERED다() throws Exception {
        // 확정 플로우(§5.7)에 답 변경 UI가 없다 - 재제출은 거절한다 (2026-08-11 확정)
        SessionHandle session = createSession();
        mockMvc.perform(answer(session, "w1", "first", body("w1a")))
                .andExpect(status().isOk());

        mockMvc.perform(answer(session, "w1", "second", body("w1b")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_ALREADY_ANSWERED"))
                .andExpect(jsonPath("$.retryable").value(false));

        assertEquals("w1a", vocabAnswerRepository.findBySessionIdAndItemId(session.id(), "w1")
                .orElseThrow().choiceId());
        assertEquals(1, vocabAnswerRepository.countBySessionId(session.id()));
    }

    // === 세션 상태 (AC - 완료된 세션 거절) ===

    @Test
    void 완료된_세션에는_409_SESSION_COMPLETED다() throws Exception {
        SessionHandle session = createSession();
        TestSession stored = sessionRepository.findById(session.id()).orElseThrow();
        stored.markCompleted(Instant.now(), stored.expiresAt());
        sessionRepository.save(stored);

        mockMvc.perform(answer(session, "w1", "after-complete", body("w1a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_COMPLETED"));
    }

    // === 인증 (§2.1) ===

    @Test
    void 인증_헤더가_없거나_모르는_토큰이면_401이다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(post(url(session, "w1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("w1a"))
                        .header("Idempotency-Key", "no-auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

        mockMvc.perform(post(url(session, "w1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("w1a"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer st_wrong")
                        .header("Idempotency-Key", "bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void 다른_세션의_토큰이면_403이다() throws Exception {
        SessionHandle mine = createSession();
        SessionHandle other = createSession();

        mockMvc.perform(post(url(mine, "w1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("w1a"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.token())
                        .header("Idempotency-Key", "cross-session"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SESSION_FORBIDDEN"));
    }

    // === 문항/선택지 검증 (AC - 다른 버전/다른 세션 제출 거절) ===

    @Test
    void 버전에_없는_문항이면_422_ITEM_NOT_IN_VERSION이다() throws Exception {
        mockMvc.perform(answer(createSession(), "zz", "no-such-item", body("w1a")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_IN_VERSION"));
    }

    @Test
    void 음성_문항에_제출하면_409_ITEM_WRONG_TYPE이다() throws Exception {
        mockMvc.perform(answer(createSession(), "v1", "voice-item", body("w1a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_WRONG_TYPE"));
    }

    @Test
    void 이_문항의_선택지가_아니면_422_ITEM_NOT_IN_VERSION이다() throws Exception {
        SessionHandle session = createSession();

        // 같은 버전의 다른 문항 선택지 - 버전 전체가 아니라 문항 단위로 검증한다
        mockMvc.perform(answer(session, "w1", "other-item-choice", body("w2a")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_IN_VERSION"));

        mockMvc.perform(answer(session, "w1", "unknown-choice", body("nope")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_IN_VERSION"));

        assertEquals(0, vocabAnswerRepository.countBySessionId(session.id()));
    }

    // === 필수 헤더와 본문 (§2.2, §3.5) ===

    @Test
    void choiceId가_없으면_400이다() throws Exception {
        SessionHandle session = createSession();
        String[] broken = {"{}", "{\"choiceId\": null}", "{\"choiceId\": \"\"}", ""};

        for (int i = 0; i < broken.length; i++) {
            mockMvc.perform(answer(session, "w1", "no-choice-" + i, broken[i]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void 깨진_JSON_본문이면_500이_아니라_400이다() throws Exception {
        mockMvc.perform(answer(createSession(), "w1", "broken-json", "{\"choiceId\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void Idempotency_Key가_없으면_400이다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(post(url(session, "w1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("w1a"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void Idempotency_Key가_100자를_넘으면_400이다() throws Exception {
        // 컬럼 길이가 100이라 검증이 없으면 저장 시점에 500이 된다
        mockMvc.perform(answer(createSession(), "w1", "k".repeat(101), body("w1a")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // === CORS (§2.5, KAN-31) ===

    @Test
    void 허용_오리진의_프리플라이트가_통과한다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(options(url(session, "w1"))
                        .header(HttpHeaders.ORIGIN, "https://web.test")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://web.test"));
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

    private static String url(SessionHandle session, String itemId) {
        return "/v0/sessions/" + session.id() + "/vocab-items/" + itemId + "/answer";
    }

    private static String body(String choiceId) {
        return "{\"choiceId\": \"" + choiceId + "\"}";
    }

    private RequestBuilder answer(SessionHandle session, String itemId, String idempotencyKey,
                                  String jsonBody) {
        return post(url(session, itemId))
                .contentType(MediaType.APPLICATION_JSON).content(jsonBody)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .header("Idempotency-Key", idempotencyKey);
    }

    private Set<String> fieldNames(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        Set<String> names = new HashSet<>();
        json.propertyNames().forEach(names::add);
        return names;
    }
}
