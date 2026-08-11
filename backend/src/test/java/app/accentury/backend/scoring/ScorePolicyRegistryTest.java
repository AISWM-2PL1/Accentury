package app.accentury.backend.scoring;

import app.accentury.backend.common.AccenturyProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // 0~19점 구간의 등급이 없어지는 판정 불능 정책을 막는다
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
        // code는 클라이언트가 등급별 자산을 찾는 키다 - 오타(HONORAY)는 발행 시점에 잡는다 (Codex sol 리뷰 P2)
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
                new ScorePolicy("sv-0.3", 0, 1, valid().tiers())));
        assertThrows(IllegalStateException.class, () -> ScorePolicyRegistry.validate(
                new ScorePolicy("sv-0.3", 2, -1, valid().tiers())));
    }

    @Test
    void 가중치가_상한을_넘으면_발행_거부다() {
        // 상한 없는 가중치는 집계기의 int 산술을 오버플로시킬 수 있다 (Codex sol 리뷰 P2)
        assertThrows(IllegalStateException.class, () -> ScorePolicyRegistry.validate(
                new ScorePolicy("sv-0.3", 10_000_000, 10_000_000, valid().tiers())));
        assertThrows(IllegalStateException.class, () -> ScorePolicyRegistry.validate(
                new ScorePolicy("sv-0.3", 2, 101, valid().tiers())));
    }

    // === 레지스트리 기동 검사 ===

    @Test
    void 활성_점수_버전의_seed가_없으면_기동에_실패한다() {
        IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> new ScorePolicyRegistry(JsonMapper.builder().build(), props("sv-9.9")));
        assertTrue(rejected.getMessage().contains("sv-9.9"), rejected.getMessage());
    }

    @Test
    void 발행되지_않은_점수_버전_조회는_배포_사고다() {
        // 세션이 고정한 버전(§5.4)의 정책이 사라진 상황 - 클라이언트 404가 아니라 500이어야 한다
        assertThrows(IllegalStateException.class, () -> registry().get("sv-0.0"));
    }

    @Test
    void 발행된_정책의_등급_표는_불변이다() {
        // 소비자가 리스트를 고치면 레지스트리 안의 정책이 바뀌어 결정성이 깨진다 (Codex sol 리뷰 P2)
        List<ScorePolicy.Tier> tiers = registry().get("sv-0.3").tiers();
        assertThrows(UnsupportedOperationException.class, tiers::removeLast);
    }

    // === 픽스처 ===

    private static ScorePolicyRegistry registry() {
        return new ScorePolicyRegistry(JsonMapper.builder().build(), props("sv-0.3"));
    }

    /** 레지스트리 기동 검사용 설정. 점수 버전 외 항목은 기본값과 같게 둔다 */
    private static AccenturyProperties props(String scoreVersion) {
        return new AccenturyProperties("gn-2026.08.1", scoreVersion,
                new AccenturyProperties.Session(Duration.ofMinutes(30)),
                new AccenturyProperties.Analysis(800, 3000, 30, Duration.ofHours(24),
                        Duration.ofSeconds(60), Duration.ofMinutes(5), null, Duration.ofSeconds(10), 2, 4),
                new AccenturyProperties.Upload(30),
                new AccenturyProperties.Cors(List.of()));
    }

    /** sv-0.3과 같은 5등급 정책. 각 테스트가 한 곳씩 망가뜨린다 */
    private static ScorePolicy valid() {
        return new ScorePolicy("sv-0.3", 2, 1, List.of(
                new ScorePolicy.Tier("OUTSIDER", "외지인", 1, 0),
                new ScorePolicy.Tier("TRAVELER", "여행객", 2, 20),
                new ScorePolicy.Tier("WANNABE", "사투리 호소인", 3, 40),
                new ScorePolicy.Tier("HONORARY", "명예주민", 4, 60),
                new ScorePolicy.Tier("NATIVE", "경남 토박이", 5, 80)));
    }

    private static ScorePolicy withTiers(ScorePolicy base, List<ScorePolicy.Tier> tiers) {
        return new ScorePolicy(base.scoreVersion(), base.intonationWeight(), base.vocabularyWeight(), tiers);
    }

    private static ScorePolicy withTier(ScorePolicy base, String code,
                                        java.util.function.UnaryOperator<ScorePolicy.Tier> change) {
        List<ScorePolicy.Tier> tiers = base.tiers().stream()
                .map(tier -> tier.code().equals(code) ? change.apply(tier) : tier)
                .toList();
        return withTiers(base, tiers);
    }
}
