package app.accentury.backend.upload;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IP 단위 업로드 제한의 API 명세 (KAN-23, 명세서 §2.5).
 * <p>
 * 낮은 한도로 전용 컨텍스트를 띄운다 - 본 업로드 테스트({@link VoiceUploadApiTest})는
 * 넉넉한 한도로 실행돼 서로 간섭하지 않는다. X-Forwarded-For로 IP를 분리해
 * 같은 컨텍스트 안의 다른 검증과도 충돌하지 않게 한다.
 */
@SpringBootTest(properties = "accentury.upload.rate-limit-per-minute=2")
@AutoConfigureMockMvc
class UploadRateLimitApiTest {

    private static final String VALID_META = """
            {"durationMs": 3000,
             "clientQuality": {"rms": 0.11, "peak": 0.83, "silenceRatio": 0.12, "clipped": false}}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 한도_초과_업로드는_429와_Retry_After를_반환한다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(upload(session, "key-1", "9.9.9.1")).andExpect(status().isAccepted());
        mockMvc.perform(upload(session, "key-2", "9.9.9.1")).andExpect(status().isAccepted());

        mockMvc.perform(upload(session, "key-3", "9.9.9.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.retryAfterMs").isNumber())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void 다른_IP는_제한에_걸리지_않는다() throws Exception {
        SessionHandle session = createSession();
        mockMvc.perform(upload(session, "fill-1", "9.9.9.2")).andExpect(status().isAccepted());
        mockMvc.perform(upload(session, "fill-2", "9.9.9.2")).andExpect(status().isAccepted());
        mockMvc.perform(upload(session, "fill-3", "9.9.9.2")).andExpect(status().isTooManyRequests());

        mockMvc.perform(upload(session, "other-ip", "9.9.9.3")).andExpect(status().isAccepted());
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

    private RequestBuilder upload(SessionHandle session, String idempotencyKey, String clientIp) {
        return multipart("/v0/sessions/" + session.id() + "/voice-items/v1/recording")
                .file(new MockMultipartFile("audio", "recording.wav", "audio/wav",
                        WavFixtures.standardWav(3000)))
                .file(new MockMultipartFile("meta", "", "application/json",
                        VALID_META.getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Forwarded-For", clientIp);
    }
}
