package app.accentury.backend.result;

import app.accentury.backend.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /complete}의 세션 단위 요청 제한 (KAN-16 AC - 비용 보호, API 명세서 §2.5).
 * <p>
 * 한도를 2로 줄여 검증한다 - 고정 윈도우 판정과 범위별 한도 배분 자체의 명세는
 * {@code app.accentury.backend.common.RateLimitsTest}가 맡는다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "accentury.completion.rate-limit-per-minute=2")
class CompleteRateLimitApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 세션당_한도를_넘으면_429와_Retry_After다() throws Exception {
        SessionHandle session = createSession();
        // 미제출 세션이라 제한 전까지는 422다 - 제한은 완주 판정보다 먼저 걸린다.
        mockMvc.perform(complete(session, "c-1")).andExpect(status().isUnprocessableContent());
        mockMvc.perform(complete(session, "c-2")).andExpect(status().isUnprocessableContent());

        mockMvc.perform(complete(session, "c-3"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void 다른_세션은_영향을_받지_않는다() throws Exception {
        // 세션 단위인 이유 - NAT 뒤의 여러 정상 응시자가 서로의 한도를 깎으면 안 된다.
        SessionHandle exhausted = createSession();
        mockMvc.perform(complete(exhausted, "e-1")).andExpect(status().isUnprocessableContent());
        mockMvc.perform(complete(exhausted, "e-2")).andExpect(status().isUnprocessableContent());
        mockMvc.perform(complete(exhausted, "e-3")).andExpect(status().isTooManyRequests());

        mockMvc.perform(complete(createSession(), "other-1"))
                .andExpect(status().isUnprocessableContent());
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

    private org.springframework.test.web.servlet.RequestBuilder complete(
            SessionHandle session, String idempotencyKey) {
        return post("/v0/sessions/" + session.id() + "/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .header("Idempotency-Key", idempotencyKey);
    }
}
