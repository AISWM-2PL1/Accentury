package app.accentury.backend.testdefinition;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 발행본 조립기 - 정의 검증과 활성 전환 명세가 함께 쓴다.
 * <p>
 * 정본 구성과 같은 음성 5 + 어휘 5, seq 교차다. 각 테스트가 여기서 받은 정의를 한 곳씩
 * 망가뜨려 발행 거부를 확인한다.
 */
final class DefinitionFixtures {

    private DefinitionFixtures() {
    }

    /** 검증을 통과하는 정의 */
    static TestDefinition valid() {
        List<TestDefinition.Item> items = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            items.add(voice("v" + i, i * 2 - 1));
            items.add(vocabulary("w" + i, i * 2));
        }
        return new TestDefinition("gn-2026.08.1", "sv-0.3", "GYEONGNAM", 240, items);
    }

    /** DB 행에 넣을 발행본 JSON - 저장 경로가 파일이 아니라 컬럼이 됐다 (KAN-26). */
    static String body(String testVersion, String dialect) {
        TestDefinition base = valid();
        return body(new TestDefinition(
                testVersion, base.scoreVersion(), dialect, base.estimatedDurationSec(), base.items()));
    }

    static String body(TestDefinition definition) {
        return JsonMapper.builder().build().writeValueAsString(definition);
    }

    /**
     * 음성 N + 어휘 5 풀 (KAN-182) - 앞 5쌍은 현행과 같은 교차, 6번째부터는 음성만 뒤에 붙는다.
     * seq는 풀 기준 1..N+5 연속이다. scriptKey는 없다.
     */
    static TestDefinition pool(int voiceCount) {
        List<TestDefinition.Item> items = new ArrayList<>();
        int seq = 1;
        for (int i = 1; i <= Math.max(voiceCount, 5); i++) {
            if (i <= voiceCount) {
                items.add(voice("v" + i, seq++));
            }
            if (i <= 5) {
                items.add(vocabulary("w" + i, seq++));
            }
        }
        return new TestDefinition("gn-2026.09.t" + voiceCount, "sv-0.3", "GYEONGNAM", 240, items);
    }

    static TestDefinition.Item voice(String itemId, int seq) {
        // 허용 밴드는 required (2026-08-09 확정) - values와 같은 길이의 상한과 하한
        return new TestDefinition.Item(itemId, seq, TestDefinition.ItemType.VOICE, "밥 뭇나?",
                guideF0("semitone", 10, 3), null, null);
    }

    static TestDefinition.Item vocabulary(String itemId, int seq) {
        List<TestDefinition.Choice> choices = List.of(
                new TestDefinition.Choice(itemId + "a", "부추"),
                new TestDefinition.Choice(itemId + "b", "미나리"),
                new TestDefinition.Choice(itemId + "c", "쑥갓"),
                new TestDefinition.Choice(itemId + "d", "시금치"));
        return new TestDefinition.Item(itemId, seq, TestDefinition.ItemType.VOCABULARY,
                "'정구지'는 표준어로 무엇일까요?", null, choices, itemId + "a");
    }

    static TestDefinition.GuideF0 guideF0(String unit, int frameIntervalMs, int length) {
        List<Double> values = new ArrayList<>();
        List<Double> low = new ArrayList<>();
        List<Double> high = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            values.add(i * 0.5);
            low.add(i * 0.5 - 1.5);
            high.add(i * 0.5 + 1.5);
        }
        return new TestDefinition.GuideF0(unit, frameIntervalMs, values, low, high);
    }
}
