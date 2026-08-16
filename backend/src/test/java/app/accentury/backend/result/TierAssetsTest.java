package app.accentury.backend.result;

import app.accentury.backend.PropertiesFixture;
import app.accentury.backend.common.AccenturyProperties;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 등급별 결과 자산 설정 검증의 단위 명세 (KAN-25).
 * <p>
 * 유효한 설정을 한 곳씩 망가뜨려 기동 거부를 확인한다 - ScorePolicyRegistryTest와
 * 같은 구성이다. 자산 없는 등급이 결과 확정 뒤에 발견되면 저장된 결과를 응답으로
 * 만들 수 없으므로, 전부 기동 시점에 잡혀야 한다.
 */
class TierAssetsTest {

    @Test
    void 유효한_설정은_소문자_키를_대문자_code로_조회한다() {
        TierAssets assets = new TierAssets(props(validResult()));

        // 설정 키는 소문자(relaxed binding), 저장된 등급 code는 대문자다 - 조회가 잇는다
        assertEquals("honorary 코멘트", assets.assetFor("HONORARY").comment());
        assertEquals("https://img.test/native.png", assets.assetFor("NATIVE").imageUrl());
        assertEquals("나는 외지인!", assets.assetFor("OUTSIDER").shareText());
        assertEquals("https://web.test/t?c=share", assets.webTestUrl());
    }

    @Test
    void 등급_자산이_하나라도_빠지면_기동_거부다() {
        Map<String, AccenturyProperties.TierAsset> tiers = new HashMap<>(validTiers());
        tiers.remove("wannabe");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TierAssets(props(new AccenturyProperties.Result("https://web.test/t", tiers))));
        assertTrue(e.getMessage().contains("WANNABE"));
    }

    @Test
    void 모르는_등급_code의_자산은_기동_거부다() {
        // 오타(honorery 등)는 조용한 누락으로 이어진다 - 5개 검사만으로는 오타+누락 조합을 못 잡는다
        Map<String, AccenturyProperties.TierAsset> tiers = new HashMap<>(validTiers());
        tiers.put("legend", tiers.get("native"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TierAssets(props(new AccenturyProperties.Result("https://web.test/t", tiers))));
        assertTrue(e.getMessage().contains("legend"));
    }

    @Test
    void 빈_값이_있으면_기동_거부다() {
        Map<String, AccenturyProperties.TierAsset> tiers = new HashMap<>(validTiers());
        tiers.put("traveler", new AccenturyProperties.TierAsset(" ", "https://img.test/t.png", "문구"));

        assertThrows(IllegalStateException.class,
                () -> new TierAssets(props(new AccenturyProperties.Result("https://web.test/t", tiers))));
    }

    @Test
    void 웹_테스트_URL이_없으면_기동_거부다() {
        assertThrows(IllegalStateException.class,
                () -> new TierAssets(props(new AccenturyProperties.Result(null, validTiers()))));
    }

    // === 헬퍼 ===

    private static AccenturyProperties.Result validResult() {
        return new AccenturyProperties.Result("https://web.test/t?c=share", validTiers());
    }

    private static Map<String, AccenturyProperties.TierAsset> validTiers() {
        return Map.of(
                "outsider", new AccenturyProperties.TierAsset("outsider 코멘트", "https://img.test/outsider.png", "나는 외지인!"),
                "traveler", new AccenturyProperties.TierAsset("traveler 코멘트", "https://img.test/traveler.png", "나는 여행객!"),
                "wannabe", new AccenturyProperties.TierAsset("wannabe 코멘트", "https://img.test/wannabe.png", "나는 호소인!"),
                "honorary", new AccenturyProperties.TierAsset("honorary 코멘트", "https://img.test/honorary.png", "나는 명예주민!"),
                "native", new AccenturyProperties.TierAsset("native 코멘트", "https://img.test/native.png", "나는 토박이!"));
    }

    /** 기본값 조합에 result만 갈아끼운 설정 - 검증 대상은 result뿐이다 */
    private static AccenturyProperties props(AccenturyProperties.Result result) {
        return PropertiesFixture.withResult(result);
    }
}
