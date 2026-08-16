package app.accentury.backend.vocab;

import app.accentury.backend.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 어휘 답안의 세션 단위 요청 제한 (KAN-28, API 명세서 §2.5).
 * <p>
 * 인증 뒤에만 닿는 경로라 IP가 아니라 세션이 키다 - NAT 뒤의 정상 응시자들이 서로의
 * 한도를 깎으면 안 된다. 정상 응시는 어휘 5문항 x 1회이므로 그 이상은 재전송이거나 남용이다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "accentury.vocab.rate-limit-per-minute=2")
class VocabAnswerRateLimitApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 세션당_한도를_넘으면_429와_Retry_After다() throws Exception {
        SessionHandle session = createSession();
        mockMvc.perform(answer(session, "w1", "w1a", "v-1")).andExpect(status().isOk());
        // 같은 키의 재전송도 요청 한 번이다 - 제한은 멱등 판별보다 앞이다
        mockMvc.perform(answer(session, "w1", "w1a", "v-1")).andExpect(status().isOk());

        mockMvc.perform(answer(session, "w2", "w2b", "v-2"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void 다른_세션은_영향을_받지_않는다() throws Exception {
        SessionHandle exhausted = createSession();
        mockMvc.perform(answer(exhausted, "w1", "w1a", "e-1")).andExpect(status().isOk());
        mockMvc.perform(answer(exhausted, "w2", "w2b", "e-2")).andExpect(status().isOk());
        mockMvc.perform(answer(exhausted, "w3", "w3a", "e-3")).andExpect(status().isTooManyRequests());

        mockMvc.perform(answer(createSession(), "w1", "w1a", "o-1")).andExpect(status().isOk());
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

    private RequestBuilder answer(SessionHandle session, String itemId, String choiceId,
                                  String idempotencyKey) {
        return post("/v0/sessions/" + session.id() + "/vocab-items/" + itemId + "/answer")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choiceId\":\"" + choiceId + "\"}");
    }
}
