package app.accentury.backend.result;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.scoring.ScorePolicyRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 등급별 결과 자산 설정의 검증과 조회 (KAN-25, KAN-132, API 명세서 §3.7).
 * <p>
 * 자산의 정본은 설정({@code accentury.result})이고, 이 클래스는 기동 시 완결성을
 * 강제한다 - 등급 code 5개({@link ScorePolicyRegistry#TIER_CODES}) 전부에 빈 값 없는
 * 자산이 있어야 서버가 뜬다. 결과 확정 후에 자산이 없다는 것을 알게 되면 이미 저장된
 * 결과를 응답으로 만들 수 없기 때문에, 점수 정책 seed 검증과 같은 기동 실패로 앞당긴다.
 * <p>
 * 공유 이미지 URL은 등급마다 적지 않고 기준 URL 하나({@code asset-base-url})에 등급 code를
 * 붙여 만든다 (KAN-132) - {@code {기준}/{code 소문자}.png}. 파일명은 자산 정본
 * ({@code assets/share/}, KAN-162)이 등급 code로 고정해 두었고, 환경마다 다른 것은 도메인뿐이다.
 * 이미지 교체는 S3의 그 키를 덮어쓰는 것으로 끝나고 설정과 배포는 바뀌지 않는다.
 * <p>
 * 설정 키는 소문자다 - relaxed binding이 맵 키의 대문자를 보존하지 않아, 저장된 등급
 * code(대문자)로의 조회는 대소문자를 무시하고 맞춘다. 로드 후 불변이라 잠금 없이 읽는다.
 */
@Component
class TierAssets {

    /** 등급 이미지 파일의 확장자 - 자산 정본(assets/share/*.png)과 같다. */
    static final String IMAGE_EXTENSION = ".png";

    /**
     * 등급 하나의 응답용 자산 - 설정({@link AccenturyProperties.TierAsset})에 기준 URL로 만든
     * 이미지 URL을 더한 것이다. {@code /result} 응답(§3.7)의 comment, share.imageUrl, share.text.
     */
    record Asset(String comment, String imageUrl, String shareText) {
    }

    private final Map<String, Asset> byCode = new HashMap<>();
    private final String webTestUrl;

    TierAssets(AccenturyProperties properties) {
        AccenturyProperties.Result result = properties.result();
        require(hasText(result.webTestUrl()),
                "accentury.result.web-test-url이 비어 있다 - 공유 웹 테스트 URL은 §3.7 응답 필수값이다");
        this.webTestUrl = result.webTestUrl();

        String baseUrl = result.assetBaseUrl();
        require(hasText(baseUrl),
                "accentury.result.asset-base-url이 비어 있다 - 등급 이미지 기준 URL은 §3.7 share.imageUrl의 출처다 (KAN-132)");
        // 카카오는 https 이미지만 자기 서버로 가져간다 (assets/share/README). 상대 경로나 http면 카드가 이미지 없이 나간다.
        require(baseUrl.startsWith("https://"),
                "accentury.result.asset-base-url은 https:// 절대 URL이어야 한다: " + baseUrl);
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        for (Map.Entry<String, AccenturyProperties.TierAsset> entry : result.tiers().entrySet()) {
            String code = entry.getKey().toUpperCase(Locale.ROOT);
            require(ScorePolicyRegistry.TIER_CODES.contains(code),
                    "모르는 등급 code의 자산이다: " + entry.getKey()
                            + " (허용: " + ScorePolicyRegistry.TIER_CODES + ")");
            AccenturyProperties.TierAsset asset = entry.getValue();
            require(hasText(asset.comment()), code + "의 comment가 비어 있다");
            require(hasText(asset.shareText()), code + "의 share-text가 비어 있다");
            byCode.put(code, new Asset(asset.comment(), imageUrl(base, code), asset.shareText()));
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
    Asset assetFor(String tierCode) {
        Asset asset = byCode.get(tierCode);
        if (asset == null) {
            throw new IllegalStateException("자산 설정이 없는 등급 code다: " + tierCode);
        }
        return asset;
    }

    /** {@code {기준}/{code 소문자}.png} - 자산 정본의 파일명 규칙(assets/share/README)과 같다. */
    static String imageUrl(String baseUrl, String tierCode) {
        return baseUrl + "/" + tierCode.toLowerCase(Locale.ROOT) + IMAGE_EXTENSION;
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
