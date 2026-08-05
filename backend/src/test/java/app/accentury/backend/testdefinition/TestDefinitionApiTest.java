package app.accentury.backend.testdefinition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /v0/tests/{testVersion}}의 실행 가능한 명세 (KAN-10, API 명세서 §3.2).
 * <p>
 * 인증 없이 호출한다 (§2.1 - 인증 불필요 엔드포인트).
 * 구버전 픽스처 {@code gn-2026.07.0}은 테스트 classpath에만 있는 seed다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TestDefinitionApiTest {

    private static final String ACTIVE = "/v0/tests/gn-2026.08.1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 활성_버전_조회는_200과_명세의_5개_최상위_필드를_반환한다() throws Exception {
        mockMvc.perform(get(ACTIVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testVersion").value("gn-2026.08.1"))
                .andExpect(jsonPath("$.scoreVersion").value("sv-0.3"))
                .andExpect(jsonPath("$.dialect").value("GYEONGNAM"))
                .andExpect(jsonPath("$.estimatedDurationSec").value(240))
                .andExpect(jsonPath("$.items.length()").value(10))
                // §3.2 응답은 정확히 5개 필드 - 늘면 이 테스트가 알려준다
                .andExpect(jsonPath("$").value(aMapWithSize(5)));
    }

    // === KAN-10 AC - VOICE 5문항·VOCABULARY 5문항이 구분되고 순서가 고정된다 ===

    @Test
    void 음성5_어휘5가_seq_순서로_교차_출제된다() throws Exception {
        JsonNode items = fetchActiveItems();
        // KAN-10 정본 표 - 출제 순서는 음성·어휘 교차 (v1→w1→...→v5→w5)
        List<String> expectedOrder = List.of("v1", "w1", "v2", "w2", "v3", "w3", "v4", "w4", "v5", "w5");
        for (int i = 0; i < expectedOrder.size(); i++) {
            JsonNode item = items.get(i);
            assertEquals(expectedOrder.get(i), item.get("itemId").asString());
            assertEquals(i + 1, item.get("seq").asInt());
            assertEquals(i % 2 == 0 ? "VOICE" : "VOCABULARY", item.get("type").asString());
        }
    }

    // === KAN-10 AC - 모든 VOICE 문항에 예측 F0 가이드 곡선이 포함된다 ===

    @Test
    void 모든_VOICE_문항은_guideF0와_최대_녹음_길이를_포함한다() throws Exception {
        int voiceCount = 0;
        for (JsonNode item : fetchActiveItems()) {
            if (!"VOICE".equals(item.get("type").asString())) {
                continue;
            }
            voiceCount++;
            assertEquals(10000, item.get("maxDurationMs").asInt(), "음성 문항은 최대 10초다 (KAN-23)");
            JsonNode guideF0 = item.get("guideF0");
            assertEquals("semitone", guideF0.get("unit").asString());
            assertEquals(10, guideF0.get("frameIntervalMs").asInt());
            assertTrue(guideF0.get("values").size() > 0, "guideF0.values는 비어 있으면 안 된다");
            // VOICE 문항은 정확히 6개 필드 - choices·정답 없음
            assertEquals(6, item.size(), "VOICE 문항 필드가 늘었다: " + item.propertyNames());
        }
        assertEquals(5, voiceCount);
    }

    // === KAN-10 AC - 응답에 정답 정보가 포함되지 않는다 ===

    @Test
    void VOCABULARY_문항은_4지선다이고_정오_정보가_없다() throws Exception {
        int vocabularyCount = 0;
        for (JsonNode item : fetchActiveItems()) {
            if (!"VOCABULARY".equals(item.get("type").asString())) {
                continue;
            }
            vocabularyCount++;
            // 어휘 문항은 정확히 5개 필드 - 정답·음성 필드 없음
            assertEquals(5, item.size(), "VOCABULARY 문항 필드가 늘었다: " + item.propertyNames());
            JsonNode choices = item.get("choices");
            assertEquals(4, choices.size(), "어휘 문항은 4지선다다 (SRS 확정)");
            for (JsonNode choice : choices) {
                // 선택지는 choiceId·text 뿐 - 정오 표시가 있으면 안 된다 (KAN-13 정오 미노출)
                assertEquals(2, choice.size(), "선택지 필드가 늘었다: " + choice.propertyNames());
            }
        }
        assertEquals(5, vocabularyCount);
    }

    @Test
    void 응답_어디에도_정답과_기준_음성_필드가_없다() throws Exception {
        String body = mockMvc.perform(get(ACTIVE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // KAN-10 AC - 정답(correctChoiceId) 미포함
        assertFalse(body.contains("correct"), "정답 정보가 응답에 새면 안 된다");
        // KAN-10 AC - 기준 음성 관련 필드 없음 (범위 제외, 2026-07-31)
        assertFalse(body.contains("referenceAudio"), "기준 음성 필드는 범위 제외다 (KAN-12)");
    }

    // === §3.2 - 불변·Cache-Control: immutable, ETag 지원 ===

    @Test
    void 버전_경로는_ETag와_불변_캐싱을_지원한다() throws Exception {
        MvcResult first = mockMvc.perform(get(ACTIVE))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(header().string("Cache-Control", containsString("immutable")))
                .andReturn();

        String etag = first.getResponse().getHeader("ETag");
        mockMvc.perform(get(ACTIVE).header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));
    }

    // === KAN-10 AC - 같은 세션의 반복 조회는 같은 테스트 버전을 반환한다 ===

    @Test
    void 세션이_고정한_버전을_반복_조회해도_같은_정의가_온다() throws Exception {
        String session = mockMvc.perform(post("/v0/sessions"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String testVersion = objectMapper.readTree(session).get("testVersion").asString();

        String firstBody = mockMvc.perform(get("/v0/tests/" + testVersion))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondBody = mockMvc.perform(get("/v0/tests/" + testVersion))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(firstBody, secondBody, "버전 경로는 불변이다 (§5.4)");
    }

    // === KAN-10 AC - 이전 버전 세션이 신규 발행 후에도 정상 완료된다 ===

    @Test
    void 신규_발행_후에도_이전_버전_정의는_계속_제공된다() throws Exception {
        // 활성 버전은 gn-2026.08.1이지만, 먼저 발행된 gn-2026.07.0에 고정된 세션도
        // 자기 정의를 계속 받는다 - 활성 전환이 진행 중 세션에 영향을 주지 않는다 (KAN-26 AC)
        mockMvc.perform(get("/v0/tests/gn-2026.07.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testVersion").value("gn-2026.07.0"))
                .andExpect(jsonPath("$.items.length()").value(10));
    }

    // === KAN-10 요구 - 미발행·경북 콘텐츠는 외부에 제공하지 않는다 ===

    @Test
    void 미발행_버전은_404_공통_오류_봉투다() throws Exception {
        mockMvc.perform(get("/v0/tests/gb-2026.08.1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    private JsonNode fetchActiveItems() throws Exception {
        String body = mockMvc.perform(get(ACTIVE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("items");
    }
}
