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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배포 기본값({@code trusted-proxies: []})에서의 요청 제한 (KAN-28, API 명세서 §2.5).
 * <p>
 * 다른 통합 테스트는 {@code application-test.yml}이 127.0.0.1을 신뢰하도록 켜 두어
 * <b>배포 기본값과 정반대</b>로 돈다. 그래서 KAN-28의 핵심 성질인 "신뢰받지 않는 상대는
 * {@code X-Forwarded-For} 위조로 자기 IP 창을 쪼갤 수 없다"가 {@code ClientIpsTest}
 * 단위 테스트로만 덮여 있었다 - 필터나 컨트롤러 배선에서 헤더 무조건 읽기가 되살아나도
 * 전체 스위트가 통과한다. 여기서 실제 HTTP 경로로 그 구멍을 막는다.
 * <p>
 * 두 축을 모두 본다: 본문 파싱 전에 끊는 업로드 필터(IP 축)와 인증이 없는 세션 생성(§3.1).
 * 요청 제한기는 컨텍스트 수명 동안 살아 있는 싱글턴이므로 테스트마다 접속 IP를 달리 준다 -
 * 그러지 않으면 실행 순서가 결과를 바꾼다.
 */
@SpringBootTest(properties = {
        // 빈 값 - 배포 기본값 그대로다 (application-test.yml의 신뢰 목록을 덮는다).
        "accentury.trusted-proxies=",
        "accentury.upload.rate-limit-per-minute=2",
        "accentury.session.rate-limit-per-minute=2"})
@AutoConfigureMockMvc
class TrustedProxyDefaultApiTest extends IntegrationTest {

    private static final String VALID_META = """
            {"durationMs": 3000,
             "clientQuality": {"rms": 0.11, "peak": 0.83, "silenceRatio": 0.12, "clipped": false}}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 위조한_X_Forwarded_For로_업로드_제한을_쪼갤_수_없다() throws Exception {
        // 헤더를 읽는다면 세 요청이 서로 다른 키가 되어 전부 통과한다 - 그것이 곧 우회다.
        // 신뢰 목록이 비어 있으므로 셋 다 같은 접속 IP 하나로 세어야 한다.
        String peer = "198.51.100.10";
        SessionHandle session = createSession(peer);

        mockMvc.perform(upload(session, "forge-1", peer, "10.0.0.1"))
                .andExpect(status().isAccepted());
        mockMvc.perform(upload(session, "forge-2", peer, "10.0.0.2"))
                .andExpect(status().isAccepted());

        mockMvc.perform(upload(session, "forge-3", peer, "10.0.0.3"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void 위조한_X_Forwarded_For로_세션_생성_제한을_쪼갤_수_없다() throws Exception {
        // 인증이 없는 유일한 쓰기 경로라(§3.1) IP가 유일한 키다 - 여기가 뚫리면 세션을
        // 무한히 찍어내 뒤따르는 세션 단위 제한까지 통째로 무의미해진다.
        String peer = "198.51.100.20";

        mockMvc.perform(sessionRequest(peer, "172.16.0.1")).andExpect(status().isCreated());
        mockMvc.perform(sessionRequest(peer, "172.16.0.2")).andExpect(status().isCreated());

        mockMvc.perform(sessionRequest(peer, "172.16.0.3"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    // === 헬퍼 ===

    private record SessionHandle(String id, String token) {
    }

    /** MockMvc의 기본 접속 IP는 모든 요청이 같다 - 테스트끼리 창을 나누려면 직접 준다. */
    private static RequestPostProcessor from(String peer) {
        return request -> {
            request.setRemoteAddr(peer);
            return request;
        };
    }

    private RequestBuilder sessionRequest(String peer, String forgedIp) {
        return post("/v0/sessions")
                .contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("X-Forwarded-For", forgedIp)
                .with(from(peer));
    }

    private SessionHandle createSession(String peer) throws Exception {
        MvcResult result = mockMvc.perform(post("/v0/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(from(peer)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new SessionHandle(json.get("sessionId").asString(), json.get("sessionToken").asString());
    }

    private RequestBuilder upload(SessionHandle session, String idempotencyKey, String peer,
                                  String forgedIp) {
        return multipart("/v0/sessions/" + session.id() + "/voice-items/v1/recording")
                .file(new MockMultipartFile("audio", "recording.wav", "audio/wav",
                        WavFixtures.standardWav(3000)))
                .file(new MockMultipartFile("meta", "", "application/json",
                        VALID_META.getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Forwarded-For", forgedIp)
                .with(from(peer));
    }
}
