package app.accentury.backend.result;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.SessionTestFlow;
import app.accentury.backend.SessionTestFlow.SessionHandle;
import app.accentury.backend.analysis.AnalysisJob;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobStatus;
import app.accentury.backend.analysis.AnalysisJobTransitions;
import app.accentury.backend.analytics.DailyCounter;
import app.accentury.backend.analytics.DailyCounterRepository;
import app.accentury.backend.analytics.Traffic;
import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.testdefinition.ActiveVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * sv-0.4 전환의 실행 가능한 명세 (KAN-200 AC - 발행과 활성 전환).
 * <p>
 * 점수 버전 전환은 새 정의({@code gn-2026.09.2}, V8) 발행 + 활성 전환이다 (§5.4). 전환 뒤
 * 새 세션은 sv-0.4로 집계되고, 전환 전에 만든 세션은 생성 시점에 고정한 sv-0.3으로 그대로
 * 집계되어야 한다. 두 버전이 같은 날 섞여도 {@code daily_counter}는 scoreVersion 축으로
 * 따로 쌓인다 (KAN-106).
 * <p>
 * 원점수 95 x 5 + 단어 60의 같은 입력이 sv-0.3에서는 억양 95, 종합 83이고 sv-0.4에서는
 * 억양 90(95 x 0.95 = 90.25), 종합 80이다 - 티켓 AC의 예시 그대로다.
 */
@AutoConfigureMockMvc
class ScoreVersionRolloutTest extends IntegrationTest {

    /** 마이그레이션이 최초 활성으로 지정한 버전 (sv-0.3). */
    private static final String BASELINE = "gn-2026.08.1";

    /** gn-2026.09.1의 본문에 scoreVersion만 sv-0.4로 바꾼 재발행 (V8). */
    private static final String REISSUE = "gn-2026.09.2";

    /** 재발행 세트 1의 어휘 정답 (gn-2026.09.1과 같다 - e2e_smoke.py EXPECTED_VOCABULARY 근거). */
    private static final Map<String, String> REISSUE_SET1_CORRECT =
            Map.of("w1", "w1b", "w2", "w2c", "w3", "w3a", "w4", "w4a", "w5", "w5c");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private AnalysisJobTransitions transitions;

    @Autowired
    private ActiveVersionService activeVersions;

    @Autowired
    private DailyCounterRepository counters;

    @Autowired
    private AccenturyProperties properties;

    private SessionTestFlow flow;

    @BeforeEach
    void setUpFlow() {
        flow = new SessionTestFlow(mockMvc, objectMapper, analysisJobRepository, transitions);
    }

    @AfterEach
    void restoreBaseline() {
        activeVersions.activate(BASELINE, "테스트 정리");
    }

    @Test
    void 전환_뒤_새_세션은_sv04_전환_전_세션은_sv03으로_집계된다() throws Exception {
        assertEquals(BASELINE, activeVersions.current().testVersion(), "전제: baseline이 활성이다");
        long sv03CompletedBefore = completed(BASELINE, "sv-0.3");
        long sv04CompletedBefore = completed(REISSUE, "sv-0.4");

        // 전환 전 세션 - 생성 시점의 sv-0.3에 고정된다.
        SessionHandle before = flow.createSession();

        activeVersions.activate(REISSUE, "KAN-200 sv-0.4 전환");
        // 전환 뒤 세션 - 응답부터 sv-0.4다. 세트 1을 못 박는 것은 정답표를 상수로 두기 위해서다 (KAN-205).
        MvcResult created = mockMvc.perform(post("/v0/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"voiceSet\": 1 }"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.testVersion").value(REISSUE))
                .andExpect(jsonPath("$.scoreVersion").value("sv-0.4"))
                .andReturn();
        JsonNode json = objectMapper.readTree(created.getResponse().getContentAsString());
        SessionHandle after = new SessionHandle(json.get("sessionId").asString(), json.get("sessionToken").asString());

        // 같은 입력: 원점수 95 x 5, 정답 3/5 (단어 60).
        flow.answerVocab(before, threeCorrect(SessionTestFlow.CORRECT_CHOICES));
        completeVoice(before, "sv-0.3", 95);
        flow.answerVocab(after, threeCorrect(REISSUE_SET1_CORRECT));
        completeVoice(after, "sv-0.4", 95);
        flow.complete(before, "before");
        flow.complete(after, "after");

        // sv-0.3: 억양 95, 종합 (95 x 2 + 60) / 3 = 83.33 → 83
        mockMvc.perform(result(before))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testVersion").value(BASELINE))
                .andExpect(jsonPath("$.scoreVersion").value("sv-0.3"))
                .andExpect(jsonPath("$.scores.intonation").value(95))
                .andExpect(jsonPath("$.scores.vocabulary").value(60))
                .andExpect(jsonPath("$.scores.overall").value(83))
                .andExpect(jsonPath("$.tier.code").value("NATIVE"));
        // sv-0.4: 억양 95 x 0.95 = 90.25 → 90, 종합 (90 x 2 + 60) / 3 = 80 → 경남 토박이
        mockMvc.perform(result(after))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testVersion").value(REISSUE))
                .andExpect(jsonPath("$.scoreVersion").value("sv-0.4"))
                .andExpect(jsonPath("$.scores.intonation").value(90))
                .andExpect(jsonPath("$.scores.vocabulary").value(60))
                .andExpect(jsonPath("$.scores.overall").value(80))
                .andExpect(jsonPath("$.tier.code").value("NATIVE"));

        // 원점수는 그대로 남고 전처리 값은 저장되지 않는다 (AC - analysis_job.intonation_score).
        List<AnalysisJob> jobs = analysisJobRepository.findAll().stream()
                .filter(job -> job.sessionId().equals(after.id())).toList();
        assertEquals(5, jobs.size());
        jobs.forEach(job -> assertEquals(95, job.intonationScore()));

        // daily_counter는 scoreVersion 축으로 두 버전을 따로 쌓는다 (KAN-106).
        assertEquals(1, completed(BASELINE, "sv-0.3") - sv03CompletedBefore);
        assertEquals(1, completed(REISSUE, "sv-0.4") - sv04CompletedBefore);
    }

    /** 앞 세 문항은 정답, 나머지 둘은 정답이 아닌 선택지로 답한다 - 단어 점수 60. */
    private static Map<String, String> threeCorrect(Map<String, String> correct) {
        return Map.of(
                "w1", correct.get("w1"), "w2", correct.get("w2"), "w3", correct.get("w3"),
                "w4", wrongChoice("w4", correct.get("w4")), "w5", wrongChoice("w5", correct.get("w5")));
    }

    private static String wrongChoice(String itemId, String correctChoiceId) {
        return correctChoiceId.equals(itemId + "a") ? itemId + "b" : itemId + "a";
    }

    /** 음성 5문항 전부를 같은 원점수로 종결한다. AI 응답의 scoreVersion 회신도 세션 버전에 맞춘다. */
    private void completeVoice(SessionHandle session, String scoreVersion, int rawScore) {
        Instant base = Instant.now();
        for (String itemId : List.of("v1", "v2", "v3", "v4", "v5")) {
            AnalysisJob job = analysisJobRepository.save(new AnalysisJob(
                    "a_" + UUID.randomUUID(), session.id(), itemId,
                    1, "k-" + UUID.randomUUID(), AnalysisJobStatus.PROCESSING, base));
            transitions.complete(job.id(), rawScore, "OK", "stub-0.1", scoreVersion);
        }
    }

    private long completed(String testVersion, String scoreVersion) {
        String id = DailyCounter.idOf(LocalDate.now(properties.analytics().zone()),
                testVersion, scoreVersion, Traffic.REAL);
        return counters.findById(id).map(DailyCounter::sessionsCompleted).orElse(0L);
    }

    private static org.springframework.test.web.servlet.RequestBuilder result(SessionHandle session) {
        return get("/v0/sessions/" + session.id() + "/result")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token());
    }
}
