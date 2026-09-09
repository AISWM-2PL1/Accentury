package app.accentury.backend.session;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.SessionTestFlow;
import app.accentury.backend.SessionTestFlow.SessionHandle;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobTransitions;
import app.accentury.backend.result.TestResult;
import app.accentury.backend.result.TestResultRepository;
import app.accentury.backend.testdefinition.ActiveVersionService;
import app.accentury.backend.upload.WavFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 세트 다중화의 세션 경로 실행 명세 (KAN-182 AC - 세션 고정, 세트 밖 제출 거절, 세트 기준
 * 상태 조회와 진행도, 완주 판정과 집계, 재응시). 세트를 생략했을 때의 서버 배정도 여기서
 * 실행한다 (KAN-205) - 세트가 둘 이상인 픽스처가 이 클래스에만 있다.
 * <p>
 * 활성 버전을 N = 7 풀 픽스처 {@code gn-2026.09.t7}(세트 2 = v6, v7 + v1, v2, v3, V901)로 바꿔 놓고
 * 세트 2 세션으로 끝까지 간다. 활성 포인터는 클래스 사이 초기화 대상이 아니라 반드시 되돌린다
 * ({@link #restoreBaseline}, {@code DatabaseWipeExtension.KEEP}).
 */
@AutoConfigureMockMvc
class VoiceSetSessionApiTest extends IntegrationTest {

    private static final String POOL7 = "gn-2026.09.t7";
    private static final String BASELINE = "gn-2026.08.1";

    private static final String VALID_META = """
            {"durationMs": 3000,
             "clientQuality": {"rms": 0.11, "peak": 0.83, "silenceRatio": 0.12, "clipped": false}}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActiveVersionService activeVersions;

    @Autowired
    private TestSessionRepository sessionRepository;

    @Autowired
    private TestResultRepository resultRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private AnalysisJobTransitions transitions;

    private SessionTestFlow flow;

    @BeforeEach
    void activatePool() {
        flow = new SessionTestFlow(mockMvc, objectMapper, analysisJobRepository, transitions);
        activeVersions.activate(POOL7, "KAN-182 세트 시험");
    }

    @AfterEach
    void restoreBaseline() {
        activeVersions.activate(BASELINE, "테스트 정리");
    }

    // === AC - voiceSet이 세션에 고정되어 응답과 test_session.voice_set에 남는다 ===

    @Test
    void 세트_번호가_세션에_고정되어_응답과_DB에_남는다() throws Exception {
        JsonNode created = create("{ \"voiceSet\": 2 }");

        assertEquals(POOL7, created.get("testVersion").asString());
        assertEquals(2, created.get("voiceSet").asInt());
        assertEquals(2, created.get("voiceSetCount").asInt());
        TestSession stored = sessionRepository.findById(created.get("sessionId").asString()).orElseThrow();
        assertEquals(2, stored.voiceSet());
    }

    /**
     * 생략은 서버 배정이다 (KAN-205). 난수라 값을 못 박을 수 없으니 유효 범위, 응답과 세션 행의
     * 일치, 그리고 세트 1에 고이지 않는다는 것을 본다. 세트 2개짜리 정의에서 40번이면 한쪽만
     * 나올 확률이 2 x 2^-40 이라 무작위성 때문에 깨지지 않는다.
     */
    @Test
    void 생략하면_서버가_세트를_고르고_세트_수_밖이면_400이다() throws Exception {
        Set<Integer> picked = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            JsonNode created = create("{}");
            int voiceSet = created.get("voiceSet").asInt();
            assertTrue(voiceSet >= 1 && voiceSet <= 2, "세트는 1 이상 voiceSetCount 이하다: " + voiceSet);
            assertEquals(voiceSet, sessionRepository.findById(created.get("sessionId").asString())
                    .orElseThrow().voiceSet(), "응답과 test_session.voice_set이 같은 값이다");
            picked.add(voiceSet);
        }
        assertEquals(Set.of(1, 2), picked, "세트 1에 고이지 않고 전 세트가 나온다");

        mockMvc.perform(post("/v0/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"voiceSet\": 3 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 세션이 고정한 값으로 정의를 조회하면 그 세트의 문항이 온다 - 생성 응답과 조회가 같은 세트를 말한다. */
    @Test
    void 세션이_고정한_세트로_정의를_조회하면_그_세트의_문항이_온다() throws Exception {
        JsonNode created = create("{ \"voiceSet\": 2 }");

        mockMvc.perform(get("/v0/tests/" + created.get("testVersion").asString())
                        .param("voiceSet", created.get("voiceSet").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voiceSet").value(2))
                .andExpect(jsonPath("$.items[0].itemId").value("v6"))
                .andExpect(jsonPath("$.items[8].itemId").value("v3"));
    }

    // === AC - 세션 세트 밖의 음성 itemId 업로드와 답안은 422 ITEM_NOT_IN_VERSION (풀에 있는 문항 포함) ===

    @Test
    void 세트_밖의_음성_문항은_풀에_있어도_업로드가_422다() throws Exception {
        SessionHandle session = handle(create("{ \"voiceSet\": 2 }"));

        // v4, v5는 풀에 있지만 세트 2(v6, v7, v1, v2, v3)의 문항이 아니다.
        mockMvc.perform(upload(session, "v4", "outside-4"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_IN_VERSION"));
        mockMvc.perform(upload(session, "v5", "outside-5"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_IN_VERSION"));
        // 세트의 문항은 채움 문항(v1)까지 포함해 정상 수락이다.
        mockMvc.perform(upload(session, "v6", "inside-6")).andExpect(status().isAccepted());
        mockMvc.perform(upload(session, "v1", "inside-1")).andExpect(status().isAccepted());
    }

    // === AC - 상태 일괄 조회와 진행도는 세트의 문항만 센다 ===

    @Test
    void 상태_일괄_조회는_세트의_음성_5문항만_싣는다() throws Exception {
        SessionHandle session = handle(create("{ \"voiceSet\": 2 }"));
        mockMvc.perform(upload(session, "v7", "status-7")).andExpect(status().isAccepted());

        String body = mockMvc.perform(get("/v0/sessions/" + session.id() + "/analyses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andReturn().getResponse().getContentAsString();
        JsonNode items = objectMapper.readTree(body).get("items");
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode item : items) {
            ids.add(item.get("itemId").asString());
        }
        assertEquals(List.of("v6", "v7", "v1", "v2", "v3"), ids, "세트 순서 그대로, 풀의 v4와 v5는 없다");
        assertEquals("PROCESSING", items.get(1).get("status").asString());
        assertEquals("NOT_SUBMITTED", items.get(0).get("status").asString());
    }

    @Test
    void 진행도의_total은_세트_기준_10이다() throws Exception {
        SessionHandle session = handle(create("{ \"voiceSet\": 2 }"));
        mockMvc.perform(upload(session, "v6", "progress-6")).andExpect(status().isAccepted());

        mockMvc.perform(post("/v0/sessions/" + session.id() + "/vocab-items/w1/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choiceId\": \"w1a\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .header("Idempotency-Key", "vocab-w1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answeredCount").value(2))
                // 풀은 음성 7 + 어휘 5 = 12문항이지만 세션의 분모는 세트의 10이다.
                .andExpect(jsonPath("$.totalCount").value(10));
    }

    // === AC - 세트 n 세션이 세트 n의 음성 5 + 어휘 5를 제출하면 완주이고 점수는 현행 규칙과 같다 ===

    @Test
    void 세트_2의_문항을_전부_제출하면_완주로_판정하고_현행_규칙으로_집계한다() throws Exception {
        SessionHandle session = handle(create("{ \"voiceSet\": 2 }"));
        flow.answerVocab(session, Map.of("w1", "w1a", "w2", "w2a", "w3", "w3a", "w4", "w4b", "w5", "w5b"));
        // 세트 2의 음성 5문항 - 채움 문항 v1, v2, v3 포함. 원점수 60, 70, 80, 90, 75 → 억양 75.
        flow.completeVoice(session, Map.of("v6", 60, "v7", 70, "v1", 80, "v2", 90, "v3", 75));

        flow.complete(session, "complete-set2");

        TestResult result = resultRepository.findBySessionId(session.id()).orElseThrow();
        assertEquals(2, result.voiceSet(), "결과에도 세트가 남는다 (test_result.voice_set)");
        assertEquals(75, result.intonation(), "억양 = 세트 음성 5문항 합(20점 환산) = 원점수 평균");
        assertEquals(60, result.vocabulary(), "정답 3/5");
        assertEquals(70, result.overall(), "(75 x 2 + 60) / 3 = 70");
        mockMvc.perform(get("/v0/sessions/" + session.id() + "/result")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scores.intonation").value(75))
                .andExpect(jsonPath("$.scores.overall").value(70));
    }

    @Test
    void 세트_밖_문항만_있고_세트_문항이_빠지면_세트_기준으로_미제출이다() throws Exception {
        SessionHandle session = handle(create("{ \"voiceSet\": 2 }"));
        flow.answerVocab(session);
        // v4, v5는 세트 밖이라 업로드 자체가 막히므로 repository로도 심지 않는다 - 세트 문항 v3만 비운다.
        flow.completeVoice(session, Map.of("v6", 75, "v7", 75, "v1", 75, "v2", 75));

        mockMvc.perform(post(SessionTestFlow.completeUrl(session))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .header("Idempotency-Key", "complete-missing"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RESULT_INCOMPLETE"))
                .andExpect(jsonPath("$.missingItems.length()").value(1))
                .andExpect(jsonPath("$.missingItems[0]").value("v3"));
    }

    // === AC - 재응시도 같은 규칙: 세트를 새로 고르고 이전 세션의 세트를 물려받지 않는다 ===

    @Test
    void 재응시의_세트도_서버가_고르고_이전_세션의_세트를_물려받지_않는다() throws Exception {
        JsonNode session = create("{ \"voiceSet\": 2 }");

        // 재응시를 이어 가며 세트를 생략한다 - 물려받는다면 세트 2만 나온다 (KAN-205).
        Set<Integer> picked = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            String body = mockMvc.perform(post("/v0/sessions")
                            .contentType(MediaType.APPLICATION_JSON).content("{}")
                            .header(HttpHeaders.AUTHORIZATION,
                                    "Bearer " + session.get("sessionToken").asString()))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            session = objectMapper.readTree(body);
            int voiceSet = session.get("voiceSet").asInt();
            assertEquals(voiceSet, sessionRepository.findById(session.get("sessionId").asString())
                    .orElseThrow().voiceSet());
            picked.add(voiceSet);
        }
        assertEquals(Set.of(1, 2), picked, "재응시마다 새로 고른다 - 이전 세션의 세트에 묶이지 않는다");

        String again = mockMvc.perform(post("/v0/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"voiceSet\": 2 }")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.get("sessionToken").asString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertEquals(2, objectMapper.readTree(again).get("voiceSet").asInt(), "명시하면 그 값 그대로다");
    }

    // === 헬퍼 ===

    private JsonNode create(String body) throws Exception {
        String response = mockMvc.perform(post("/v0/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private static SessionHandle handle(JsonNode created) {
        return new SessionHandle(created.get("sessionId").asString(), created.get("sessionToken").asString());
    }

    private static RequestBuilder upload(SessionHandle session, String itemId, String idempotencyKey) {
        return multipart("/v0/sessions/" + session.id() + "/voice-items/" + itemId + "/recording")
                .file(new MockMultipartFile("audio", "recording.wav", "audio/wav", WavFixtures.standardWav(3000)))
                .file(new MockMultipartFile("meta", "", "application/json",
                        VALID_META.getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .header("Idempotency-Key", idempotencyKey);
    }
}
