package app.accentury.backend.testdefinition;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import app.accentury.backend.scoring.ScorePolicyRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 발행 전 검증의 단위 명세 (KAN-10 AC와 명세서 §6).
 * <p>
 * 유효한 정의를 한 곳씩 망가뜨려 발행 거부(기동 실패)를 확인한다.
 * KAN-26 관리자 발행 API가 이 검증을 그대로 가져간다.
 */
class TestDefinitionRegistryTest {

    @Test
    void 유효한_정의는_검증을_통과한다() {
        TestDefinitionRegistry.validate(valid());
    }

    // === KAN-10 AC - 모든 VOICE 문항에 guideF0 포함, 누락 시 발행 거부 ===

    @Test
    void VOICE_문항에_guideF0가_없으면_발행_거부다() {
        TestDefinition broken = withItem(valid(), "v3",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        null, null, null));
        IllegalStateException rejected =
                assertThrows(IllegalStateException.class, () -> TestDefinitionRegistry.validate(broken));
        assertTrue(rejected.getMessage().contains("guideF0"), rejected.getMessage());
    }

    @Test
    void 허용_밴드가_없으면_발행_거부다() {
        // bandLow와 bandHigh는 required다 (2026-08-09 확정, §3.2, §6)
        TestDefinition broken = withItem(valid(), "v2",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        new TestDefinition.GuideF0("semitone", 10, List.of(0.1, 0.2, 0.3), null, null),
                        null, null));
        IllegalStateException rejected =
                assertThrows(IllegalStateException.class, () -> TestDefinitionRegistry.validate(broken));
        assertTrue(rejected.getMessage().contains("bandLow"), rejected.getMessage());
    }

    @Test
    void 허용_밴드_길이가_values와_다르면_발행_거부다() {
        TestDefinition broken = withItem(valid(), "v1",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        new TestDefinition.GuideF0("semitone", 10, List.of(0.1, 0.2, 0.3),
                                List.of(-1.0), List.of(1.6, 1.7, 1.8)),
                        null, null));
        assertThrows(IllegalStateException.class, () -> TestDefinitionRegistry.validate(broken));
    }

    // === §6 - 모든 VOCABULARY 문항에 정답 존재 ===

    @Test
    void VOCABULARY_문항에_정답이_없으면_발행_거부다() {
        TestDefinition broken = withItem(valid(), "w2",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        null, item.choices(), null));
        IllegalStateException rejected =
                assertThrows(IllegalStateException.class, () -> TestDefinitionRegistry.validate(broken));
        assertTrue(rejected.getMessage().contains("정답"), rejected.getMessage());
    }

    @Test
    void 정답이_선택지_밖이면_발행_거부다() {
        TestDefinition broken = withItem(valid(), "w2",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        null, item.choices(), "w9z"));
        assertThrows(IllegalStateException.class, () -> TestDefinitionRegistry.validate(broken));
    }

    // === 문항 구성 확정 (2026-07-27) - 음성 5 + 어휘 5, seq 고정 ===

    @Test
    void 음성5_어휘5_구성이_아니면_발행_거부다() {
        List<TestDefinition.Item> items = new ArrayList<>(valid().items());
        items.removeIf(item -> item.itemId().equals("v5"));
        items.add(vocabulary("w6", 9));
        assertThrows(IllegalStateException.class,
                () -> TestDefinitionRegistry.validate(withItems(valid(), items)));
    }

    @Test
    void seq가_1부터_연속하지_않으면_발행_거부다() {
        TestDefinition broken = withItem(valid(), "w5",
                item -> new TestDefinition.Item(item.itemId(), 12, item.type(), item.prompt(),
                        null, item.choices(), item.correctChoiceId()));
        assertThrows(IllegalStateException.class, () -> TestDefinitionRegistry.validate(broken));
    }

    @Test
    void itemId가_중복되면_발행_거부다() {
        TestDefinition broken = withItem(valid(), "v2",
                item -> new TestDefinition.Item("v1", item.seq(), item.type(), item.prompt(),
                        item.guideF0(), null, null));
        assertThrows(IllegalStateException.class, () -> TestDefinitionRegistry.validate(broken));
    }

    @Test
    void 식별자가_저장_컬럼_길이를_넘으면_발행_거부다() {
        // 제출 저장 컬럼이 varchar(40)이다 - 발행 검증이 막지 않으면 정의 조회와 답안
        // 검증은 통과하고 제출 시점의 INSERT가 500으로 터진다 (Codex sol 리뷰 P2)
        TestDefinition longItemId = withItem(valid(), "w2",
                item -> new TestDefinition.Item("w".repeat(41), item.seq(), item.type(), item.prompt(),
                        null, item.choices(), item.correctChoiceId()));
        assertThrows(IllegalStateException.class, () -> TestDefinitionRegistry.validate(longItemId));

        TestDefinition longChoiceId = withItem(valid(), "w2",
                item -> new TestDefinition.Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                        null, List.of(
                                new TestDefinition.Choice("w2a", "부추"),
                                new TestDefinition.Choice("w2b", "미나리"),
                                new TestDefinition.Choice("w2c", "쑥갓"),
                                new TestDefinition.Choice("c".repeat(41), "시금치")),
                        "w2a"));
        assertThrows(IllegalStateException.class, () -> TestDefinitionRegistry.validate(longChoiceId));
    }

    // === §6 - 경북 정의는 MVP에서 활성화 불가 ===

    @Test
    void 경남이_아닌_정의는_발행_거부다() {
        TestDefinition gyeongbuk = new TestDefinition("gb-2026.08.1", "sv-0.3", "GYEONGBUK", 240, valid().items());
        IllegalStateException rejected =
                assertThrows(IllegalStateException.class, () -> TestDefinitionRegistry.validate(gyeongbuk));
        assertTrue(rejected.getMessage().contains("경남"), rejected.getMessage());
    }

    // === 레지스트리 기동 검사 ===

    @Test
    void 활성_버전의_seed가_없으면_기동에_실패한다() {
        AccenturyProperties noSuchVersion = props("gn-9999.99.9", "sv-0.3");
        // 픽스처가 던지는 예외까지 assertThrows에 잡히면 검사를 잃은 회귀도 통과한다 - 람다 밖에서 만든다
        ScorePolicyRegistry policies = policies();
        assertThrows(IllegalStateException.class,
                () -> new TestDefinitionRegistry(JsonMapper.builder().build(), noSuchVersion, policies));
    }

    @Test
    void 활성_버전의_scoreVersion이_설정과_다르면_기동에_실패한다() {
        // 세션(설정값 고정)과 정의 응답(seed값)이 서로 다른 채점 버전을 가리키는 배포를 막는다 (Codex sol 리뷰 P2)
        AccenturyProperties mismatched = props("gn-2026.08.1", "sv-9.9");
        ScorePolicyRegistry policies = policies();
        IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> new TestDefinitionRegistry(JsonMapper.builder().build(), mismatched, policies));
        assertTrue(rejected.getMessage().contains("scoreVersion"), rejected.getMessage());
    }

    @Test
    void 정의가_참조하는_scoreVersion의_정책이_없으면_기동에_실패한다() {
        // scoreVersion 참조 유효 검증 (§6, KAN-21) - 활성이 아닌 정의라도 참조가 끊기면 발행 거부.
        // 정책 seed는 classpath 고정이라, 참조 실패 상황은 조회를 막은 레지스트리로 재현한다
        ScorePolicyRegistry noPolicies = new ScorePolicyRegistry(
                JsonMapper.builder().build(), props("gn-2026.08.1", "sv-0.3")) {
            @Override
            public boolean isPublished(String scoreVersion) {
                return false;
            }
        };
        IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> new TestDefinitionRegistry(JsonMapper.builder().build(),
                        props("gn-2026.08.1", "sv-0.3"), noPolicies));
        assertTrue(rejected.getMessage().contains("점수 정책"), rejected.getMessage());
    }

    @Test
    void 발행된_seed는_로드되고_미발행_버전은_404다() {
        AccenturyProperties active = props("gn-2026.08.1", "sv-0.3");
        TestDefinitionRegistry registry =
                new TestDefinitionRegistry(JsonMapper.builder().build(), active, policies());

        assertEquals("gn-2026.08.1", registry.get("gn-2026.08.1").response().testVersion());
        ApiException notFound =
                assertThrows(ApiException.class, () -> registry.get("gn-0000.00.0"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, notFound.code());
    }

    // === 픽스처 ===

    /** 실제 seed(sv-0.3)를 로드한 점수 정책 레지스트리 - 정의의 scoreVersion 참조 검증에 쓰인다 */
    private static ScorePolicyRegistry policies() {
        return new ScorePolicyRegistry(JsonMapper.builder().build(), props("gn-2026.08.1", "sv-0.3"));
    }

    /** 레지스트리 기동 검사용 설정. 업로드, CORS 등 무관한 항목은 기본값과 같게 둔다 */
    private static AccenturyProperties props(String testVersion, String scoreVersion) {
        return new AccenturyProperties(testVersion, scoreVersion,
                new AccenturyProperties.Session(Duration.ofMinutes(30)),
                new AccenturyProperties.Analysis(800, 3000, 30, Duration.ofHours(24),
                        Duration.ofSeconds(60), Duration.ofMinutes(5), null, Duration.ofSeconds(10), 2, 4),
                new AccenturyProperties.Upload(30),
                new AccenturyProperties.Completion(60),
                new AccenturyProperties.Cors(List.of()),
                new AccenturyProperties.Result(null, Map.of()));
    }

    /** 정본 구성과 같은 5+5와 seq 교차 정의. 각 테스트가 한 곳씩 망가뜨린다 */
    private static TestDefinition valid() {
        List<TestDefinition.Item> items = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            items.add(voice("v" + i, i * 2 - 1));
            items.add(vocabulary("w" + i, i * 2));
        }
        return new TestDefinition("gn-2026.08.1", "sv-0.3", "GYEONGNAM", 240, items);
    }

    private static TestDefinition.Item voice(String itemId, int seq) {
        // 허용 밴드는 required (2026-08-09 확정) - values와 같은 길이의 상한과 하한
        return new TestDefinition.Item(itemId, seq, TestDefinition.ItemType.VOICE, "밥 뭇나?",
                new TestDefinition.GuideF0("semitone", 10, List.of(-0.8, 0.3, 2.8),
                        List.of(-2.3, -1.2, 1.3), List.of(0.7, 1.8, 4.3)), null, null);
    }

    private static TestDefinition.Item vocabulary(String itemId, int seq) {
        List<TestDefinition.Choice> choices = List.of(
                new TestDefinition.Choice(itemId + "a", "부추"),
                new TestDefinition.Choice(itemId + "b", "미나리"),
                new TestDefinition.Choice(itemId + "c", "쑥갓"),
                new TestDefinition.Choice(itemId + "d", "시금치"));
        return new TestDefinition.Item(itemId, seq, TestDefinition.ItemType.VOCABULARY,
                "'정구지'는 표준어로 무엇일까요?", null, choices, itemId + "a");
    }

    private static TestDefinition withItems(TestDefinition base, List<TestDefinition.Item> items) {
        return new TestDefinition(base.testVersion(), base.scoreVersion(), base.dialect(),
                base.estimatedDurationSec(), items);
    }

    private static TestDefinition withItem(TestDefinition base, String itemId,
                                           java.util.function.UnaryOperator<TestDefinition.Item> change) {
        List<TestDefinition.Item> items = base.items().stream()
                .map(item -> item.itemId().equals(itemId) ? change.apply(item) : item)
                .toList();
        return withItems(base, items);
    }
}
