package app.accentury.backend.testdefinition;

import app.accentury.backend.common.AccenturyProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 활성 버전은 설정({@code accentury.test-version})이 정본이다.
     * <p>
     * 경로를 리터럴로 박으면, 구 seed가 계속 발행 상태로 남는 설계(KAN-26 버전 불변) 때문에
     * 버전을 로테이션해도 테스트가 조용히 통과하면서 은퇴한 seed만 검사하게 된다 (Claude 리뷰 P3).
     */
    @Autowired
    private AccenturyProperties properties;

    private String activePath() {
        return "/v0/tests/" + properties.testVersion();
    }

    @Test
    void 활성_버전_조회는_200과_명세의_5개_최상위_필드를_반환한다() throws Exception {
        mockMvc.perform(get(activePath()))
                .andExpect(status().isOk())
                // 응답의 두 버전은 seed에서 오고 설정에서 오지 않는다 - 일치는 기동 검사가 강제한다
                .andExpect(jsonPath("$.testVersion").value(properties.testVersion()))
                .andExpect(jsonPath("$.scoreVersion").value(properties.scoreVersion()))
                .andExpect(jsonPath("$.dialect").value("GYEONGNAM"))
                .andExpect(jsonPath("$.estimatedDurationSec").value(240))
                .andExpect(jsonPath("$.items.length()").value(10))
                // §3.2 응답은 정확히 5개 필드 - 늘면 이 테스트가 알려준다
                .andExpect(jsonPath("$").value(aMapWithSize(5)));
    }

    // === KAN-10 AC - VOICE 5문항과 VOCABULARY 5문항이 구분되고 순서가 고정된다 ===

    @Test
    void 음성5_어휘5가_seq_순서로_교차_출제된다() throws Exception {
        JsonNode items = fetchActiveItems();
        // KAN-10 정본 표 - 출제 순서는 음성과 어휘 교차 (v1→w1→...→v5→w5)
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
            // 정의 원본에는 없고 응답에서 서버 상수로 채우는 값이다 - 계약(§3.2)은 문항별 필드를 유지한다.
            // 리터럴로 두어 상수가 바뀌면 클라이언트 계약 변경으로 드러나게 한다 (KAN-23)
            assertEquals(10000, item.get("maxDurationMs").asInt(), "음성 문항은 최대 10초다 (KAN-23)");
            JsonNode guideF0 = item.get("guideF0");
            assertEquals("semitone", guideF0.get("unit").asString());
            assertEquals(10, guideF0.get("frameIntervalMs").asInt());
            assertTrue(guideF0.get("values").size() > 0, "guideF0.values는 비어 있으면 안 된다");
            // 허용 밴드는 required다 (2026-08-09 확정, §3.2, §6) - 발행 검증이 길이까지 강제한다
            assertEquals(guideF0.get("values").size(), guideF0.get("bandLow").size());
            assertEquals(guideF0.get("values").size(), guideF0.get("bandHigh").size());
            assertProperties(guideF0, "guideF0",
                    Set.of("unit", "frameIntervalMs", "values", "bandLow", "bandHigh"));
            assertProperties(item, "VOICE 문항",
                    Set.of("itemId", "seq", "type", "prompt", "maxDurationMs", "guideF0"));
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
            // 정답이나 음성 필드가 붙으면 안 된다
            assertProperties(item, "VOCABULARY 문항",
                    Set.of("itemId", "seq", "type", "prompt", "choices"));
            JsonNode choices = item.get("choices");
            assertEquals(4, choices.size(), "어휘 문항은 4지선다다 (SRS 확정)");
            for (JsonNode choice : choices) {
                // 정오 표시가 있으면 안 된다 (KAN-13 정오 미노출)
                assertProperties(choice, "선택지", Set.of("choiceId", "text"));
            }
        }
        assertEquals(5, vocabularyCount);
    }

    @Test
    void 응답_어디에도_정답과_기준_음성_필드가_없다() throws Exception {
        String body = mockMvc.perform(get(activePath()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // KAN-10 AC - 정답(correctChoiceId) 미포함
        assertFalse(body.contains("correct"), "정답 정보가 응답에 새면 안 된다");
        // KAN-10 AC - 기준 음성 관련 필드 없음 (범위 제외, 2026-07-31)
        assertFalse(body.contains("referenceAudio"), "기준 음성 필드는 범위 제외다 (KAN-12)");
    }

    // === §3.2 - 불변, Cache-Control: immutable, ETag 지원 ===

    @Test
    void 버전_경로는_ETag와_불변_캐싱을_지원한다() throws Exception {
        MvcResult first = mockMvc.perform(get(activePath()))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(header().string("Cache-Control", containsString("immutable")))
                // 공유 캐시 미사용 - CDN 미도입 확정으로 public이 아니라 private다 (2026-08-09, KAN-101)
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andReturn();

        String etag = first.getResponse().getHeader("ETag");
        mockMvc.perform(get(activePath()).header("If-None-Match", etag))
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
        // 활성 버전이 아니지만, 먼저 발행된 gn-2026.07.0에 고정된 세션도
        // 자기 정의를 계속 받는다 - 활성 전환이 진행 중 세션에 영향을 주지 않는다 (KAN-26 AC)
        mockMvc.perform(get("/v0/tests/gn-2026.07.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testVersion").value("gn-2026.07.0"))
                .andExpect(jsonPath("$.items.length()").value(10));
    }

    // === KAN-10 요구 - 미발행과 경북 콘텐츠는 외부에 제공하지 않는다 ===

    @Test
    void 미발행_버전은_404_공통_오류_봉투이고_캐시되지_않는다() throws Exception {
        mockMvc.perform(get("/v0/tests/gb-2026.08.1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").exists())
                // 404는 지시자가 없으면 휴리스틱 캐싱 대상이다(RFC 9110 §15.1) - 정상 응답이
                // 1년 immutable인 API라 캐시에 눌러앉은 오류는 반복 재생된다 (Claude 리뷰 P2)
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    /**
     * 응답에 허용 목록 밖의 필드가 없는지 확인한다.
     * <p>
     * {@link TestDefinitionResponse}가 내부 타입({@code TestDefinition.GuideF0}, {@code Choice})을
     * 그대로 재사용하므로, 내부에 필드가 늘면 응답으로 그대로 새어 나간다.
     * 필드 개수 고정 대신 이름 기준으로 막는다 (Claude 리뷰 P3).
     *
     * @param required 반드시 있어야 하는 필드 - 이 밖의 필드는 노출로 간주한다
     */
    private static void assertProperties(JsonNode node, String what, Set<String> required) {
        Set<String> actual = new LinkedHashSet<>(node.propertyNames());

        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(actual);
        assertTrue(missing.isEmpty(), what + " 필드가 빠졌다: " + missing);

        Set<String> leaked = new LinkedHashSet<>(actual);
        leaked.removeAll(required);
        assertTrue(leaked.isEmpty(), what + "에 허용되지 않은 필드가 노출됐다: " + leaked);
    }

    private JsonNode fetchActiveItems() throws Exception {
        String body = mockMvc.perform(get(activePath()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("items");
    }
}
