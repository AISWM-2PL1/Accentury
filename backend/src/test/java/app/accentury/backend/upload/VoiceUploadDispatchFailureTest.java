package app.accentury.backend.upload;

import app.accentury.backend.analysis.AnalysisDispatcher;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분석 전달이 실패할 때의 동작 (KAN-23, Codex sol 리뷰 P1 반영).
 * <p>
 * 오디오를 저장하지 않으므로(FR-DP-01) 실패한 시도를 서버가 재시도할 수 없다 -
 * 작업을 PROCESSING으로 남기지 않고 RETRYABLE_FAILED로 전이해 재녹음을 유도한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VoiceUploadDispatchFailureTest {

    @TestConfiguration
    static class FailingDispatcherConfig {

        @Bean
        @Primary
        AnalysisDispatcher failingDispatcher() {
            return request -> {
                throw new IllegalStateException("AI 연결 실패 시뮬레이션");
            };
        }
    }

    private static final String VALID_META = """
            {"durationMs": 3000,
             "clientQuality": {"rms": 0.11, "peak": 0.83, "silenceRatio": 0.12, "clipped": false}}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Test
    void 전달_실패는_503이고_작업은_RETRYABLE_FAILED로_남는다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(upload(session, "dispatch-fail"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ANALYSIS_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));

        var job = analysisJobRepository
                .findBySessionIdAndItemIdAndIdempotencyKey(session.id(), "v1", "dispatch-fail")
                .orElseThrow();
        assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, job.status());
    }

    @Test
    void 실패한_키의_재전송은_저장된_상태를_돌려받고_작업을_추가하지_않는다() throws Exception {
        SessionHandle session = createSession();
        mockMvc.perform(upload(session, "replay-after-fail"))
                .andExpect(status().isServiceUnavailable());

        // 멱등 재전송은 전달 전에 반환되므로 202 + 저장된 RETRYABLE_FAILED 상태다 (§5.2)
        mockMvc.perform(upload(session, "replay-after-fail"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RETRYABLE_FAILED"));

        assertEquals(1, analysisJobRepository.countBySessionIdAndItemId(session.id(), "v1"));
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

    private RequestBuilder upload(SessionHandle session, String idempotencyKey) {
        return multipart("/v0/sessions/" + session.id() + "/voice-items/v1/recording")
                .file(new MockMultipartFile("audio", "recording.wav", "audio/wav",
                        WavFixtures.standardWav(3000)))
                .file(new MockMultipartFile("meta", "", "application/json",
                        VALID_META.getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .header("Idempotency-Key", idempotencyKey);
    }
}
