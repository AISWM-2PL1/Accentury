package app.accentury.backend.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 점수 정책 발행 검증의 단위 명세 (KAN-21).
 * <p>
 * 유효한 정책을 한 곳씩 망가뜨려 발행 거부(기동 실패)를 확인한다 -
 * TestDefinitionRegistryTest와 같은 구성이다.
 */
class ScorePolicyRegistryTest {

    @Test
    void 유효한_정책은_검증을_통과한다() {
        ScorePolicyRegistry.validate(valid());
    }

    // === KAN-21 AC - scoreVersion만으로 가중치와 경계값을 재현할 수 있다 ===

    @Test
    void sv03_seed는_확정_집계식_그대로다() {
        ScorePolicy policy = registry().get("sv-0.3");

        assertEquals(2, policy.intonationWeight());    // 음성 가중치 2배 (2026-07-27 확정)
        assertEquals(1, policy.vocabularyWeight());
        assertEquals(List.of("OUTSIDER", "TRAVELER", "WANNABE", "HONORARY", "NATIVE"),
                policy.tiers().stream().map(ScorePolicy.Tier::code).toList());
        assertEquals(List.of("외지인", "여행객", "사투리 호소인", "명예주민", "경남 토박이"),
                policy.tiers().stream().map(ScorePolicy.Tier::name).toList());
        assertEquals(List.of(1, 2, 3, 4, 5),
                policy.tiers().stream().map(ScorePolicy.Tier::rank).toList());
        assertEquals(List.of(0, 20, 40, 60, 80),
                policy.tiers().stream().map(ScorePolicy.Tier::minScore).toList());
    }

    @Test
    void sv03_seed에는_억양_전처리가_없다() {
        // sv-0.3.json 무변경 - 전처리 필드가 없는 정책은 계수 1(100%)로 읽는다 (KAN-200 AC).
        ScorePolicy policy = registry().get("sv-0.3");
        assertNull(policy.intonationPreprocess());
        for (int sum = 0; sum <= 500; sum++) {
            assertEquals(100, policy.intonationCoefficientPercent(sum, 5));
        }
    }

    // === KAN-200 - sv-0.4는 억양 전처리 규칙만 더하고 가중치와 등급 표는 sv-0.3과 같다 ===

    @Test
    void sv04_seed는_전처리_규칙만_더한_sv03이다() {
        ScorePolicy sv03 = registry().get("sv-0.3");
        ScorePolicy sv04 = registry().get("sv-0.4");

        assertEquals(sv03.intonationWeight(), sv04.intonationWeight());
        assertEquals(sv03.vocabularyWeight(), sv04.vocabularyWeight());
        assertEquals(sv03.tiers(), sv04.tiers());
        assertEquals(new ScorePolicy.IntonationPreprocess(5, 5), sv04.intonationPreprocess());
    }

    @Test
    void sv04_계수는_평균을_5의_배수로_올린_값_나누기_100이다() {
        // 계수표(KAN-200): 상한 포함 - 95는 91~95 구간(0.95), 95.2는 96~100 구간(1.00).
        ScorePolicy.IntonationPreprocess rule = registry().get("sv-0.4").intonationPreprocess();
        assertEquals(20, rule.bandCount());
        assertEquals(100, rule.coefficientPercent(500, 5));    // 평균 100
        assertEquals(100, rule.coefficientPercent(476, 5));    // 평균 95.2
        assertEquals(95, rule.coefficientPercent(475, 5));     // 평균 95
        assertEquals(95, rule.coefficientPercent(455, 5));     // 평균 91
        assertEquals(90, rule.coefficientPercent(450, 5));     // 평균 90
        assertEquals(85, rule.coefficientPercent(425, 5));     // 평균 85
        assertEquals(10, rule.coefficientPercent(50, 5));      // 평균 10
        assertEquals(5, rule.coefficientPercent(25, 5));       // 평균 5
        assertEquals(5, rule.coefficientPercent(1, 5));        // 평균 0.2
        assertEquals(0, rule.coefficientPercent(0, 5));        // 평균 0
    }

    // === KAN-200 AC - 잘못된 전처리 규칙은 기동 실패다 ===

    @Test
    void 전처리_구간_폭이_100의_약수가_아니면_발행_거부다() {
        assertRejectedPreprocess(new ScorePolicy.IntonationPreprocess(0, 5), "bandWidth");
        assertRejectedPreprocess(new ScorePolicy.IntonationPreprocess(-5, 5), "bandWidth");
        assertRejectedPreprocess(new ScorePolicy.IntonationPreprocess(3, 5), "bandWidth");
    }

    @Test
    void 전처리_감소_폭이_양수가_아니면_발행_거부다() {
        assertRejectedPreprocess(new ScorePolicy.IntonationPreprocess(5, 0), "coefficientStepPercent");
        assertRejectedPreprocess(new ScorePolicy.IntonationPreprocess(5, -5), "coefficientStepPercent");
    }

    @Test
    void 전처리_계수가_0_아래로_내려가면_발행_거부다() {
        // 폭 5 x 구간 20에서 감소 6%면 최하위 구간의 계수가 -14%다 - 원점수가 있는데 억양이 음수가 된다.
        assertRejectedPreprocess(new ScorePolicy.IntonationPreprocess(5, 6), "0~1");
        assertRejectedPreprocess(new ScorePolicy.IntonationPreprocess(10, 11), "0~1");
    }

    @Test
    void 계수가_0_이상_1_이하인_규칙은_전부_단조_비감소라_발행된다() {
        // 구간 안에서는 합에 비례하고 구간 경계에서는 계수가 커지므로 위로만 뛴다 - 검증기가
        // 합 0~500을 전수 확인한다. 폭과 감소 폭의 유효한 조합 몇 개로 그 성질을 확인한다.
        ScorePolicyRegistry.validate(withPreprocess(valid(), new ScorePolicy.IntonationPreprocess(5, 5)));
        ScorePolicyRegistry.validate(withPreprocess(valid(), new ScorePolicy.IntonationPreprocess(5, 1)));
        ScorePolicyRegistry.validate(withPreprocess(valid(), new ScorePolicy.IntonationPreprocess(10, 10)));
        ScorePolicyRegistry.validate(withPreprocess(valid(), new ScorePolicy.IntonationPreprocess(100, 100)));
    }

    // === 등급 표의 무결성 - 어떤 종합 점수든 등급이 결정적으로 나와야 한다 ===

    @Test
    void 등급이_5개가_아니면_발행_거부다() {
        List<ScorePolicy.Tier> four = new ArrayList<>(valid().tiers());
        four.removeLast();
        assertThrows(IllegalStateException.class,
                () -> ScorePolicyRegistry.validate(withTiers(valid(), four)));
    }

    @Test
    void 첫_등급의_minScore가_0이_아니면_발행_거부다() {
        // 0~19점 구간의 등급이 없어지는 판정 불능 정책을 막는다.
        ScorePolicy broken = withTier(valid(), "OUTSIDER",
                tier -> new ScorePolicy.Tier(tier.code(), tier.name(), tier.rank(), 5));
        IllegalStateException rejected =
                assertThrows(IllegalStateException.class, () -> ScorePolicyRegistry.validate(broken));
        assertTrue(rejected.getMessage().contains("minScore"), rejected.getMessage());
    }

    @Test
    void minScore가_순증가하지_않으면_발행_거부다() {
        ScorePolicy broken = withTier(valid(), "HONORARY",
                tier -> new ScorePolicy.Tier(tier.code(), tier.name(), tier.rank(), 40));
        assertThrows(IllegalStateException.class, () -> ScorePolicyRegistry.validate(broken));
    }

    @Test
    void minScore가_100을_넘으면_발행_거부다() {
        ScorePolicy broken = withTier(valid(), "NATIVE",
                tier -> new ScorePolicy.Tier(tier.code(), tier.name(), tier.rank(), 101));
        assertThrows(IllegalStateException.class, () -> ScorePolicyRegistry.validate(broken));
    }

    @Test
    void rank가_1부터_연속_오름차순이_아니면_발행_거부다() {
        ScorePolicy broken = withTier(valid(), "TRAVELER",
                tier -> new ScorePolicy.Tier(tier.code(), tier.name(), 5, tier.minScore()));
        IllegalStateException rejected =
                assertThrows(IllegalStateException.class, () -> ScorePolicyRegistry.validate(broken));
        assertTrue(rejected.getMessage().contains("rank"), rejected.getMessage());
    }

    @Test
    void 등급_code가_중복되면_발행_거부다() {
        ScorePolicy broken = withTier(valid(), "TRAVELER",
                tier -> new ScorePolicy.Tier("OUTSIDER", tier.name(), tier.rank(), tier.minScore()));
        assertThrows(IllegalStateException.class, () -> ScorePolicyRegistry.validate(broken));
    }

    @Test
    void 계약에_없는_등급_code는_발행_거부다() {
        // code는 클라이언트가 등급별 자산을 찾는 키다 - 오타(HONORAY)는 발행 시점에 잡는다 (Codex sol 리뷰 P2).
        ScorePolicy broken = withTier(valid(), "HONORARY",
                tier -> new ScorePolicy.Tier("HONORAY", tier.name(), tier.rank(), tier.minScore()));
        IllegalStateException rejected =
                assertThrows(IllegalStateException.class, () -> ScorePolicyRegistry.validate(broken));
        assertTrue(rejected.getMessage().contains("HONORAY"), rejected.getMessage());
    }

    @Test
    void 등급_이름이_비면_발행_거부다() {
        ScorePolicy broken = withTier(valid(), "WANNABE",
                tier -> new ScorePolicy.Tier(tier.code(), " ", tier.rank(), tier.minScore()));
        assertThrows(IllegalStateException.class, () -> ScorePolicyRegistry.validate(broken));
    }

    // === 가중치 - 2:1은 sv-0.3 확정값이고, 0은 한 축의 무단 폐기다 ===

    @Test
    void 가중치가_양수가_아니면_발행_거부다() {
        assertThrows(IllegalStateException.class, () -> ScorePolicyRegistry.validate(
                new ScorePolicy("sv-0.3", 0, 1, null, valid().tiers())));
        assertThrows(IllegalStateException.class, () -> ScorePolicyRegistry.validate(
                new ScorePolicy("sv-0.3", 2, -1, null, valid().tiers())));
    }

    @Test
    void 가중치가_상한을_넘으면_발행_거부다() {
        // 상한 없는 가중치는 집계기의 int 산술을 오버플로시킬 수 있다 (Codex sol 리뷰 P2).
        assertThrows(IllegalStateException.class, () -> ScorePolicyRegistry.validate(
                new ScorePolicy("sv-0.3", 10_000_000, 10_000_000, null, valid().tiers())));
        assertThrows(IllegalStateException.class, () -> ScorePolicyRegistry.validate(
                new ScorePolicy("sv-0.3", 2, 101, null, valid().tiers())));
    }

    // === 레지스트리 기동 검사 ===

    @Test
    void seed의_모르는_키는_발행_거부다() {
        // optional 필드의 키 오타는 기본값(무시)이면 "전처리 없음"으로 조용히 읽혀 계수 1로 채점된다.
        String typo = """
                { "scoreVersion": "sv-9.9", "intonationWeight": 2, "vocabularyWeight": 1,
                  "intonationPreProcess": { "bandWidth": 5, "coefficientStepPercent": 5 },
                  "tiers": [] }
                """;
        IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> ScorePolicyRegistry.read(JsonMapper.builder().build(),
                        new ByteArrayResource(typo.getBytes(StandardCharsets.UTF_8))));
        assertTrue(rejected.getMessage().contains("읽을 수 없다"), rejected.getMessage());
    }

    @Test
    void 정책_seed가_로드된다() {
        assertTrue(registry().isPublished("sv-0.4"));
        // "활성 점수 버전의 seed가 있는가"를 여기서 묻던 검사는 KAN-26에서 자리를 옮겼다 -
        // 활성 점수 버전이라는 설정 자체가 사라지고, 이제는 발행된 모든 테스트 정의가
        // 참조하는 정책이 있는지를 TestDefinitionRegistry가 기동 시 확인한다.
        assertTrue(registry().isPublished("sv-0.3"));
        assertFalse(registry().isPublished("sv-9.9"));
    }

    @Test
    void 발행되지_않은_점수_버전_조회는_배포_사고다() {
        // 세션이 고정한 버전(§5.4)의 정책이 사라진 상황 - 클라이언트 404가 아니라 500이어야 한다.
        assertThrows(IllegalStateException.class, () -> registry().get("sv-0.0"));
    }

    @Test
    void 발행된_정책의_등급_표는_불변이다() {
        // 소비자가 리스트를 고치면 레지스트리 안의 정책이 바뀌어 결정성이 깨진다 (Codex sol 리뷰 P2).
        List<ScorePolicy.Tier> tiers = registry().get("sv-0.3").tiers();
        assertThrows(UnsupportedOperationException.class, tiers::removeLast);
    }

    // === 픽스처 ===

    private static ScorePolicyRegistry registry() {
        return new ScorePolicyRegistry(JsonMapper.builder().build());
    }

    /** sv-0.3과 같은 5등급 정책. 각 테스트가 한 곳씩 망가뜨린다. */
    private static ScorePolicy valid() {
        return new ScorePolicy("sv-0.3", 2, 1, null, List.of(
                new ScorePolicy.Tier("OUTSIDER", "외지인", 1, 0),
                new ScorePolicy.Tier("TRAVELER", "여행객", 2, 20),
                new ScorePolicy.Tier("WANNABE", "사투리 호소인", 3, 40),
                new ScorePolicy.Tier("HONORARY", "명예주민", 4, 60),
                new ScorePolicy.Tier("NATIVE", "경남 토박이", 5, 80)));
    }

    private static ScorePolicy withPreprocess(ScorePolicy base, ScorePolicy.IntonationPreprocess rule) {
        return new ScorePolicy(base.scoreVersion(), base.intonationWeight(), base.vocabularyWeight(),
                rule, base.tiers());
    }

    private static void assertRejectedPreprocess(ScorePolicy.IntonationPreprocess rule, String messagePart) {
        IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> ScorePolicyRegistry.validate(withPreprocess(valid(), rule)));
        assertTrue(rejected.getMessage().contains(messagePart), rejected.getMessage());
    }

    private static ScorePolicy withTiers(ScorePolicy base, List<ScorePolicy.Tier> tiers) {
        return new ScorePolicy(base.scoreVersion(), base.intonationWeight(), base.vocabularyWeight(),
                base.intonationPreprocess(), tiers);
    }

    private static ScorePolicy withTier(ScorePolicy base, String code,
                                        java.util.function.UnaryOperator<ScorePolicy.Tier> change) {
        List<ScorePolicy.Tier> tiers = base.tiers().stream()
                .map(tier -> tier.code().equals(code) ? change.apply(tier) : tier)
                .toList();
        return withTiers(base, tiers);
    }
}
