package app.accentury.backend.testdefinition;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 세트 분할 규칙의 단위 명세 (KAN-182 AC - N = 5, 6, 7, 10, 34와 N = 4 거부).
 * 2026-09-04에 어휘도 같은 규칙으로 나뉘게 되어 어휘 풀 명세가 뒤에 붙었다.
 * <p>
 * 규칙은 순수 산술이라 DB 없이 검증한다. 실제 발행 경로에서 같은 규칙이 도는 것은
 * {@code db/testdata/V901__voice_pool_fixtures.sql}과 {@code TestDefinitionApiTest}가 본다.
 */
class VoiceSetsTest {

    @Test
    void 세트_수는_5로_올림_나눗셈이다() {
        assertEquals(1, VoiceSets.setCount(5, 5));
        assertEquals(2, VoiceSets.setCount(6, 5));
        assertEquals(2, VoiceSets.setCount(7, 5));
        assertEquals(2, VoiceSets.setCount(10, 5));
        assertEquals(7, VoiceSets.setCount(34, 5));
    }

    @Test
    void N이_5면_세트_하나로_현행과_같다() {
        // 하위 호환 - 기존 더미 정의(gn-2026.08.1)는 세트 1 하나이고 그 세트가 풀 전체다.
        assertEquals(List.of(1, 2, 3, 4, 5), VoiceSets.poolIndexes(5, 1));
    }

    @Test
    void 마지막_세트의_부족분은_풀의_처음부터_채운다() {
        // N = 6: 세트 2 = 6 + 1, 2, 3, 4
        assertEquals(List.of(6, 1, 2, 3, 4), VoiceSets.poolIndexes(6, 2));
        // N = 7: 세트 2 = 6, 7 + 1, 2, 3
        assertEquals(List.of(1, 2, 3, 4, 5), VoiceSets.poolIndexes(7, 1));
        assertEquals(List.of(6, 7, 1, 2, 3), VoiceSets.poolIndexes(7, 2));
    }

    @Test
    void N이_5의_배수면_채우지_않는다() {
        assertEquals(List.of(1, 2, 3, 4, 5), VoiceSets.poolIndexes(10, 1));
        assertEquals(List.of(6, 7, 8, 9, 10), VoiceSets.poolIndexes(10, 2));
    }

    @Test
    void N이_34면_세트_7개이고_세트_7은_31_32_33_34_1이다() {
        // 티켓의 예시 표 그대로다.
        assertEquals(7, VoiceSets.setCount(34, 5));
        assertEquals(List.of(1, 2, 3, 4, 5), VoiceSets.poolIndexes(34, 1));
        assertEquals(List.of(6, 7, 8, 9, 10), VoiceSets.poolIndexes(34, 2));
        for (int set = 3; set <= 6; set++) {
            int first = (set - 1) * 5 + 1;
            assertEquals(List.of(first, first + 1, first + 2, first + 3, first + 4),
                    VoiceSets.poolIndexes(34, set));
        }
        assertEquals(List.of(31, 32, 33, 34, 1), VoiceSets.poolIndexes(34, 7));
    }

    @Test
    void 채움_문항은_같은_세트_안에서_중복되지_않는다() {
        // N >= 5면 채움 수(5 - r)가 마지막 세트의 첫 poolIndex보다 작다 - 어느 N이든 세트 안 중복이 없다.
        for (int poolSize = 5; poolSize <= 40; poolSize++) {
            for (int set = 1; set <= VoiceSets.setCount(poolSize, 5); set++) {
                List<Integer> indexes = VoiceSets.poolIndexes(poolSize, set);
                assertEquals(5, indexes.size());
                assertEquals(5, indexes.stream().distinct().count(),
                        "N=" + poolSize + " 세트 " + set + "에 중복이 있다: " + indexes);
            }
        }
    }

    @Test
    void N이_5_미만이면_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> VoiceSets.setCount(4, 5));
        assertThrows(IllegalArgumentException.class, () -> VoiceSets.poolIndexes(4, 1));
        assertThrows(IllegalArgumentException.class, () -> VoiceSets.derive(pool(4)));
    }

    @Test
    void 세트_번호가_1_미만이면_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> VoiceSets.poolIndexes(7, 0));
        assertThrows(IllegalArgumentException.class, () -> VoiceSets.poolIndexes(7, -1));
    }

    @Test
    void 자기_풀의_세트_수를_넘는_번호는_풀의_처음으로_돌아간다() {
        // 세트 수는 큰 쪽 풀이 정하므로(2026-09-04), 작은 풀은 되풀이해서 채우는 것이 정상이다.
        // 어휘 5 + 음성 34면 세트가 7개인데 어휘 풀은 자기만 보면 세트가 1개뿐이다.
        for (int set = 1; set <= 7; set++) {
            assertEquals(List.of(1, 2, 3, 4, 5), VoiceSets.poolIndexes(5, set),
                    "어휘 5는 세트 " + set + "에서도 같은 5문항이다");
        }
        // 세트 3의 자리는 10..14이고 7로 나눈 나머지가 3..0이라 poolIndex는 4, 5, 6, 7, 1이다.
        assertEquals(List.of(4, 5, 6, 7, 1), VoiceSets.poolIndexes(7, 3));
    }

    // === 어휘 풀 다중화 (2026-09-04) ===

    @Test
    void 세트_수는_음성과_어휘_중_큰_풀이_정한다() {
        assertEquals(7, VoiceSets.setCount(34, 5), "음성이 크면 음성이 정한다");
        assertEquals(7, VoiceSets.setCount(5, 34), "어휘가 크면 어휘가 정한다");
        assertEquals(29, VoiceSets.setCount(145, 145), "정본 발행본 - 양쪽 145면 29세트다");
    }

    @Test
    void 어휘도_세트마다_다른_5문항이다() {
        // 음성 10 + 어휘 10이면 세트 2개이고 세트 2의 어휘는 w6..w10이다.
        List<TestDefinition> sets = VoiceSets.derive(pool(10, 10));
        assertEquals(2, sets.size());
        assertEquals(List.of("v1", "w1", "v2", "w2", "v3", "w3", "v4", "w4", "v5", "w5"),
                itemIds(sets.get(0)));
        assertEquals(List.of("v6", "w6", "v7", "w7", "v8", "w8", "v9", "w9", "v10", "w10"),
                itemIds(sets.get(1)));
    }

    @Test
    void 어휘가_5면_세트마다_같은_5문항으로_현행과_같다() {
        // 하위 호환 - 어휘 풀을 늘리지 않은 정의는 KAN-182 때와 똑같이 돈다.
        List<TestDefinition> sets = VoiceSets.derive(pool(7, 5));
        assertEquals(2, sets.size());
        for (TestDefinition set : sets) {
            List<String> vocabulary = set.items().stream()
                    .filter(item -> item.type() == TestDefinition.ItemType.VOCABULARY)
                    .map(TestDefinition.Item::itemId).toList();
            assertEquals(List.of("w1", "w2", "w3", "w4", "w5"), vocabulary);
        }
    }

    @Test
    void 어휘_풀이_5_미만이면_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> VoiceSets.setCount(10, 4));
        assertThrows(IllegalArgumentException.class, () -> VoiceSets.derive(pool(10, 4)));
    }

    @Test
    void 어휘_풀이_음성보다_커도_남는_어휘가_없다() {
        // 음성 5 + 어휘 12면 세트 3개다. 어휘 12개가 전부 어느 세트엔가 실린다.
        List<TestDefinition> sets = VoiceSets.derive(pool(5, 12));
        assertEquals(3, sets.size());
        List<String> seen = new ArrayList<>();
        for (TestDefinition set : sets) {
            set.items().stream()
                    .filter(item -> item.type() == TestDefinition.ItemType.VOCABULARY)
                    .map(TestDefinition.Item::itemId)
                    .forEach(id -> { if (!seen.contains(id)) seen.add(id); });
        }
        assertEquals(12, seen.size(), "어휘 12개가 모두 출제된다: " + seen);
    }

    private static List<String> itemIds(TestDefinition set) {
        return set.items().stream().map(TestDefinition.Item::itemId).toList();
    }

    // === 세트 정의 유도 - 문항은 풀의 원본 그대로, seq만 1..10 교차 ===

    @Test
    void 세트_정의는_음성과_어휘를_교차하고_seq를_1부터_다시_매긴다() {
        List<TestDefinition> sets = VoiceSets.derive(pool(7));
        assertEquals(2, sets.size());

        List<String> second = sets.get(1).items().stream().map(TestDefinition.Item::itemId).toList();
        assertEquals(List.of("v6", "w1", "v7", "w2", "v1", "w3", "v2", "w4", "v3", "w5"), second);
        for (int i = 0; i < 10; i++) {
            TestDefinition.Item item = sets.get(1).items().get(i);
            assertEquals(i + 1, item.seq(), "seq는 세트 안에서 1부터 연속이다");
            assertEquals(i % 2 == 0 ? TestDefinition.ItemType.VOICE : TestDefinition.ItemType.VOCABULARY,
                    item.type());
        }
    }

    @Test
    void 채워진_문항은_풀의_원래_문항_그대로다() {
        // 같은 itemId, scriptKey, guideF0 - 사본을 만들지 않는다. 바뀌는 것은 seq뿐이다.
        TestDefinition pool = pool(7);
        TestDefinition.Item original = pool.items().stream()
                .filter(item -> item.itemId().equals("v1")).findFirst().orElseThrow();
        TestDefinition.Item filled = VoiceSets.derive(pool).get(1).items().get(4);

        assertEquals("v1", filled.itemId());
        assertEquals(original.scriptKey(), filled.scriptKey());
        assertSame(original.guideF0(), filled.guideF0());
        assertEquals(5, filled.seq());
    }

    @Test
    void 세트_정의는_버전_속성을_풀에서_그대로_물려받는다() {
        TestDefinition set = VoiceSets.derive(pool(5)).getFirst();
        assertEquals("gn-test", set.testVersion());
        assertEquals("sv-0.3", set.scoreVersion());
        assertEquals("GYEONGNAM", set.dialect());
        assertEquals(240, set.estimatedDurationSec());
    }

    /** 음성 N + 어휘 5 풀 - 어휘를 늘리지 않던 KAN-182 때의 모양이다. */
    private static TestDefinition pool(int voiceCount) {
        return pool(voiceCount, 5);
    }

    /** 음성 N + 어휘 M 풀 (seq 오름차순, 앞쪽은 교차하고 남는 쪽이 뒤에 붙는다) */
    private static TestDefinition pool(int voiceCount, int vocabularyCount) {
        List<TestDefinition.Item> items = new ArrayList<>();
        int seq = 1;
        for (int i = 1; i <= Math.max(voiceCount, vocabularyCount); i++) {
            if (i <= voiceCount) {
                TestDefinition.Item voice = DefinitionFixtures.voice("v" + i, seq++);
                items.add(new TestDefinition.Item(voice.itemId(), voice.seq(), voice.type(), voice.prompt(),
                        "1|" + i, voice.guideF0(), null, null));
            }
            if (i <= vocabularyCount) {
                items.add(DefinitionFixtures.vocabulary("w" + i, seq++));
            }
        }
        return new TestDefinition("gn-test", "sv-0.3", "GYEONGNAM", 240, items);
    }
}
