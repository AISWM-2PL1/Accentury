package app.accentury.backend.testdefinition;

import app.accentury.backend.IntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * 구버전 픽스처 {@code gn-2026.07.0}은 테스트 프로파일에서만 도는 마이그레이션
 * ({@code db/testdata/V900__second_test_definition.sql})이 넣은 발행본이다 (KAN-26).
 */
@AutoConfigureMockMvc
class TestDefinitionApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 활성 버전의 정본은 DB의 {@code active_test_version} 한 행이다 (KAN-26). */
    private String activePath() {
        return "/v0/tests/" + activeTestVersion();
    }

    @Test
    void 활성_버전_조회는_200과_명세의_7개_최상위_필드를_반환한다() throws Exception {
        mockMvc.perform(get(activePath()))
                .andExpect(status().isOk())
                // 두 버전 모두 발행본이 정본이다 - 설정에는 이제 어느 쪽도 없다 (KAN-26).
                .andExpect(jsonPath("$.testVersion").value(activeTestVersion()))
                .andExpect(jsonPath("$.scoreVersion").value(activeScoreVersion()))
                .andExpect(jsonPath("$.dialect").value("GYEONGNAM"))
                .andExpect(jsonPath("$.estimatedDurationSec").value(240))
                // KAN-182 - 세트 번호와 세트 수. 음성 5문항 발행본은 세트 하나다.
                .andExpect(jsonPath("$.voiceSet").value(1))
                .andExpect(jsonPath("$.voiceSetCount").value(1))
                .andExpect(jsonPath("$.items.length()").value(10))
                // §3.2 응답은 정확히 7개 필드 - 늘면 이 테스트가 알려준다.
                .andExpect(jsonPath("$").value(aMapWithSize(7)));
    }

    // === KAN-182 - 세트 조회: ?voiceSet={n}, 생략 시 1, 세트별 ETag ===

    /** 테스트 프로파일 픽스처 - N = 7 풀, 세트 2 = poolIndex 6, 7 + 1, 2, 3 (V901). */
    private static final String POOL7 = "/v0/tests/gn-2026.09.t7";

    @Test
    void 세트를_지정하면_그_세트의_음성5_어휘5를_seq_교차_순서로_준다() throws Exception {
        String body = mockMvc.perform(get(POOL7).param("voiceSet", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testVersion").value("gn-2026.09.t7"))
                .andExpect(jsonPath("$.voiceSet").value(2))
                .andExpect(jsonPath("$.voiceSetCount").value(2))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andReturn().getResponse().getContentAsString();
        JsonNode items = objectMapper.readTree(body).get("items");
        // 채움 규칙 - 마지막 세트의 부족분(3개)은 풀의 처음(v1, v2, v3)에서 온다.
        List<String> expected = List.of("v6", "w1", "v7", "w2", "v1", "w3", "v2", "w4", "v3", "w5");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), items.get(i).get("itemId").asString());
            assertEquals(i + 1, items.get(i).get("seq").asInt(), "seq는 세트 응답에서 1..10으로 다시 매긴다");
        }
        // scriptKey는 AI meta 전용이다 - 정의 응답으로 새면 안 된다 (픽스처 t7은 전 문항에 scriptKey가 있다).
        assertFalse(body.contains("scriptKey"), "scriptKey는 클라이언트 응답에 싣지 않는다");
    }

    @Test
    void voiceSet을_생략하면_세트_1과_바이트_단위로_같다() throws Exception {
        MvcResult omitted = mockMvc.perform(get(POOL7)).andExpect(status().isOk()).andReturn();
        MvcResult explicit = mockMvc.perform(get(POOL7).param("voiceSet", "1"))
                .andExpect(status().isOk()).andReturn();

        assertEquals(explicit.getResponse().getContentAsString(), omitted.getResponse().getContentAsString(),
                "세트를 모르는 기존 클라이언트는 변경 없이 세트 1을 받는다");
        assertEquals(explicit.getResponse().getHeader("ETag"), omitted.getResponse().getHeader("ETag"));
        List<String> firstSet = new java.util.ArrayList<>();
        for (JsonNode item : objectMapper.readTree(omitted.getResponse().getContentAsString()).get("items")) {
            firstSet.add(item.get("itemId").asString());
        }
        assertEquals(List.of("v1", "w1", "v2", "w2", "v3", "w3", "v4", "w4", "v5", "w5"), firstSet);
    }

    @Test
    void 세트마다_ETag가_다르고_각각_304_재검증이_된다() throws Exception {
        String etag1 = mockMvc.perform(get(POOL7).param("voiceSet", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        String etag2 = mockMvc.perform(get(POOL7).param("voiceSet", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", not(etag1)))
                .andExpect(header().string("Cache-Control", containsString("immutable")))
                .andReturn().getResponse().getHeader("ETag");

        mockMvc.perform(get(POOL7).param("voiceSet", "2").header("If-None-Match", etag2))
                .andExpect(status().isNotModified());
        // 다른 세트의 ETag로는 재검증되지 않는다 - 세트가 URL에 들어가 캐시 키가 갈린다.
        mockMvc.perform(get(POOL7).param("voiceSet", "2").header("If-None-Match", etag1))
                .andExpect(status().isOk());
    }

    @Test
    void 세트_수를_넘는_voiceSet은_404_RESOURCE_NOT_FOUND다() throws Exception {
        // 없는 버전과 같은 취급이다 (§3.2).
        mockMvc.perform(get(POOL7).param("voiceSet", "3"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        // 음성 5문항 발행본은 세트 2가 없다.
        mockMvc.perform(get(activePath()).param("voiceSet", "2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 정수가_아니거나_1_미만인_voiceSet은_400_VALIDATION_FAILED다() throws Exception {
        for (String invalid : List.of("0", "-1", "abc", "1.5")) {
            mockMvc.perform(get(POOL7).param("voiceSet", invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void 채움이_없는_5의_배수_풀은_세트를_순서대로_나눈다() throws Exception {
        // 픽스처 t10 - N = 10, 세트 2 = poolIndex 6..10 (V901).
        JsonNode items = objectMapper.readTree(mockMvc.perform(get("/v0/tests/gn-2026.09.t10").param("voiceSet", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voiceSetCount").value(2))
                .andReturn().getResponse().getContentAsString()).get("items");
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode item : items) {
            ids.add(item.get("itemId").asString());
        }
        assertEquals(List.of("v6", "w1", "v7", "w2", "v8", "w3", "v9", "w4", "v10", "w5"), ids);
    }

    // === 2026-09-04 정본 콘텐츠 (V6) - 음성 145 + 어휘 145 = 세트 29개 ===

    /** KAN-17 가이드 곡선과 KAN-159 문장으로 만든 정본 발행본. 어휘도 세트마다 갈린다. */
    private static final String CONTENT = "/v0/tests/gn-2026.09.1";

    @Test
    void 정본_콘텐츠는_세트가_29개이고_어휘도_세트마다_다르다() throws Exception {
        // 어휘 풀 다중화(2026-09-04)가 실제 DB 발행 경로에서 도는지 본다 - 세트가 29개인데
        // 어휘가 5문항 고정이면 어느 세트를 응시하든 같은 어휘를 본다.
        Set<String> voices = new LinkedHashSet<>();
        Set<String> vocabulary = new LinkedHashSet<>();
        for (int set = 1; set <= 29; set++) {
            String body = mockMvc.perform(get(CONTENT).param("voiceSet", String.valueOf(set)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.voiceSet").value(set))
                    .andExpect(jsonPath("$.voiceSetCount").value(29))
                    .andExpect(jsonPath("$.items.length()").value(10))
                    .andReturn().getResponse().getContentAsString();
            for (JsonNode item : objectMapper.readTree(body).get("items")) {
                Set<String> seen = "VOICE".equals(item.get("type").asString()) ? voices : vocabulary;
                seen.add(item.get("itemId").asString());
            }
        }
        assertEquals(145, voices.size(), "음성 145문항이 모두 어느 세트엔가 실린다");
        assertEquals(145, vocabulary.size(), "어휘 145문항이 모두 어느 세트엔가 실린다");
        mockMvc.perform(get(CONTENT).param("voiceSet", "30")).andExpect(status().isNotFound());
    }

    @Test
    void 정본_콘텐츠의_guideF0는_밴드_없이_중앙선만_준다() throws Exception {
        // KAN-17 산출물의 1안이 중앙선만 낸다 (박재영 2026-09-04). 발행 검증의 밴드를
        // optional로 되돌린 결과가 응답에 그대로 드러나는 자리다.
        String body = mockMvc.perform(get(CONTENT)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode guide = objectMapper.readTree(body).get("items").get(0).get("guideF0");
        assertEquals("semitone", guide.get("unit").asString());
        assertTrue(guide.get("frameIntervalMs").isInt(),
                "산출물의 실수를 반올림해 정수로 싣는다 (2026-09-04 결정)");
        assertFalse(guide.has("bandLow"), "밴드 없이 발행된다");
        assertFalse(guide.has("bandHigh"), "밴드 없이 발행된다");
    }

    @Test
    void 정본_콘텐츠도_세트_응답이_200KB_기준_안이다() throws Exception {
        // 풀 전체는 276KB다. 세트 하나만 싣는다는 KAN-182 결정이 지켜지는지 본다 (§3.2).
        int bytes = mockMvc.perform(get(CONTENT)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray().length;
        assertTrue(bytes < 200 * 1024, "세트 하나의 응답이 " + bytes / 1024 + "KB다");
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
            // 리터럴로 두어 상수가 바뀌면 클라이언트 계약 변경으로 드러나게 한다 (KAN-23).
            assertEquals(10000, item.get("maxDurationMs").asInt(), "음성 문항은 최대 10초다 (KAN-23)");
            JsonNode guideF0 = item.get("guideF0");
            assertEquals("semitone", guideF0.get("unit").asString());
            assertEquals(10, guideF0.get("frameIntervalMs").asInt());
            assertTrue(guideF0.get("values").size() > 0, "guideF0.values는 비어 있으면 안 된다");
            // 허용 밴드는 required다 (2026-08-09 확정, §3.2, §6) - 발행 검증이 길이까지 강제한다.
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
            // 정답이나 음성 필드가 붙으면 안 된다.
            assertProperties(item, "VOCABULARY 문항",
                    Set.of("itemId", "seq", "type", "prompt", "choices"));
            JsonNode choices = item.get("choices");
            assertEquals(4, choices.size(), "어휘 문항은 4지선다다 (SRS 확정)");
            for (JsonNode choice : choices) {
                // 정오 표시가 있으면 안 된다 (KAN-13 정오 미노출).
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
                // 공유 캐시 미사용 - CDN 미도입 확정으로 public이 아니라 private다 (2026-08-09, KAN-101).
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
        // 자기 정의를 계속 받는다 - 활성 전환이 진행 중 세션에 영향을 주지 않는다 (KAN-26 AC).
        assertNotEquals("gn-2026.07.0", activeTestVersion(), "전제: 이 버전은 활성이 아니다");
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
                // 1년 immutable인 API라 캐시에 눌러앉은 오류는 반복 재생된다 (Claude 리뷰 P2).
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    /**
     * 응답에 허용 목록 밖의 필드가 없는지 확인한다.
     * <p>
     * {@link TestDefinitionResponse}가 내부 타입({@code TestDefinition.GuideF0}, {@code Choice})을
     * 그대로 재사용하므로, 내부에 필드가 늘면 응답으로 그대로 새어 나간다.
     * 필드 개수 고정 대신 이름 기준으로 막는다 (Claude 리뷰 P3).
     *
     * @param required 반드시 있어야 하는 필드 - 이 밖의 필드는 노출로 간주한다.
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
