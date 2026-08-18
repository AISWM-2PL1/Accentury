package app.accentury.backend.upload;

import app.accentury.backend.IntegrationTest;
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
 * 음성 업로드의 세션 단위 요청 제한 (KAN-28, API 명세서 §2.5 - IP와 세션 이중 제한).
 * <p>
 * IP 제한({@link UploadRateLimitApiTest})은 NAT 뒤 다수 사용자를 고려해 느슨하다 -
 * 세션 하나가 그 여유를 혼자 쓰지 못하게 막는 두 번째 축이 여기다. IP를 갈라 보내도
 * 같은 세션이면 걸린다는 것이 핵심이다.
 */
@SpringBootTest(properties = "accentury.upload.session-rate-limit-per-minute=2")
@AutoConfigureMockMvc
class UploadSessionRateLimitApiTest extends IntegrationTest {

    private static final String VALID_META = """
            {"durationMs": 3000,
             "clientQuality": {"rms": 0.11, "peak": 0.83, "silenceRatio": 0.12, "clipped": false}}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 세션당_한도를_넘으면_IP를_바꿔도_429다() throws Exception {
        SessionHandle session = createSession();
        mockMvc.perform(upload(session, "s-1", "8.8.8.1")).andExpect(status().isAccepted());
        mockMvc.perform(upload(session, "s-2", "8.8.8.2")).andExpect(status().isAccepted());

        mockMvc.perform(upload(session, "s-3", "8.8.8.3"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void 다른_세션은_같은_IP라도_영향을_받지_않는다() throws Exception {
        // 공유 IP 뒤의 정상 응시자를 서로 막지 않는 것이 세션 단위로 나눈 이유다.
        SessionHandle exhausted = createSession();
        mockMvc.perform(upload(exhausted, "e-1", "8.8.9.1")).andExpect(status().isAccepted());
        mockMvc.perform(upload(exhausted, "e-2", "8.8.9.1")).andExpect(status().isAccepted());
        mockMvc.perform(upload(exhausted, "e-3", "8.8.9.1")).andExpect(status().isTooManyRequests());

        mockMvc.perform(upload(createSession(), "o-1", "8.8.9.1")).andExpect(status().isAccepted());
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
