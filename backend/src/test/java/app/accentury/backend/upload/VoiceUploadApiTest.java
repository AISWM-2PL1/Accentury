package app.accentury.backend.upload;

import app.accentury.backend.analysis.AnalysisJobRepository;
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

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v0/sessions/{sid}/voice-items/{itemId}/recording}의 실행 가능한 명세
 * (KAN-23, API 명세서 §3.3).
 * <p>
 * 요청 제한은 전용 컨텍스트가 필요해 {@link UploadRateLimitApiTest}에서 따로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VoiceUploadApiTest {

    private static final String VALID_META = """
            {"durationMs": 3000,
             "clientQuality": {"rms": 0.11, "peak": 0.83, "silenceRatio": 0.12, "clipped": false}}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    // === 정상 흐름 ===

    @Test
    void 유효한_업로드는_202와_작업_정보를_반환한다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(upload(session, "v1", "idem-ok"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.analysisJobId", startsWith("a_")))
                .andExpect(jsonPath("$.itemId").value("v1"))
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.pollAfterMs").value(800));
    }

    @Test
    void 재녹음은_새_시도와_새_작업으로_수락된다() throws Exception {
        SessionHandle session = createSession();
        String firstJobId = uploadAndGetJobId(session, "v2", "retake-1");

        MvcResult retake = mockMvc.perform(upload(session, "v2", "retake-2"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attempt").value(2))
                .andReturn();

        assertNotEquals(firstJobId, jobId(retake));
    }

    @Test
    void 문항당_업로드_시도_상한은_5회다() throws Exception {
        // §2.5, §5.1 (2026-08-09 확정) - GPU 비용 보호. 업로드 전 로컬 재녹음은 세지 않는다
        SessionHandle session = createSession();
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(upload(session, "v4", "cap-" + i))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.attempt").value(i));
        }

        mockMvc.perform(upload(session, "v4", "cap-6"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_RETAKE_EXCEEDED"))
                // 시간이 지나도 풀리지 않는 상한이라 retryable=false - RATE_LIMITED와 다르다
                .andExpect(jsonPath("$.retryable").value(false));

        // 같은 키의 재전송은 상한과 무관하게 저장된 작업을 돌려받는다 (§5.2)
        mockMvc.perform(upload(session, "v4", "cap-5"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attempt").value(5));
    }

    @Test
    void 같은_Idempotency_Key_재전송은_작업을_중복_생성하지_않는다() throws Exception {
        SessionHandle session = createSession();
        String firstJobId = uploadAndGetJobId(session, "v3", "same-key");

        MvcResult replay = mockMvc.perform(upload(session, "v3", "same-key"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attempt").value(1))
                .andReturn();

        assertEquals(firstJobId, jobId(replay));
        assertEquals(1, analysisJobRepository.countBySessionIdAndItemId(session.id(), "v3"));
    }

    // === 인증 (§2.1) ===

    @Test
    void 인증_헤더가_없거나_모르는_토큰이면_401이다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(multipart(url(session, "v1"))
                        .file(audioPart(WavFixtures.standardWav(3000))).file(metaPart(VALID_META))
                        .header("Idempotency-Key", "no-auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

        mockMvc.perform(multipart(url(session, "v1"))
                        .file(audioPart(WavFixtures.standardWav(3000))).file(metaPart(VALID_META))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer st_wrong")
                        .header("Idempotency-Key", "bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void 다른_세션의_토큰이면_403이다() throws Exception {
        SessionHandle mine = createSession();
        SessionHandle other = createSession();

        mockMvc.perform(multipart(url(mine, "v1"))
                        .file(audioPart(WavFixtures.standardWav(3000))).file(metaPart(VALID_META))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.token())
                        .header("Idempotency-Key", "cross-session"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SESSION_FORBIDDEN"));
    }

    // === 문항 검증 ===

    @Test
    void 어휘_문항에_업로드하면_409_ITEM_WRONG_TYPE이다() throws Exception {
        mockMvc.perform(upload(createSession(), "w1", "vocab-item"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_WRONG_TYPE"));
    }

    @Test
    void 버전에_없는_문항이면_422_ITEM_NOT_IN_VERSION이다() throws Exception {
        mockMvc.perform(upload(createSession(), "zz", "no-such-item"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_IN_VERSION"));
    }

    // === 필수 헤더와 파트 (§2.2, §3.3) ===

    @Test
    void Idempotency_Key가_없으면_400이다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(multipart(url(session, "v1"))
                        .file(audioPart(WavFixtures.standardWav(3000))).file(metaPart(VALID_META))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void audio_파트가_없으면_400이다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(multipart(url(session, "v1"))
                        .file(metaPart(VALID_META))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .header("Idempotency-Key", "no-audio"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void meta_파트가_없으면_400이다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(multipart(url(session, "v1"))
                        .file(audioPart(WavFixtures.standardWav(3000)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .header("Idempotency-Key", "no-meta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void meta가_JSON_null이면_500이_아니라_400이다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(multipart(url(session, "v1"))
                        .file(audioPart(WavFixtures.standardWav(3000))).file(metaPart("null"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .header("Idempotency-Key", "null-meta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void clientQuality_필드가_빠지면_400이다() throws Exception {
        SessionHandle session = createSession();
        String missingSilenceRatio = """
                {"durationMs": 3000, "clientQuality": {"rms": 0.11, "peak": 0.83, "clipped": false}}""";

        mockMvc.perform(multipart(url(session, "v1"))
                        .file(audioPart(WavFixtures.standardWav(3000))).file(metaPart(missingSilenceRatio))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .header("Idempotency-Key", "partial-quality"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // === 오디오 규격 (§3.3) ===

    @Test
    void WAV가_아니면_415_AUDIO_FORMAT_UNSUPPORTED이다() throws Exception {
        mockMvc.perform(upload(createSession(), "v1", "not-wav",
                        "mp3인 척하는 바이트".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("AUDIO_FORMAT_UNSUPPORTED"));
    }

    @Test
    void 규격_외_샘플레이트나_스테레오는_415다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(upload(session, "v1", "hi-rate", WavFixtures.wav(44_100, 1, 16, 1000)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("AUDIO_FORMAT_UNSUPPORTED"));

        mockMvc.perform(upload(session, "v1", "stereo", WavFixtures.wav(16_000, 2, 16, 1000)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("AUDIO_FORMAT_UNSUPPORTED"));
    }

    @Test
    void 오디오가_1MB를_넘으면_413_AUDIO_TOO_LARGE다() throws Exception {
        // 33초 분량 = 약 1.06MB - 크기 검사(413)가 길이 검사(422)보다 먼저다
        mockMvc.perform(upload(createSession(), "v1", "too-large", WavFixtures.standardWav(33_000)))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("AUDIO_TOO_LARGE"));
    }

    @Test
    void 문항_최대_길이를_넘으면_422_AUDIO_TOO_LONG이다() throws Exception {
        // 상한은 전 문항 공통 상수 VOICE_MAX_DURATION_MS(10초) - 11초는 크기(352KB)는 통과하고 길이에서 걸린다
        mockMvc.perform(upload(createSession(), "v1", "too-long", WavFixtures.standardWav(11_000)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AUDIO_TOO_LONG"));
    }

    // === CORS (§2.5, KAN-31) ===

    @Test
    void 허용_오리진의_프리플라이트가_통과한다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(options(url(session, "v1"))
                        .header(HttpHeaders.ORIGIN, "https://web.test")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://web.test"));
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

    private static String url(SessionHandle session, String itemId) {
        return "/v0/sessions/" + session.id() + "/voice-items/" + itemId + "/recording";
    }

    private static MockMultipartFile audioPart(byte[] bytes) {
        return new MockMultipartFile("audio", "recording.wav", "audio/wav", bytes);
    }

    private static MockMultipartFile metaPart(String json) {
        return new MockMultipartFile("meta", "", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    /** 유효한 오디오와 meta를 채운 업로드 요청 */
    private RequestBuilder upload(SessionHandle session, String itemId, String idempotencyKey) {
        return upload(session, itemId, idempotencyKey, WavFixtures.standardWav(3000));
    }

    private RequestBuilder upload(SessionHandle session, String itemId, String idempotencyKey, byte[] audio) {
        return multipart(url(session, itemId))
                .file(audioPart(audio)).file(metaPart(VALID_META))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .header("Idempotency-Key", idempotencyKey);
    }

    private String uploadAndGetJobId(SessionHandle session, String itemId, String idempotencyKey)
            throws Exception {
        return jobId(mockMvc.perform(upload(session, itemId, idempotencyKey))
                .andExpect(status().isAccepted())
                .andReturn());
    }

    private String jobId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("analysisJobId").asString();
    }
}
