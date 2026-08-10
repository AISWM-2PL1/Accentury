package app.accentury.backend.upload;

import app.accentury.backend.analysis.AnalysisDispatcher;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobStatus;
import org.junit.jupiter.api.BeforeEach;
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
        ToggleableDispatcher failingDispatcher() {
            return new ToggleableDispatcher();
        }
    }

    /** 기본은 전달 실패. 시도 카운트 검증에서만 성공으로 전환한다 */
    static class ToggleableDispatcher implements AnalysisDispatcher {

        volatile boolean failing = true;

        @Override
        public void dispatch(AnalysisRequest request) {
            if (failing) {
                throw new IllegalStateException("AI 연결 실패 시뮬레이션");
            }
        }
    }

    private static final int MAX_ATTEMPTS = VoiceUploadService.MAX_ATTEMPTS_PER_ITEM;

    private static final String VALID_META = """
            {"durationMs": 3000,
             "clientQuality": {"rms": 0.11, "peak": 0.83, "silenceRatio": 0.12, "clipped": false}}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private ToggleableDispatcher dispatcher;

    @BeforeEach
    void 기본은_전달_실패() {
        dispatcher.failing = true;
    }

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

    @Test
    void 전달_실패는_시도_상한을_소모하지_않는다() throws Exception {
        // 상한(5회)의 목적은 GPU 비용 보호다 (§2.5, §5.1) - 분석에 닿지도 못한 시도까지 세면
        // 서버 장애만으로 문항이 retryable=false인 429로 영구 차단되고 세션 전체를 버려야 한다
        SessionHandle session = createSession();
        for (int i = 1; i <= MAX_ATTEMPTS + 1; i++) {
            mockMvc.perform(upload(session, "no-consume-" + i))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ANALYSIS_UNAVAILABLE"));
        }

        // 상한을 넘겨 시도했는데도 429가 아니고, 세지 않으니 attempt도 1에 머문다
        for (int i = 1; i <= MAX_ATTEMPTS + 1; i++) {
            var job = analysisJobRepository
                    .findBySessionIdAndItemIdAndIdempotencyKey(session.id(), "v1", "no-consume-" + i)
                    .orElseThrow();
            assertEquals(AnalysisJobStatus.RETRYABLE_FAILED, job.status());
            assertEquals(1, job.attempt());
        }
    }

    @Test
    void 전달에_성공한_시도만_attempt로_센다() throws Exception {
        SessionHandle session = createSession();

        dispatcher.failing = false;
        mockMvc.perform(upload(session, "mixed-ok-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attempt").value(1));

        dispatcher.failing = true;
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(upload(session, "mixed-fail-" + i))
                    .andExpect(status().isServiceUnavailable());
        }

        // 중간의 실패 3건은 예산에서 빠지므로 다음 성공 시도는 3이 아니라 2다
        dispatcher.failing = false;
        mockMvc.perform(upload(session, "mixed-ok-2"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attempt").value(2));
    }

    @Test
    void 전달_실패가_섞여도_성공_5회를_넘기면_상한에_걸린다() throws Exception {
        // 실패를 세지 않는 것이 상한 자체를 무력화하지는 않는다
        SessionHandle session = createSession();
        for (int i = 1; i <= MAX_ATTEMPTS; i++) {
            dispatcher.failing = true;
            mockMvc.perform(upload(session, "cap-fail-" + i))
                    .andExpect(status().isServiceUnavailable());

            dispatcher.failing = false;
            mockMvc.perform(upload(session, "cap-ok-" + i))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.attempt").value(i));
        }

        mockMvc.perform(upload(session, "cap-over"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_RETAKE_EXCEEDED"))
                .andExpect(jsonPath("$.retryable").value(false));
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
