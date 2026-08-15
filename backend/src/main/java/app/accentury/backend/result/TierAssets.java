package app.accentury.backend.result;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.scoring.ScorePolicyRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 등급별 결과 자산 설정의 검증과 조회 (KAN-25, API 명세서 §3.7).
 * <p>
 * 자산의 정본은 설정({@code accentury.result})이고, 이 클래스는 기동 시 완결성을
 * 강제한다 - 등급 code 5개({@link ScorePolicyRegistry#TIER_CODES}) 전부에 빈 값 없는
 * 자산이 있어야 서버가 뜬다. 결과 확정 후에 자산이 없다는 것을 알게 되면 이미 저장된
 * 결과를 응답으로 만들 수 없기 때문에, 점수 정책 seed 검증과 같은 기동 실패로 앞당긴다.
 * <p>
 * 설정 키는 소문자다 - relaxed binding이 맵 키의 대문자를 보존하지 않아, 저장된 등급
 * code(대문자)로의 조회는 대소문자를 무시하고 맞춘다. 로드 후 불변이라 잠금 없이 읽는다.
 */
@Component
class TierAssets {

    private final Map<String, AccenturyProperties.TierAsset> byCode = new HashMap<>();
    private final String webTestUrl;

    TierAssets(AccenturyProperties properties) {
        AccenturyProperties.Result result = properties.result();
        require(hasText(result.webTestUrl()),
                "accentury.result.web-test-url이 비어 있다 - 공유 웹 테스트 URL은 §3.7 응답 필수값이다");
        this.webTestUrl = result.webTestUrl();

        for (Map.Entry<String, AccenturyProperties.TierAsset> entry : result.tiers().entrySet()) {
            String code = entry.getKey().toUpperCase(Locale.ROOT);
            require(ScorePolicyRegistry.TIER_CODES.contains(code),
                    "모르는 등급 code의 자산이다: " + entry.getKey()
                            + " (허용: " + ScorePolicyRegistry.TIER_CODES + ")");
            AccenturyProperties.TierAsset asset = entry.getValue();
            require(hasText(asset.comment()), code + "의 comment가 비어 있다");
            require(hasText(asset.imageUrl()), code + "의 image-url이 비어 있다");
            require(hasText(asset.shareText()), code + "의 share-text가 비어 있다");
            byCode.put(code, asset);
        }
        for (String code : ScorePolicyRegistry.TIER_CODES) {
            require(byCode.containsKey(code), code + "의 자산 설정(accentury.result.tiers)이 없다");
        }
    }

    /** 공유 카드가 여는 웹 테스트 완성 URL - 전 등급 공통 (2026-08-14 확정, KAN-30) */
    String webTestUrl() {
        return webTestUrl;
    }

    /**
     * 등급 code의 자산. 저장된 결과의 code는 판정 시점에 {@link ScorePolicyRegistry}가
     * 검증한 값이고 기동 검증이 5개 전부를 강제했으므로, 없으면 배포 사고다 - 500으로 낸다.
     */
    AccenturyProperties.TierAsset assetFor(String tierCode) {
        AccenturyProperties.TierAsset asset = byCode.get(tierCode);
        if (asset == null) {
            throw new IllegalStateException("자산 설정이 없는 등급 code다: " + tierCode);
        }
        return asset;
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException("결과 자산 설정 거부 - " + message);
        }
    }
}
