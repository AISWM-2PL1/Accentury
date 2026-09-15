package app.accentury.backend.feedback;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.SessionTestFlow;
import app.accentury.backend.SessionTestFlow.SessionHandle;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobTransitions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이용 후기의 세션 단위 요청 제한 (KAN-211, API 명세서 §2.5).
 * <p>
 * 인증 뒤에만 닿는 경로라 IP가 아니라 세션이 키다 - NAT 뒤의 정상 응시자들이 서로의
 * 한도를 깎으면 안 된다. 세션당 후기는 하나이므로 그 이상은 재전송이거나 남용이다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "accentury.feedback.rate-limit-per-minute=2")
class FeedbackRateLimitApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private AnalysisJobTransitions transitions;

    private SessionTestFlow flow;

    @BeforeEach
    void setUp() {
        flow = new SessionTestFlow(mockMvc, objectMapper, analysisJobRepository, transitions);
    }

    @Test
    void 세션당_한도를_넘으면_429와_Retry_After다() throws Exception {
        SessionHandle session = completedSession();
        mockMvc.perform(feedback(session, "r-1")).andExpect(status().isCreated());
        // 같은 키의 재전송도 요청 한 번이다 - 제한은 멱등 판별보다 앞이다.
        mockMvc.perform(feedback(session, "r-1")).andExpect(status().isOk());

        mockMvc.perform(feedback(session, "r-2"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void 다른_세션은_영향을_받지_않는다() throws Exception {
        SessionHandle exhausted = completedSession();
        mockMvc.perform(feedback(exhausted, "e-1")).andExpect(status().isCreated());
        // 두 번째부터는 새 키라 409지만, 요청 제한은 그 앞에서 소모된다.
        mockMvc.perform(feedback(exhausted, "e-2")).andExpect(status().isConflict());
        mockMvc.perform(feedback(exhausted, "e-3")).andExpect(status().isTooManyRequests());

        mockMvc.perform(feedback(completedSession(), "o-1")).andExpect(status().isCreated());
    }

    // === 헬퍼 ===

    private SessionHandle completedSession() throws Exception {
        SessionHandle session = flow.createSession();
        flow.answerVocab(session);
        flow.completeVoice(session);
        flow.complete(session, "complete-" + session.id());
        return session;
    }

    private RequestBuilder feedback(SessionHandle session, String idempotencyKey) {
        return post("/v0/sessions/" + session.id() + "/feedback")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"요청 제한 검증용 후기\"}");
    }
}
