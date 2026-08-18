package app.accentury.backend.upload;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.analysis.AnalysisDispatcher;
import app.accentury.backend.analysis.AnalysisJobRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 회로가 열린 동안의 업로드 동작 (KAN-28 AC - "AI 장애가 일반 세션, 콘텐츠 API로
 * 전파되지 않는다", API 명세서 §3.3, §4.2).
 * <p>
 * 회로가 열리면 업로드는 <b>작업을 만들지 않고</b> 503으로 끊는다 (2026-08-16 확정).
 * 오디오를 저장하지 않으므로(FR-DP-01) 받아 둬도 나중에 다시 보낼 수 없고, 작업을
 * 만들면 사용자의 문항당 시도 예산(§2.5)만 깎이기 때문이다.
 */
@AutoConfigureMockMvc
class VoiceUploadCircuitOpenApiTest extends IntegrationTest {

    private static final String VALID_META = """
            {"durationMs": 3000,
             "clientQuality": {"rms": 0.11, "peak": 0.83, "silenceRatio": 0.12, "clipped": false}}""";

    @TestConfiguration
    static class OpenCircuitDispatcherConfig {

        @Bean
        @Primary
        CircuitDispatcher circuitDispatcher() {
            return new CircuitDispatcher();
        }
    }

    /** 회로 상태를 테스트가 직접 여닫는 디스패처 - 실제 판정은 AiCircuitBreakerTest가 본다. */
    static class CircuitDispatcher implements AnalysisDispatcher {

        volatile boolean open;

        /** 반열림 흉내 - available()이 시험 자리를 잡아 한 번만 true를 준다. */
        volatile boolean singleTrial;
        volatile boolean trialTaken;
        volatile int dispatches;

        @Override
        public boolean accepts(String analysisJobId) {
            if (open) {
                return false;
            }
            if (!singleTrial) {
                return true;
            }
            if (trialTaken) {
                return false;
            }
            trialTaken = true;
            return true;
        }

        @Override
        public void dispatch(AnalysisRequest request) {
            dispatches++;
            request.wipeAudio();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CircuitDispatcher dispatcher;

    @Autowired
    private AnalysisJobRepository jobRepository;

    @BeforeEach
    void closeCircuit() {
        // 컨텍스트를 공유하므로 앞 테스트가 열어 둔 회로를 물려받지 않게 되돌린다.
        dispatcher.open = false;
        dispatcher.singleTrial = false;
        dispatcher.trialTaken = false;
        dispatcher.dispatches = 0;
    }

    @Test
    void 회로가_열려_있으면_503이고_작업이_생기지_않는다() throws Exception {
        SessionHandle session = createSession();
        dispatcher.open = true;

        mockMvc.perform(upload(session, "c-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ANALYSIS_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));

        assertEquals(0, jobRepository.countBySessionIdAndItemId(session.id(), "v1"),
                "분석 작업이 만들어지면 시도 예산만 깎이고 영원히 끝나지 않는다");
        assertEquals(0, dispatcher.dispatches);
    }

    @Test
    void 회로가_열려도_문항당_시도_예산은_깎이지_않는다() throws Exception {
        // 서버 사정으로 상한(§2.5 - 문항당 5회)이 소모되면, 복구된 뒤 정상 응시자가
        // 자기 잘못 없이 429 RATE_RETAKE_EXCEEDED에 막힌다.
        SessionHandle session = createSession();
        dispatcher.open = true;
        for (int i = 0; i < VoiceUploadService.MAX_ATTEMPTS_PER_ITEM + 1; i++) {
            mockMvc.perform(upload(session, "budget-" + i))
                    .andExpect(status().isServiceUnavailable());
        }

        dispatcher.open = false;

        mockMvc.perform(upload(session, "recovered"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attempt").value(1));
    }

    @Test
    void 회로가_열려도_이미_접수된_시도의_재전송은_그대로_받는다() throws Exception {
        // 같은 키의 재전송은 저장된 작업을 돌려주는 것뿐이라 AI를 부르지 않는다 (§5.2) -
        // 여기서 503을 주면 네트워크가 끊긴 정상 사용자가 접수된 시도를 잃는다.
        SessionHandle session = createSession();
        mockMvc.perform(upload(session, "replay")).andExpect(status().isAccepted());

        dispatcher.open = true;

        mockMvc.perform(upload(session, "replay"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attempt").value(1));
        assertEquals(1, jobRepository.countBySessionIdAndItemId(session.id(), "v1"));
    }

    @Test
    void 시도_상한에_걸린_업로드는_반열림의_시험_자리를_뺏지_않는다() throws Exception {
        // 반열림에서 accepts()는 복구 시험 자리를 잡는 호출이다 - 어차피 429로 거절될
        // 요청이 그 자리를 물고 놓아주지 않으면, 멀쩡한 다른 세션의 복구가 시험 한도만큼
        // 늦어진다 (Codex sol 리뷰 P2).
        SessionHandle capped = createSession();
        for (int i = 0; i < VoiceUploadService.MAX_ATTEMPTS_PER_ITEM; i++) {
            mockMvc.perform(upload(capped, "cap-" + i)).andExpect(status().isAccepted());
        }
        dispatcher.singleTrial = true;

        mockMvc.perform(upload(capped, "over-cap"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_RETAKE_EXCEEDED"));

        assertFalse(dispatcher.trialTaken, "거절될 요청이 시험 자리를 잡으면 안 된다");
        mockMvc.perform(upload(createSession(), "trial")).andExpect(status().isAccepted());
    }

    @Test
    void AI_장애가_세션과_테스트_정의_API로_전파되지_않는다() throws Exception {
        // AC - 분석이 죽어도 응시 시작과 문항 조회는 살아 있어야 한다.
        // 두 경로는 AI를 부르지 않으므로 회로와 무관하다.
        dispatcher.open = true;

        SessionHandle session = createSession();
        mockMvc.perform(get("/v0/tests/gn-2026.08.1")).andExpect(status().isOk());
        mockMvc.perform(post("/v0/sessions/" + session.id() + "/vocab-items/w1/answer")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .header("Idempotency-Key", "vocab-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choiceId\":\"w1a\"}"))
                .andExpect(status().isOk());
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
