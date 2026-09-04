package app.accentury.backend.upload;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.analysis.AnalysisDispatcher;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobStatus;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
@AutoConfigureMockMvc
class VoiceUploadDispatchFailureTest extends IntegrationTest {

    @TestConfiguration
    static class FailingDispatcherConfig {

        @Bean
        @Primary
        ToggleableDispatcher failingDispatcher() {
            return new ToggleableDispatcher();
        }
    }

    /**
     * 기본은 전달 실패. 시도 카운트 검증에서만 성공으로 전환한다.
     * <p>
     * 일부러 버퍼를 지우지 않는다 - 소유권이 호출과 함께 넘어온다는 계약
     * ({@link AnalysisDispatcher})을 지키는지 보려면, 서비스가 손대지 않았다는 것을
     * 여기서 확인할 수 있어야 한다.
     */
    static class ToggleableDispatcher implements AnalysisDispatcher {

        volatile boolean failing = true;

        /** 전달에 성공한 마지막 요청 - 분석으로 실제로 넘어간 값을 확인한다. */
        volatile AnalysisRequest lastDispatched;

        /** 마지막으로 넘어온 오디오 버퍼 - 지우지 않고 그대로 들고 있는다. */
        volatile byte @Nullable [] lastAudio;

        /** 복구 시험 자리를 놓아준 작업 - 회로 판정 자체는 AiCircuitBreakerTest가 본다 (KAN-28). */
        volatile @Nullable String abandoned;

        @Override
        public void dispatch(AnalysisRequest request) {
            lastAudio = request.audio();
            if (failing) {
                throw new IllegalStateException("AI 연결 실패 시뮬레이션");
            }
            lastDispatched = request;
        }

        @Override
        public void abandon(String analysisJobId) {
            abandoned = analysisJobId;
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
        dispatcher.abandoned = null;
    }

    @Test
    void 전달에_실패하면_복구_시험_자리를_놓아준다() throws Exception {
        // accepts()가 반열림 시험 자리를 이 작업 앞으로 잡아 둘 수 있다 (KAN-28) - 전달이
        // 실패하면 이 작업은 AI에 닿지 못하는데, 놓아주지 않으면 시험 한도(60초) 동안
        // 나머지 업로드가 전부 503이다. 그동안 AI는 이미 살아 있을 수 있다.
        SessionHandle session = createSession();

        mockMvc.perform(upload(session, "release-trial"))
                .andExpect(status().isServiceUnavailable());

        var job = analysisJobRepository
                .findBySessionIdAndItemIdAndIdempotencyKey(session.id(), "v1", "release-trial")
                .orElseThrow();
        assertEquals(job.id(), dispatcher.abandoned,
                "전달에 실패한 그 작업의 자리를 놓아줘야 한다");
    }

    @Test
    void 전달에_성공하면_시험_자리를_놓아주지_않는다() throws Exception {
        // 자리는 판정(성공/실패)이 날 때까지 그 작업의 것이다 - 여기서 놓아주면 큐에 남은
        // 다른 작업이 자리를 채가고, 202를 받은 업로드가 분석도 못 해 보고 실패한다.
        SessionHandle session = createSession();
        dispatcher.failing = false;

        mockMvc.perform(upload(session, "keep-trial")).andExpect(status().isAccepted());

        assertNull(dispatcher.abandoned);
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
    void 전달이_예외로_끝나도_서비스는_버퍼를_건드리지_않는다() throws Exception {
        // 소유권은 반환이 아니라 dispatch() 호출과 함께 넘어간다 (AnalysisDispatcher 계약).
        // 반환 뒤에 넘긴다고 보면, 제출에는 성공하고 그 뒤에 던지는 구현(계측 데코레이터,
        // 향후 AOP)에서 살아 있는 워커의 버퍼를 서비스가 0으로 덮어쓴다 (Codex 리뷰).
        SessionHandle session = createSession();

        mockMvc.perform(upload(session, "ownership-on-call"))
                .andExpect(status().isServiceUnavailable());

        byte[] audio = dispatcher.lastAudio;
        assertNotNull(audio, "디스패처까지 버퍼가 넘어왔어야 한다");
        // 이 디스패처는 일부러 지우지 않는다 - WAV 헤더가 0으로 덮였다면 서비스가 손댄 것이다.
        assertEquals("RIFF", new String(audio, 0, 4, StandardCharsets.US_ASCII),
                "전달 이후의 파기는 구현의 몫이다");
    }

    @Test
    void 실패한_키의_재전송은_저장된_상태를_돌려받고_작업을_추가하지_않는다() throws Exception {
        SessionHandle session = createSession();
        mockMvc.perform(upload(session, "replay-after-fail"))
                .andExpect(status().isServiceUnavailable());

        // 멱등 재전송은 전달 전에 반환되므로 202 + 저장된 RETRYABLE_FAILED 상태다 (§5.2).
        mockMvc.perform(upload(session, "replay-after-fail"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RETRYABLE_FAILED"));

        assertEquals(1, analysisJobRepository.countBySessionIdAndItemId(session.id(), "v1"));
    }

    @Test
    void 전달_실패는_시도_상한을_소모하지_않는다() throws Exception {
        // 상한(5회)의 목적은 GPU 비용 보호다 (§2.5, §5.1) - 분석에 닿지도 못한 시도까지 세면
        // 서버 장애만으로 문항이 retryable=false인 429로 영구 차단되고 세션 전체를 버려야 한다.
        SessionHandle session = createSession();
        for (int i = 1; i <= MAX_ATTEMPTS + 1; i++) {
            mockMvc.perform(upload(session, "no-consume-" + i))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ANALYSIS_UNAVAILABLE"));
        }

        // 상한을 넘겨 시도했는데도 429가 아니고, 세지 않으니 attempt도 1에 머문다.
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

        // 중간의 실패 3건은 예산에서 빠지므로 다음 성공 시도는 3이 아니라 2다.
        dispatcher.failing = false;
        mockMvc.perform(upload(session, "mixed-ok-2"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attempt").value(2));
    }

    @Test
    void 전달_실패가_섞여도_성공_5회를_넘기면_상한에_걸린다() throws Exception {
        // 실패를 세지 않는 것이 상한 자체를 무력화하지는 않는다.
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

    @Test
    void 분석에_넘기는_길이는_meta_신고값이_아니라_WAV_계산값이다() throws Exception {
        // 신고값을 그대로 넘기면 분석이 엉뚱한 메타를 받는다 (Codex sol 리뷰 P2) -
        // 길이 제한(§3.3)과 마찬가지로 정본은 서버가 WAV 헤더에서 계산한 값이다.
        SessionHandle session = createSession();
        dispatcher.failing = false;

        String lyingMeta = """
                {"durationMs": 9999,
                 "clientQuality": {"rms": 0.11, "peak": 0.83, "silenceRatio": 0.12, "clipped": false}}""";
        mockMvc.perform(multipart("/v0/sessions/" + session.id() + "/voice-items/v1/recording")
                        .file(new MockMultipartFile("audio", "recording.wav", "audio/wav",
                                WavFixtures.standardWav(3000)))
                        .file(new MockMultipartFile("meta", "", "application/json",
                                lyingMeta.getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .header("Idempotency-Key", "lying-duration"))
                .andExpect(status().isAccepted());

        assertEquals(3000, dispatcher.lastDispatched.durationMs());
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
