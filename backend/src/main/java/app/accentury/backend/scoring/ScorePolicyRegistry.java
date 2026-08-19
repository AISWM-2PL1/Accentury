package app.accentury.backend.scoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 발행된 점수 버전 정책의 저장소 (KAN-21).
 * <p>
 * 점수 정책의 "발행"은 classpath seed({@code score-versions/*.json}) 로드다. 시작 시 전부
 * 로드하고 검증하므로 유효하지 않은 정책이 있으면 서버가 뜨지 않는다. 로드 후에는 불변이라
 * 잠금 없이 읽는다.
 * <p>
 * <b>테스트 정의와 달리 DB로 옮기지 않았다</b> (KAN-26 범위). 옮길 이유였던 것 - 재배포 없는
 * 전환과 롤백 - 이 여기에는 없기 때문이다. 활성 점수 버전은 따로 지정하는 값이 아니라 활성
 * 테스트 정의가 선언한 값을 따르므로(§5.4), 점수 버전을 바꾸는 일은 곧 새 정의를 발행하는
 * 일이다. 점수 정책 자체의 발행과 롤백이 필요해지면 그때 같은 형태로 옮긴다.
 * <p>
 * 어떤 정의든 자기가 참조하는 정책 seed가 있어야 한다는 검사는
 * {@link app.accentury.backend.testdefinition.TestDefinitionRegistry}가 기동 시 전 정의에 대해
 * 한다 ({@link #isPublished}) - 그래서 "채점할 수 없는 세션은 애초에 만들어지지 않는다".
 */
@Component
public class ScorePolicyRegistry {

    private static final Logger log = LoggerFactory.getLogger(ScorePolicyRegistry.class);

    private static final String SEED_PATTERN = "classpath*:score-versions/*.json";

    /**
     * 등급 code의 확정 목록 - rank 순서 그대로다 (2026-07-27 확정). code는 §3.7 tier.code
     * 클라이언트 계약이고 결과 화면과 공유 이미지(KAN-29, 30)가 code별 고정 자산을 가지므로,
     * 오타나 순서 바뀜은 발행 시점에 거부해야 한다 (Codex sol 리뷰 P2). 표시 이름과 경계는
     * seed가 정본이지만 code 집합 변경은 클라이언트 배포가 함께 필요한 별개 결정이다.
     * 등급별 자산 설정 검증(TierAssets, KAN-25)도 이 목록이 기준이다.
     */
    public static final List<String> TIER_CODES = List.of("OUTSIDER", "TRAVELER", "WANNABE", "HONORARY", "NATIVE");

    /**
     * 가중치 상한 - 가중치는 비율값(sv-0.3: 2:1)이라 이 범위면 충분하다. 집계 산술은
     * long이라 오버플로하지 않지만(ScoreAggregator), 자릿수 오타 같은 비정상 비율은
     * 발행 시점에 거른다 (Codex sol 리뷰 P2).
     */
    static final int MAX_WEIGHT = 100;

    private final Map<String, ScorePolicy> published = new HashMap<>();

    public ScorePolicyRegistry(ObjectMapper objectMapper) {
        for (Resource seed : loadSeeds()) {
            ScorePolicy policy = read(objectMapper, seed);
            validate(policy);
            requireMatchingFilename(seed, policy);
            ScorePolicy duplicate = published.put(policy.scoreVersion(), policy);
            require(duplicate == null, "scoreVersion 중복 발행: " + policy.scoreVersion());
        }

        require(!published.isEmpty(), "점수 정책 seed가 하나도 없다");
        log.info("점수 정책 {}종 발행 완료: {}", published.size(), published.keySet());
    }

    /**
     * 발행 여부 - 테스트 정의 발행 검증(§6 "scoreVersion 참조 유효",
     * {@link app.accentury.backend.testdefinition.TestDefinitionRegistry})이 기동 시 전 정의에
     * 대해 조회한다. 참조가 끊긴 정의가 하나라도 있으면 서버가 뜨지 않는다.
     */
    public boolean isPublished(String scoreVersion) {
        return published.containsKey(scoreVersion);
    }

    /**
     * 점수 버전의 정책을 찾는다. 세션이 생성 시점에 고정한 scoreVersion(§5.4)으로만
     * 조회되는 내부 경로라, 없으면 클라이언트 오류가 아니라 배포 사고다 - 500으로 낸다.
     */
    public ScorePolicy get(String scoreVersion) {
        ScorePolicy found = published.get(scoreVersion);
        if (found == null) {
            throw new IllegalStateException("발행되지 않은 점수 버전이다: " + scoreVersion);
        }
        return found;
    }

    private static Resource[] loadSeeds() {
        try {
            return new PathMatchingResourcePatternResolver().getResources(SEED_PATTERN);
        } catch (IOException e) {
            throw new IllegalStateException("점수 정책 seed 스캔 실패: " + SEED_PATTERN, e);
        }
    }

    /**
     * seed 하나를 읽는다. {@link JacksonException}은 unchecked라 함께 잡지 않으면
     * 파일 정보 없이 지나친다 - TestDefinitionRegistry와 같은 래핑이다.
     */
    private static ScorePolicy read(ObjectMapper objectMapper, Resource seed) {
        try {
            return objectMapper.readValue(seed.getInputStream(), ScorePolicy.class);
        } catch (JacksonException | IOException e) {
            throw new IllegalStateException("점수 정책 seed를 읽을 수 없다: " + seed.getDescription(), e);
        }
    }

    /** 파일명과 본문 버전이 어긋난 seed는 사고의 씨앗이라 발행을 거부한다. */
    private static void requireMatchingFilename(Resource seed, ScorePolicy policy) {
        String filename = seed.getFilename();
        require(filename == null || filename.equals(policy.scoreVersion() + ".json"),
                "seed 파일명(" + filename + ")과 scoreVersion(" + policy.scoreVersion() + ")이 다르다");
    }

    /**
     * 발행 전 검증. 실패는 {@link IllegalStateException} - 서버 기동 중단.
     * 등급 표가 0~100 전 구간을 빈틈없이 덮고 경계가 단조 증가함을 강제한다 -
     * 이 검증이 통과한 정책에서는 어떤 종합 점수든 등급이 결정적으로 나온다 (KAN-21 AC).
     */
    static void validate(ScorePolicy policy) {
        require(hasText(policy.scoreVersion()), "scoreVersion이 비어 있다");
        // 가중치 0은 한 축을 무단 폐기하는 것이라 새 점수 버전 논의 없이는 실수다 (§4.3 - 2:1 확정).
        require(policy.intonationWeight() > 0 && policy.intonationWeight() <= MAX_WEIGHT,
                "intonationWeight는 1~" + MAX_WEIGHT + "이어야 한다: " + policy.intonationWeight());
        require(policy.vocabularyWeight() > 0 && policy.vocabularyWeight() <= MAX_WEIGHT,
                "vocabularyWeight는 1~" + MAX_WEIGHT + "이어야 한다: " + policy.vocabularyWeight());

        List<ScorePolicy.Tier> tiers = policy.tiers();
        require(tiers.size() == TIER_CODES.size(),
                "등급은 " + TIER_CODES.size() + "개여야 한다 (2026-07-27 확정)");

        for (int i = 0; i < tiers.size(); i++) {
            ScorePolicy.Tier tier = tiers.get(i);
            // code는 클라이언트 계약이다 - 목록 밖 code와 순서 바뀜은 발행 거부 (Codex sol 리뷰 P2)
            require(TIER_CODES.get(i).equals(tier.code()),
                    "rank " + (i + 1) + "의 등급 code는 " + TIER_CODES.get(i) + "여야 한다: " + tier.code());
            require(hasText(tier.name()), "등급 name이 비어 있다: " + tier.code());
            require(tier.rank() == i + 1,
                    "rank는 1부터 연속 오름차순이어야 한다: " + tier.code() + "의 rank가 " + tier.rank());
            if (i == 0) {
                // 첫 등급이 0을 덮지 않으면 최저점의 등급이 없다 - 판정 불능 구간 금지
                require(tier.minScore() == 0, "첫 등급의 minScore는 0이어야 한다: " + tier.minScore());
            } else {
                require(tier.minScore() > tiers.get(i - 1).minScore(),
                        "minScore는 순증가여야 한다: " + tier.code());
            }
            require(tier.minScore() <= 100, "minScore가 점수 범위(0~100)를 벗어난다: " + tier.code());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException("점수 정책 발행 거부 - " + message);
        }
    }
}
