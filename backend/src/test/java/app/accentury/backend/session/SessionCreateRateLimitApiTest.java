package app.accentury.backend.session;

import app.accentury.backend.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v0/sessions}의 IP 단위 요청 제한 (KAN-28, API 명세서 §2.5).
 * <p>
 * 인증이 없는 유일한 쓰기 경로다 - 호출 한 번마다 세션 행과 토큰이 생기므로,
 * 막지 않으면 반복 호출만으로 저장소를 채울 수 있다. 판정 자체의 명세는
 * {@code RateLimitsTest}가 맡고, 여기는 응답 계약(429 + Retry-After + 봉투)을 본다.
 */
@SpringBootTest(properties = "accentury.session.rate-limit-per-minute=2")
@AutoConfigureMockMvc
class SessionCreateRateLimitApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void IP당_한도를_넘으면_429와_Retry_After다() throws Exception {
        mockMvc.perform(create("9.8.7.1")).andExpect(status().isCreated());
        mockMvc.perform(create("9.8.7.1")).andExpect(status().isCreated());

        mockMvc.perform(create("9.8.7.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.retryAfterMs").isNumber())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void 다른_IP는_영향을_받지_않는다() throws Exception {
        // 한 사람의 폭주가 다른 응시자의 시작을 막으면 안 된다
        mockMvc.perform(create("9.8.7.2")).andExpect(status().isCreated());
        mockMvc.perform(create("9.8.7.2")).andExpect(status().isCreated());
        mockMvc.perform(create("9.8.7.2")).andExpect(status().isTooManyRequests());

        mockMvc.perform(create("9.8.7.3")).andExpect(status().isCreated());
    }

    private static RequestBuilder create(String clientIp) {
        return post("/v0/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                // 첫 값은 클라이언트 위조분, 마지막 값이 프록시가 붙인 실제 IP다
                .header("X-Forwarded-For", "203.0.113.99, " + clientIp);
    }
}
