package app.accentury.backend;

import app.accentury.backend.analysis.AnalysisJob;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobStatus;
import app.accentury.backend.analysis.AnalysisJobTransitions;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc 세션 플로우(생성 → 어휘 답안 → 음성 종결 → 완료)의 공용 조립기.
 * <p>
 * 같은 헬퍼가 테스트마다 사본으로 늘어 일곱 벌(createSession)과 네 벌(answerVocab,
 * completeVoice, CORRECT_CHOICES)이 되고 시그니처까지 어긋나기 시작해 여기로 모았다
 * (2026-08-17 리뷰, {@link PropertiesFixture}와 같은 정리). API 계약이 바뀌면 이 한
 * 곳만 고친다. 스프링 빈이 아니라 테스트가 직접 조립하는 평범한 객체다 - MockMvc 없는
 * 컨텍스트까지 컴포넌트 스캔에 걸리게 하지 않기 위해서다.
 */
public final class SessionTestFlow {

    /** seed 정본의 어휘 정답표 - 전부 이대로 제출하면 단어 점수 100이다 */
    public static final Map<String, String> CORRECT_CHOICES =
            Map.of("w1", "w1a", "w2", "w2b", "w3", "w3a", "w4", "w4b", "w5", "w5a");

    /** 발급된 세션의 핸들 - 경로 파라미터(id)와 Bearer 토큰만 나른다 */
    public record SessionHandle(String id, String token) {
    }

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisJobTransitions transitions;

    public SessionTestFlow(MockMvc mockMvc, ObjectMapper objectMapper,
                           AnalysisJobRepository analysisJobRepository,
                           AnalysisJobTransitions transitions) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.analysisJobRepository = analysisJobRepository;
        this.transitions = transitions;
    }

    public SessionHandle createSession() throws Exception {
        JsonNode json = createSessionJson();
        return new SessionHandle(json.get("sessionId").asString(), json.get("sessionToken").asString());
    }

    /** 응답 본문 전체가 필요한 테스트용 (예: 필드 수 고정 검증) */
    public JsonNode createSessionJson() throws Exception {
        MvcResult result = mockMvc.perform(post("/v0/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 정답표 전체를 그대로 제출한다 - 단어 점수 100의 기본 경로 */
    public void answerVocab(SessionHandle session) throws Exception {
        answerVocab(session, CORRECT_CHOICES);
    }

    public void answerVocab(SessionHandle session, Map<String, String> choiceByItem) throws Exception {
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

    /** 음성 5문항 전부를 원점수 75로 종결한다 - 억양 평균 75의 기본 경로 */
    public void completeVoice(SessionHandle session) {
        completeVoice(session, Map.of("v1", 75, "v2", 75, "v3", 75, "v4", 75, "v5", 75));
    }

    /** 문항마다 시도 1건을 심고 주어진 원점수로 성공 종결한다 */
    public void completeVoice(SessionHandle session, Map<String, Integer> scoreByItem) {
        Instant base = Instant.now();
        for (Map.Entry<String, Integer> entry : scoreByItem.entrySet()) {
            AnalysisJob job = analysisJobRepository.save(new AnalysisJob(
                    "a_" + UUID.randomUUID(), session.id(), entry.getKey(),
                    1, "k-" + UUID.randomUUID(), AnalysisJobStatus.PROCESSING, base));
            transitions.complete(job.id(), entry.getValue(), "OK", "stub-0.1", "sv-0.3");
        }
    }

    public static String completeUrl(SessionHandle session) {
        return "/v0/sessions/" + session.id() + "/complete";
    }

    public void complete(SessionHandle session, String idempotencyKey) throws Exception {
        mockMvc.perform(post(completeUrl(session))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }
}
