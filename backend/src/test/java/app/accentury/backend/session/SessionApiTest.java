package app.accentury.backend.session;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v0/sessions}의 실행 가능한 명세 (KAN-9, API 명세서 §3.1).
 * <p>
 * H2 인메모리로 전체 스택(컨트롤러→서비스→JPA)을 검증한다 - Docker 불필요.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // === KAN-9 AC - 유효한 요청은 새 세션을 반환한다 ===

    @Test
    void 유효한_요청은_201과_명세의_5개_필드를_반환한다() throws Exception {
        mockMvc.perform(post("/v0/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "campaignToken": "kko_a1b2",
                                  "client": { "platform": "IOS", "appVersion": "0.1.0" } }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(startsWith("s_")))
                .andExpect(jsonPath("$.sessionToken").value(startsWith("st_")))
                // KAN-9 AC - 응답에 testVersion과 scoreVersion이 모두 포함된다
                .andExpect(jsonPath("$.testVersion").value("gn-2026.08.1"))
                .andExpect(jsonPath("$.scoreVersion").value("sv-0.3"))
                .andExpect(jsonPath("$.expiresAt").exists())
                // §3.1 응답은 정확히 5개 필드 - 늘면 이 테스트가 알려준다
                .andExpect(jsonPath("$").value(aMapWithSize(5)));
    }

    @Test
    void 바디_없이도_세션이_생성된다() throws Exception {
        // §3.1 - campaignToken과 client 모두 optional. 웹(KAN-31)은 아무 정보 없이 시작할 수 있다
        mockMvc.perform(post("/v0/sessions"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(startsWith("s_")));
    }

    @Test
    void expiresAt은_생성_시점부터_TTL_30분_후다() throws Exception {
        Instant before = Instant.now();
        String body = mockMvc.perform(post("/v0/sessions"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Instant after = Instant.now();

        Instant expiresAt = Instant.parse(objectMapper.readTree(body).get("expiresAt").asString());
        Duration ttl = Duration.ofMinutes(30);
        assertTrue(!expiresAt.isBefore(before.plus(ttl)) && !expiresAt.isAfter(after.plus(ttl)),
                "expiresAt은 요청 시각 + 30분이어야 한다: " + expiresAt);
    }

    // === KAN-9 AC - 재응시 호출이 이전 세션과 서버 이력을 연결하지 않는다 ===

    @Test
    void 재응시는_완전히_독립된_새_세션이다() throws Exception {
        JsonNode first = createSession();
        JsonNode second = createSession();

        assertNotEquals(first.get("sessionId").asString(), second.get("sessionId").asString());
        assertNotEquals(first.get("sessionToken").asString(), second.get("sessionToken").asString());
    }

    // === 검증 실패 - 공통 오류 봉투 400 (§2.3, §2.4) ===

    @Test
    void 목록에_없는_platform은_400_VALIDATION_FAILED다() throws Exception {
        mockMvc.perform(post("/v0/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"client\": { \"platform\": \"WINDOWS\" } }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 형식_밖의_campaignToken은_400이다() throws Exception {
        // 저장되는 값이므로 안전한 문자만 - 로그 위조와 인코딩 문제 차단
        mockMvc.perform(post("/v0/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"campaignToken\": \"한글 토큰!\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private JsonNode createSession() throws Exception {
        String body = mockMvc.perform(post("/v0/sessions"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
