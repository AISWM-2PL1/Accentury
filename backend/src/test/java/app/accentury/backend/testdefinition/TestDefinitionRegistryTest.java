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
import java.util.function.Supplier;
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

    // === 문항 구성 (2026-07-27 확정, KAN-182 완화) - 음성 N (N >= 5) + 어휘 5, seq 풀 기준 고정 ===

    @Test
    void 문항이_없거나_10개_미만이면_발행_거부다() {
        assertRejected(withItems(valid(), null), "음성 5개 이상");
        assertRejected(withItems(valid(), List.of()), "음성 5개 이상");
        assertRejected(withItems(valid(), valid().items().subList(0, 9)), "음성 5개 이상");
    }

    @Test
    void 어휘가_5문항이_아니면_발행_거부다() {
        // 어휘는 풀이 아니다 - 세트마다 그대로 5문항이므로 6개도 4개도 거부다.
        List<TestDefinition.Item> six = new ArrayList<>(valid().items());
        six.add(vocabulary("w6", 11));
        assertRejected(withItems(valid(), six), "문항 구성");

        List<TestDefinition.Item> items = new ArrayList<>(valid().items());
        items.removeIf(item -> item.itemId().equals("v5"));
        items.add(vocabulary("w6", 9));
        assertRejected(withItems(valid(), items), "문항 구성");
    }

    @Test
    void 음성이_5문항_미만이면_발행_거부다() {
        // N = 4는 채워도 한 세트 안에 같은 문항이 두 번 들어간다 (KAN-182 AC - N = 4 거부).
        // 9문항(음성 4 + 어휘 5)은 총수 검사에서 걸린다.
        List<TestDefinition.Item> nine = new ArrayList<>(valid().items());
        nine.removeIf(item -> item.itemId().equals("v5"));
        assertRejected(withItems(valid(), nine), "음성 5개 이상");

        // 총수를 어휘로 채운 정의(음성 4 + 어휘 7)는 구성 검사에서 걸린다.
        List<TestDefinition.Item> fourVoices = new ArrayList<>(nine);
        fourVoices.add(vocabulary("w6", 9));
        fourVoices.add(vocabulary("w7", 11));
        assertRejected(withItems(valid(), fourVoices), "문항 구성");
    }

    @Test
    void 음성_풀이_5개를_넘는_정의는_발행된다() {
        // KAN-182 - 풀 다중화. seq는 풀 기준 1..N+5 연속이면 된다.
        TestDefinitionRegistry.validate(pool(7));
        TestDefinitionRegistry.validate(pool(10));
        TestDefinitionRegistry.validate(pool(34));
    }

    // === KAN-182 - scriptKey: 정의 단위 all-or-nothing, 풀 안에서 유일 ===

    @Test
    void scriptKey가_없는_기존_정의는_그대로_발행된다() {
        // gn-2026.08.1은 scriptKey가 없고 더미 문장이라 실모델로 채점할 수 없지만, 발행 후 불변(§5.4)을
        // 지키려면 새 검증이 기존 행을 깨뜨리면 안 된다.
        TestDefinitionRegistry.validate(valid());
        TestDefinitionRegistry.validate(withScriptKeys(pool(7), false));
    }

    @Test
    void 전_음성_문항에_scriptKey가_있는_정의는_발행된다() {
        TestDefinitionRegistry.validate(withScriptKeys(pool(7), true));
    }

    @Test
    void scriptKey가_일부_문항에만_있으면_발행_거부다() {
        TestDefinition partial = withItem(withScriptKeys(pool(7), true), "v3",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        null, item.guideF0(), null, null));
        assertRejected(partial, "전부 없어야");
    }

    @Test
    void scriptKey가_중복되면_발행_거부다() {
        TestDefinition duplicated = withItem(withScriptKeys(pool(7), true), "v3",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        "1|1", item.guideF0(), null, null));
        assertRejected(duplicated, "scriptKey 중복");
    }

    @Test
    void scriptKey가_빈_문자열이면_발행_거부다() {
        TestDefinition blank = withItem(withScriptKeys(pool(7), true), "v3",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        "  ", item.guideF0(), null, null));
        assertRejected(blank, "scriptKey가 비어");
    }

    @Test
    void VOCABULARY_문항에_scriptKey가_붙으면_발행_거부다() {
        TestDefinition broken = withItem(valid(), "w1",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        "1|1", null, item.choices(), item.correctChoiceId()));
        assertRejected(broken, "VOCABULARY 문항에 음성 필드");
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
            // 음성 5문항 정의는 세트 하나다 (KAN-182 하위 호환).
            assertEquals(1, registry.active().voiceSetCount());
            assertEquals(5, registry.active().voicePoolSize());
            // 활성이 아닌 발행본도 자기 경로로 계속 조회된다 (§5.4, KAN-26 AC).
            assertEquals("gn-2026.07.0", registry.get("gn-2026.07.0").voiceSet(1).response().testVersion());

            ApiException notFound = assertThrows(ApiException.class, () -> registry.get("gn-0000.00.0"));
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, notFound.code());
        }

        @Test
        void 풀_정의는_세트별_응답과_ETag를_미리_만든다() {
            // KAN-182 - 세트는 기동 시 발행본에서 유도되고, 세트마다 본문이 달라 ETag도 다르다.
            TestDefinitionRegistry registry = registry(List.of(row("gn-2026.09.t7", pool(7))), "gn-2026.09.t7");
            TestDefinitionRegistry.PublishedDefinition published = registry.get("gn-2026.09.t7");

            assertEquals(7, published.voicePoolSize());
            assertEquals(2, published.voiceSetCount());
            assertEquals(1, published.voiceSet(1).response().voiceSet());
            assertEquals(2, published.voiceSet(2).response().voiceSetCount());
            assertEquals(List.of("v6", "w1", "v7", "w2", "v1", "w3", "v2", "w4", "v3", "w5"),
                    published.voiceSet(2).response().items().stream()
                            .map(TestDefinitionResponse.Item::itemId).toList());
            assertTrue(!published.voiceSet(1).etag().equals(published.voiceSet(2).etag()),
                    "세트별 ETag가 달라야 한다");
            // 세트 수를 넘으면 없는 버전과 같은 404다 (§3.2).
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND,
                    assertThrows(ApiException.class, () -> published.voiceSet(3)).code());
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND,
                    assertThrows(ApiException.class, () -> published.voiceSet(0)).code());
        }

        @Test
        void 세션_세트_밖의_음성_문항은_풀에_있어도_ITEM_NOT_IN_VERSION이다() {
            // KAN-182 - 열어 두면 한 세션에 음성 점수가 5개 넘게 쌓여 집계가 깨진다.
            TestDefinitionRegistry registry = registry(List.of(row("gn-2026.09.t7", pool(7))), "gn-2026.09.t7");

            assertEquals("v6", registry.requireItem("gn-2026.09.t7", 2, "v6",
                    TestDefinition.ItemType.VOICE).itemId());
            assertEquals("v1", registry.requireItem("gn-2026.09.t7", 2, "v1",
                    TestDefinition.ItemType.VOICE).itemId(), "채움 문항 v1은 세트 2의 문항이다");
            ApiException outside = assertThrows(ApiException.class,
                    () -> registry.requireItem("gn-2026.09.t7", 2, "v4", TestDefinition.ItemType.VOICE));
            assertEquals(ErrorCode.ITEM_NOT_IN_VERSION, outside.code());
            // 어휘 5문항은 모든 세트에 있다.
            assertEquals("w5", registry.requireItem("gn-2026.09.t7", 2, "w5",
                    TestDefinition.ItemType.VOCABULARY).itemId());
            assertEquals(10, registry.sessionDefinition("gn-2026.09.t7", 2).items().size());
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
            // 교체 전후로 옛 버전 조회가 같은 정의를 계속 돌려준다. 전환은 포인터 행만 바꾼다
            // (KAN-167 - 레지스트리는 활성 버전을 들고 있지 않다).
            String[] pointer = {"gn-2026.08.1"};
            TestDefinitionRegistry registry = new TestDefinitionRegistry(JsonMapper.builder().build(),
                    definitions(List.of(row("gn-2026.08.1"), row("gn-2026.07.0"))),
                    activeVersions(() -> pointer[0]), policies());
            String etagBefore = registry.get("gn-2026.08.1").voiceSet(1).etag();

            pointer[0] = "gn-2026.07.0";

            assertEquals("gn-2026.07.0", registry.active().definition().testVersion());
            assertEquals(etagBefore, registry.get("gn-2026.08.1").voiceSet(1).etag());
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
        return activeVersions(() -> activeVersion);
    }

    /** 포인터를 테스트가 옮길 수 있는 저장소 - 읽을 때마다 supplier를 다시 본다 (KAN-167). */
    private static ActiveTestVersionRepository activeVersions(Supplier<@Nullable String> activeVersion) {
        return new ActiveTestVersionRepository() {
            @Override
            public Optional<ActiveTestVersion> findById(String id) {
                String current = activeVersion.get();
                return current == null
                        ? Optional.empty()
                        : Optional.of(new ActiveTestVersion(current, null, Instant.EPOCH));
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

    private static StoredTestDefinition row(String testVersion, TestDefinition definition) {
        return new StoredTestDefinition(testVersion, "GYEONGNAM", "sv-0.3",
                DefinitionFixtures.body(withVersions(definition, testVersion, "sv-0.3")), Instant.EPOCH);
    }

    /** 음성 N + 어휘 5 풀 - scriptKey 없음 */
    private static TestDefinition pool(int voiceCount) {
        return DefinitionFixtures.pool(voiceCount);
    }

    private static TestDefinition withScriptKeys(TestDefinition base, boolean present) {
        List<TestDefinition.Item> items = base.items().stream()
                .map(item -> item.type() != TestDefinition.ItemType.VOICE ? item
                        : new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                                present ? "1|" + item.itemId().substring(1) : null,
                                item.guideF0(), null, null))
                .toList();
        return withItems(base, items);
    }

    private static TestDefinition.Item voice(String itemId, int seq) {
        return DefinitionFixtures.voice(itemId, seq);
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
