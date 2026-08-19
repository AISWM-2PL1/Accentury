package app.accentury.backend.testdefinition;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import app.accentury.backend.scoring.ScorePolicyRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 발행 전 검증과 기동 조립의 단위 명세 (KAN-10 AC, KAN-26 AC, 명세서 §6).
 * <p>
 * 유효한 정의를 한 곳씩 망가뜨려 발행 거부(기동 실패)를 확인한다. 발행 입력이 DB로 바뀌어도
 * (2026-08-09 확정) 검증은 그대로 남으므로, 이 명세도 그대로 남는다 - 바뀐 것은 정의가
 * 어디서 오느냐뿐이다.
 * <p>
 * <b>검증 분기를 빠짐없이 덮는 것이 이 클래스의 목적이다</b> (KAN-26 요구 - 이관 시점에
 * 커버리지 보강). 이관 전에는 25개 분기 중 8개만 덮여 있었다. 덮이지 않은 분기는 "검증이
 * 있다고 믿지만 실제로는 통과시키는" 상태와 구별되지 않는다.
 */
class TestDefinitionRegistryTest {

    // === §6 최상위 필드 ===

    @Test
    void 유효한_정의는_검증을_통과한다() {
        TestDefinitionRegistry.validate(valid());
    }

    @Test
    void testVersion이_비면_발행_거부다() {
        assertRejected(withVersions(valid(), "", "sv-0.3"), "testVersion");
        assertRejected(withVersions(valid(), "   ", "sv-0.3"), "testVersion");
    }

    @Test
    void scoreVersion이_비면_발행_거부다() {
        assertRejected(withVersions(valid(), "gn-2026.08.1", ""), "scoreVersion");
    }

    @Test
    void 예상_소요_시간이_양수가_아니면_발행_거부다() {
        // 0이나 음수는 클라이언트 진행 표시(§3.2)를 깨뜨린다.
        assertRejected(new TestDefinition("gn-2026.08.1", "sv-0.3", "GYEONGNAM", 0, valid().items()),
                "estimatedDurationSec");
        assertRejected(new TestDefinition("gn-2026.08.1", "sv-0.3", "GYEONGNAM", -1, valid().items()),
                "estimatedDurationSec");
    }

    // === §6 - 경북 정의는 MVP에서 발행·활성화 불가 ===

    @Test
    void 경남이_아닌_정의는_발행_거부다() {
        TestDefinition gyeongbuk = new TestDefinition("gb-2026.08.1", "sv-0.3", "GYEONGBUK", 240, valid().items());
        assertRejected(gyeongbuk, "경남");
    }

    // === 문항 구성 확정 (2026-07-27) - 음성 5 + 어휘 5, seq 고정 ===

    @Test
    void 문항이_없거나_10개가_아니면_발행_거부다() {
        assertRejected(withItems(valid(), null), "10개");
        assertRejected(withItems(valid(), List.of()), "10개");

        List<TestDefinition.Item> eleven = new ArrayList<>(valid().items());
        eleven.add(vocabulary("w6", 11));
        assertRejected(withItems(valid(), eleven), "10개");
    }

    @Test
    void 음성5_어휘5_구성이_아니면_발행_거부다() {
        List<TestDefinition.Item> items = new ArrayList<>(valid().items());
        items.removeIf(item -> item.itemId().equals("v5"));
        items.add(vocabulary("w6", 9));
        assertRejected(withItems(valid(), items), "문항 구성");
    }

    @Test
    void seq가_1부터_연속하지_않으면_발행_거부다() {
        TestDefinition broken = withItem(valid(), "w5",
                item -> new TestDefinition.Item(item.itemId(), 12, item.type(), item.prompt(),
                        null, item.choices(), item.correctChoiceId()));
        assertRejected(broken, "연속");
    }

    @Test
    void seq가_중복되면_발행_거부다() {
        TestDefinition broken = withItem(valid(), "w5",
                item -> new TestDefinition.Item(item.itemId(), 1, item.type(), item.prompt(),
                        null, item.choices(), item.correctChoiceId()));
        assertRejected(broken, "seq 중복");
    }

    @Test
    void itemId가_중복되면_발행_거부다() {
        TestDefinition broken = withItem(valid(), "v2",
                item -> new TestDefinition.Item("v1", item.seq(), item.type(), item.prompt(),
                        item.guideF0(), null, null));
        assertRejected(broken, "itemId 중복");
    }

    @Test
    void itemId나_prompt가_비면_발행_거부다() {
        assertRejected(withItem(valid(), "v1",
                item -> new TestDefinition.Item("", item.seq(), item.type(), item.prompt(),
                        item.guideF0(), null, null)), "itemId");
        assertRejected(withItem(valid(), "v1",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), "  ",
                        item.guideF0(), null, null)), "prompt");
    }

    @Test
    void 문항_유형이_없으면_발행_거부다() {
        assertRejected(withItem(valid(), "v1",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), null, item.prompt(),
                        item.guideF0(), null, null)), "type");
    }

    @Test
    void 식별자가_저장_컬럼_길이를_넘으면_발행_거부다() {
        // 제출 저장 컬럼이 varchar(40)이다 - 발행 검증이 막지 않으면 정의 조회와 답안
        // 검증은 통과하고 제출 시점의 INSERT가 500으로 터진다 (Codex sol 리뷰 P2).
        TestDefinition longItemId = withItem(valid(), "w2",
                item -> new TestDefinition.Item("w".repeat(41), item.seq(), item.type(), item.prompt(),
                        null, item.choices(), item.correctChoiceId()));
        assertRejected(longItemId, "itemId");

        TestDefinition longChoiceId = withItem(valid(), "w2",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        null, choicesWith(item, 3, new TestDefinition.Choice("c".repeat(41), "시금치")),
                        "w2a"));
        assertRejected(longChoiceId, "choiceId");
    }

    // === KAN-10 AC - 모든 VOICE 문항에 guideF0 포함, 누락 시 발행 거부 ===

    @Test
    void VOICE_문항에_guideF0가_없으면_발행_거부다() {
        assertRejected(voiceWith(null), "guideF0");
    }

    @Test
    void VOICE_문항에_어휘_필드가_붙으면_발행_거부다() {
        // 유형 오염 가드 (KAN-15에서 이관) - 유형이 갖지 않는 필드는 응답으로도 새면 안 된다.
        TestDefinition withChoices = withItem(valid(), "v1",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        item.guideF0(), List.of(new TestDefinition.Choice("v1a", "부추")), null));
        assertRejected(withChoices, "VOICE 문항에 어휘 필드");

        TestDefinition withAnswer = withItem(valid(), "v1",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        item.guideF0(), null, "v1a"));
        assertRejected(withAnswer, "VOICE 문항에 어휘 필드");
    }

    @Test
    void guideF0의_단위나_간격이_비정상이면_발행_거부다() {
        assertRejected(voiceWith(guideF0("", 10, 3)), "unit");
        assertRejected(voiceWith(guideF0("semitone", 0, 3)), "frameIntervalMs");
        assertRejected(voiceWith(guideF0("semitone", -10, 3)), "frameIntervalMs");
    }

    @Test
    void guideF0_곡선이_비면_발행_거부다() {
        assertRejected(voiceWith(new TestDefinition.GuideF0("semitone", 10, null, null, null)), "values");
        assertRejected(voiceWith(new TestDefinition.GuideF0("semitone", 10, List.of(), List.of(), List.of())),
                "values");
    }

    @Test
    void 허용_밴드가_없으면_발행_거부다() {
        // bandLow와 bandHigh는 required다 (2026-08-09 확정, §3.2, §6).
        assertRejected(voiceWith(new TestDefinition.GuideF0("semitone", 10, List.of(0.1, 0.2, 0.3),
                null, List.of(1.6, 1.7, 1.8))), "bandLow");
        assertRejected(voiceWith(new TestDefinition.GuideF0("semitone", 10, List.of(0.1, 0.2, 0.3),
                List.of(-1.6, -1.7, -1.8), null)), "bandHigh");
    }

    @Test
    void 허용_밴드_길이가_values와_다르면_발행_거부다() {
        assertRejected(voiceWith(new TestDefinition.GuideF0("semitone", 10, List.of(0.1, 0.2, 0.3),
                List.of(-1.0), List.of(1.6, 1.7, 1.8))), "bandLow");
        assertRejected(voiceWith(new TestDefinition.GuideF0("semitone", 10, List.of(0.1, 0.2, 0.3),
                List.of(-1.6, -1.7, -1.8), List.of(1.6))), "bandHigh");
    }

    // === §6 - 모든 VOCABULARY 문항에 정답 존재 ===

    @Test
    void VOCABULARY_문항에_음성_필드가_붙으면_발행_거부다() {
        // 유형 오염 가드 (KAN-15에서 이관)
        TestDefinition broken = withItem(valid(), "w1",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        guideF0("semitone", 10, 3), item.choices(), item.correctChoiceId()));
        assertRejected(broken, "VOCABULARY 문항에 음성 필드");
    }

    @Test
    void VOCABULARY_문항에_정답이_없으면_발행_거부다() {
        TestDefinition broken = withItem(valid(), "w2",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        null, item.choices(), null));
        assertRejected(broken, "정답");
    }

    @Test
    void 정답이_선택지_밖이면_발행_거부다() {
        TestDefinition broken = withItem(valid(), "w2",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        null, item.choices(), "w9z"));
        assertRejected(broken, "정답");
    }

    @Test
    void 선택지가_4지선다가_아니면_발행_거부다() {
        assertRejected(withItem(valid(), "w2",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        null, null, "w2a")), "4지선다");
        assertRejected(withItem(valid(), "w2",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        null, item.choices().subList(0, 3), "w2a")), "4지선다");
    }

    @Test
    void 선택지의_식별자나_문구가_비면_발행_거부다() {
        assertRejected(withItem(valid(), "w2",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        null, choicesWith(item, 1, new TestDefinition.Choice("", "미나리")), "w2a")),
                "choiceId");
        assertRejected(withItem(valid(), "w2",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        null, choicesWith(item, 1, new TestDefinition.Choice("w2b", " ")), "w2a")),
                "선택지 문구");
    }

    @Test
    void 선택지_식별자가_중복되면_발행_거부다() {
        assertRejected(withItem(valid(), "w2",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        null, choicesWith(item, 1, new TestDefinition.Choice("w2a", "미나리")), "w2a")),
                "choiceId 중복");
    }

    // === 기동 조립 (KAN-26 - 발행본은 DB에서 온다) ===

    @Nested
    class 기동 {

        @Test
        void 발행본을_로드하고_활성_버전을_잡는다() {
            TestDefinitionRegistry registry = registry(
                    List.of(row("gn-2026.08.1"), row("gn-2026.07.0")), "gn-2026.08.1");

            assertEquals("gn-2026.08.1", registry.active().definition().testVersion());
            assertEquals("sv-0.3", registry.active().definition().scoreVersion());
            // 활성이 아닌 발행본도 자기 경로로 계속 조회된다 (§5.4, KAN-26 AC).
            assertEquals("gn-2026.07.0", registry.get("gn-2026.07.0").response().testVersion());

            ApiException notFound = assertThrows(ApiException.class, () -> registry.get("gn-0000.00.0"));
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, notFound.code());
        }

        @Test
        void 발행본이_하나도_없으면_기동에_실패한다() {
            // 마이그레이션이 적용되지 않은 DB에 붙었을 때다 - 세션을 만들 수 없으므로 기동을 멈춘다.
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> registry(List.of(), "gn-2026.08.1"));
            assertTrue(rejected.getMessage().contains("하나도 없다"), rejected.getMessage());
        }

        @Test
        void 활성_버전_행이_없으면_기동에_실패한다() {
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> registry(List.of(row("gn-2026.08.1")), null));
            assertTrue(rejected.getMessage().contains("active_test_version"), rejected.getMessage());
        }

        @Test
        void 활성_버전의_발행본이_없으면_기동에_실패한다() {
            // DB의 FK가 이미 막는 조합이지만, 그 FK가 닿지 않는 환경까지 덮는 방어선이다.
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> registry(List.of(row("gn-2026.08.1")), "gn-9999.99.9"));
            assertTrue(rejected.getMessage().contains("gn-9999.99.9"), rejected.getMessage());
        }

        @Test
        void 본문이_깨졌으면_어느_버전인지_알려주고_기동에_실패한다() {
            StoredTestDefinition broken = new StoredTestDefinition(
                    "gn-2026.08.1", "GYEONGNAM", "sv-0.3", "{ not json", Instant.EPOCH);
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> registry(List.of(broken), "gn-2026.08.1"));
            assertTrue(rejected.getMessage().contains("gn-2026.08.1"), rejected.getMessage());
        }

        @Test
        void 행의_사본_컬럼과_본문이_어긋나면_기동에_실패한다() {
            // 목록 조회(§6)는 컬럼을, 정의 조회(§3.2)는 본문을 쓴다 - 어긋난 채 배포되면
            // 두 API가 같은 버전을 다르게 말한다. 파일명 대조 검사가 DB로 옮겨 온 자리다.
            String body = body("gn-2026.08.1");
            assertTrue(assertThrows(IllegalStateException.class, () -> registry(
                    List.of(new StoredTestDefinition("gn-9999.99.9", "GYEONGNAM", "sv-0.3", body, Instant.EPOCH)),
                    "gn-9999.99.9")).getMessage().contains("test_version"));
            assertTrue(assertThrows(IllegalStateException.class, () -> registry(
                    List.of(new StoredTestDefinition("gn-2026.08.1", "GYEONGBUK", "sv-0.3", body, Instant.EPOCH)),
                    "gn-2026.08.1")).getMessage().contains("dialect"));
            assertTrue(assertThrows(IllegalStateException.class, () -> registry(
                    List.of(new StoredTestDefinition("gn-2026.08.1", "GYEONGNAM", "sv-9.9", body, Instant.EPOCH)),
                    "gn-2026.08.1")).getMessage().contains("score_version"));
        }

        @Test
        void 정의가_참조하는_scoreVersion의_정책이_없으면_기동에_실패한다() {
            // scoreVersion 참조 유효 검증 (§6, KAN-21) - 활성이 아닌 정의라도 참조가 끊기면 발행 거부.
            // 정책 seed는 classpath 고정이라, 참조 실패 상황은 조회를 막은 레지스트리로 재현한다.
            ScorePolicyRegistry noPolicies = new ScorePolicyRegistry(JsonMapper.builder().build()) {
                @Override
                public boolean isPublished(String scoreVersion) {
                    return false;
                }
            };
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> new TestDefinitionRegistry(JsonMapper.builder().build(),
                            definitions(List.of(row("gn-2026.08.1"))),
                            activeVersions("gn-2026.08.1"), noPolicies));
            assertTrue(rejected.getMessage().contains("점수 정책"), rejected.getMessage());
        }

        @Test
        void 활성_교체는_새_활성만_바꾸고_나머지_발행본은_그대로_둔다() {
            // 활성 전환이 진행 중 세션에 영향을 주지 않는다는 것의 레지스트리 쪽 근거다 (KAN-26 AC) -
            // 교체 전후로 옛 버전 조회가 같은 정의를 계속 돌려준다.
            TestDefinitionRegistry registry = registry(
                    List.of(row("gn-2026.08.1"), row("gn-2026.07.0")), "gn-2026.08.1");
            String etagBefore = registry.get("gn-2026.08.1").etag();

            registry.applyActivation("gn-2026.07.0");

            assertEquals("gn-2026.07.0", registry.active().definition().testVersion());
            assertEquals(etagBefore, registry.get("gn-2026.08.1").etag());
        }
    }

    // === 픽스처 ===

    private static TestDefinition valid() {
        return DefinitionFixtures.valid();
    }

    private static TestDefinition.Item vocabulary(String itemId, int seq) {
        return DefinitionFixtures.vocabulary(itemId, seq);
    }

    private static TestDefinition.GuideF0 guideF0(String unit, int frameIntervalMs, int length) {
        return DefinitionFixtures.guideF0(unit, frameIntervalMs, length);
    }

    private static void assertRejected(TestDefinition broken, String expectedInMessage) {
        IllegalStateException rejected =
                assertThrows(IllegalStateException.class, () -> TestDefinitionRegistry.validate(broken));
        assertTrue(rejected.getMessage().contains(expectedInMessage),
                "메시지에 " + expectedInMessage + "이(가) 없다: " + rejected.getMessage());
    }

    /** 실제 seed(sv-0.3)를 로드한 점수 정책 레지스트리 - 정의의 scoreVersion 참조 검증에 쓰인다. */
    private static ScorePolicyRegistry policies() {
        return new ScorePolicyRegistry(JsonMapper.builder().build());
    }

    private static TestDefinitionRegistry registry(List<StoredTestDefinition> rows,
                                                   @Nullable String activeVersion) {
        return new TestDefinitionRegistry(JsonMapper.builder().build(),
                definitions(rows), activeVersions(activeVersion), policies());
    }

    /** 발행본 저장소 스텁 - 발행이 마이그레이션 전용이라 실제 구현에도 읽기밖에 없다. */
    private static StoredTestDefinitionRepository definitions(List<StoredTestDefinition> rows) {
        return () -> rows;
    }

    private static ActiveTestVersionRepository activeVersions(@Nullable String activeVersion) {
        return new ActiveTestVersionRepository() {
            @Override
            public Optional<ActiveTestVersion> findById(String id) {
                return activeVersion == null
                        ? Optional.empty()
                        : Optional.of(new ActiveTestVersion(activeVersion, null, Instant.EPOCH));
            }

            @Override
            public Optional<ActiveTestVersion> lockById(String id) {
                return findById(id);
            }
        };
    }

    private static StoredTestDefinition row(String testVersion) {
        return new StoredTestDefinition(testVersion, "GYEONGNAM", "sv-0.3",
                DefinitionFixtures.body(testVersion, "GYEONGNAM"), Instant.EPOCH);
    }

    private static String body(String testVersion) {
        return DefinitionFixtures.body(testVersion, "GYEONGNAM");
    }

    /** VOICE 문항 하나의 guideF0만 갈아 끼운 정의 - 곡선 검증 분기들이 쓴다. */
    private static TestDefinition voiceWith(TestDefinition.@Nullable GuideF0 guideF0) {
        return withItem(valid(), "v1",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        guideF0, null, null));
    }

    /** 어휘 문항의 선택지 하나만 바꾼 목록 */
    private static List<TestDefinition.Choice> choicesWith(TestDefinition.Item item, int index,
                                                           TestDefinition.Choice replacement) {
        TestDefinition.Choice[] choices = item.choices().toArray(new TestDefinition.Choice[0]);
        choices[index] = replacement;
        return Arrays.asList(choices);
    }

    private static TestDefinition withVersions(TestDefinition base, String testVersion, String scoreVersion) {
        return new TestDefinition(testVersion, scoreVersion, base.dialect(),
                base.estimatedDurationSec(), base.items());
    }

    private static TestDefinition withItems(TestDefinition base, @Nullable List<TestDefinition.Item> items) {
        return new TestDefinition(base.testVersion(), base.scoreVersion(), base.dialect(),
                base.estimatedDurationSec(), items);
    }

    private static TestDefinition withItem(TestDefinition base, String itemId,
                                           UnaryOperator<TestDefinition.Item> change) {
        List<TestDefinition.Item> items = base.items().stream()
                .map(item -> item.itemId().equals(itemId) ? change.apply(item) : item)
                .toList();
        return withItems(base, items);
    }
}
