package app.accentury.backend.testdefinition;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 발행된 테스트 정의의 저장소 (KAN-10).
 * <p>
 * 프로토타입의 "발행"은 classpath seed({@code test-definitions/*.json}) 로드다 -
 * KAN-26이 "CMS 없이 JSON seed"를 명시했다. 관리자 발행 API·DB 이관·롤백은 KAN-26에서 한다.
 * 시작 시 전부 로드·검증하므로 유효하지 않은 정의가 있으면 서버가 뜨지 않는다
 * (발행 거부 - KAN-10 AC·명세서 §6). 로드 후에는 불변이라 잠금 없이 읽는다.
 * <p>
 * 미발행·경북 콘텐츠는 여기 실리지 않으므로 외부에 나갈 수 없다 (KAN-10 요구).
 */
@Component
public class TestDefinitionRegistry {

    private static final Logger log = LoggerFactory.getLogger(TestDefinitionRegistry.class);

    private static final String SEED_PATTERN = "classpath*:test-definitions/*.json";

    /** MVP 대상 방언 - 경남 고정 (KAN-8 범위 제외). 경북 정의는 발행 불가 (§6) */
    static final String DIALECT_GYEONGNAM = "GYEONGNAM";

    /** 문항 구성 확정(2026-07-27): 음성 5 + 어휘 5 = 10문항 */
    static final int VOICE_COUNT = 5;
    static final int VOCABULARY_COUNT = 5;

    /** 어휘 문항은 4지선다다 (SRS 확정, KAN-13) */
    static final int CHOICE_COUNT = 4;

    private final Map<String, PublishedDefinition> published = new HashMap<>();

    /**
     * @param definition 정답 포함 원본 - 서버 내부용 (KAN-15 답안 저장·KAN-21 채점)
     * @param response   정답 제외 공개용 - 그대로 직렬화해 응답한다
     * @param etag       응답 본문 SHA-256의 강한 ETag - 버전 경로가 불변이라 재검증은 항상 304다 (§3.2)
     */
    public record PublishedDefinition(TestDefinition definition, TestDefinitionResponse response, String etag) {
    }

    public TestDefinitionRegistry(ObjectMapper objectMapper, AccenturyProperties properties) {
        for (Resource seed : loadSeeds()) {
            TestDefinition definition = read(objectMapper, seed);
            validate(definition);
            requireMatchingFilename(seed, definition);

            // 응답은 seq 오름차순 고정 (KAN-10 AC - 순서 고정). seed 순서에 의존하지 않는다
            List<TestDefinition.Item> ordered = definition.items().stream()
                    .sorted(Comparator.comparingInt(TestDefinition.Item::seq))
                    .toList();
            TestDefinition sorted = new TestDefinition(definition.testVersion(), definition.scoreVersion(),
                    definition.dialect(), definition.estimatedDurationSec(), ordered);

            TestDefinitionResponse response = TestDefinitionResponse.from(sorted);
            String etag = strongEtag(objectMapper.writeValueAsBytes(response));
            PublishedDefinition duplicate =
                    published.put(sorted.testVersion(), new PublishedDefinition(sorted, response, etag));
            require(duplicate == null, "testVersion 중복 발행: " + sorted.testVersion());
        }

        require(published.containsKey(properties.testVersion()),
                "활성 버전(accentury.test-version=" + properties.testVersion() + ")의 정의 seed가 없다");
        log.info("테스트 정의 {}종 발행 완료: {} (활성: {})",
                published.size(), published.keySet(), properties.testVersion());
    }

    /** 발행된 정의를 찾는다. 미발행 버전은 404 - 존재 여부 외 어떤 정보도 주지 않는다 (KAN-10 요구) */
    public PublishedDefinition get(String testVersion) {
        PublishedDefinition found = published.get(testVersion);
        if (found == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return found;
    }

    private static Resource[] loadSeeds() {
        try {
            return new PathMatchingResourcePatternResolver().getResources(SEED_PATTERN);
        } catch (IOException e) {
            throw new IllegalStateException("테스트 정의 seed 스캔 실패: " + SEED_PATTERN, e);
        }
    }

    private static TestDefinition read(ObjectMapper objectMapper, Resource seed) {
        try {
            return objectMapper.readValue(seed.getInputStream(), TestDefinition.class);
        } catch (IOException e) {
            throw new IllegalStateException("테스트 정의 seed를 읽을 수 없다: " + seed.getDescription(), e);
        }
    }

    /** 파일명과 본문 버전이 어긋난 seed는 사고의 씨앗이라 발행을 거부한다 */
    private static void requireMatchingFilename(Resource seed, TestDefinition definition) {
        String filename = seed.getFilename();
        require(filename == null || filename.equals(definition.testVersion() + ".json"),
                "seed 파일명(" + filename + ")과 testVersion(" + definition.testVersion() + ")이 다르다");
    }

    /**
     * 발행 전 검증 (명세서 §6·KAN-10 AC). 실패는 {@link IllegalStateException} - 서버 기동 중단.
     * KAN-26이 관리자 발행 API를 만들면 이 검증을 그대로 가져간다.
     */
    static void validate(TestDefinition definition) {
        require(hasText(definition.testVersion()), "testVersion이 비어 있다");
        require(hasText(definition.scoreVersion()), "scoreVersion이 비어 있다");
        require(DIALECT_GYEONGNAM.equals(definition.dialect()),
                "MVP는 경남 정의만 발행할 수 있다 (§6): dialect=" + definition.dialect());
        require(definition.estimatedDurationSec() > 0, "estimatedDurationSec은 양수여야 한다");

        List<TestDefinition.Item> items = definition.items();
        require(items != null && items.size() == VOICE_COUNT + VOCABULARY_COUNT,
                "문항은 음성 " + VOICE_COUNT + " + 어휘 " + VOCABULARY_COUNT + " = 10개여야 한다");

        Set<String> itemIds = new HashSet<>();
        Set<Integer> seqs = new HashSet<>();
        int voice = 0;
        int vocabulary = 0;
        for (TestDefinition.Item item : items) {
            require(hasText(item.itemId()), "itemId가 비어 있다");
            require(itemIds.add(item.itemId()), "itemId 중복: " + item.itemId());
            require(seqs.add(item.seq()), "seq 중복: " + item.seq());
            require(hasText(item.prompt()), "prompt가 비어 있다: " + item.itemId());
            require(item.type() != null, "type이 없다: " + item.itemId());
            switch (item.type()) {
                case VOICE -> {
                    voice++;
                    validateVoice(item);
                }
                case VOCABULARY -> {
                    vocabulary++;
                    validateVocabulary(item);
                }
            }
        }
        require(voice == VOICE_COUNT && vocabulary == VOCABULARY_COUNT,
                "문항 구성이 음성 " + VOICE_COUNT + "·어휘 " + VOCABULARY_COUNT
                        + "이 아니다: 음성 " + voice + "·어휘 " + vocabulary);
        for (int seq = 1; seq <= items.size(); seq++) {
            require(seqs.contains(seq), "seq는 1부터 연속이어야 한다: " + seq + " 누락");
        }
    }

    private static void validateVoice(TestDefinition.Item item) {
        require(item.maxDurationMs() != null && item.maxDurationMs() > 0,
                "VOICE 문항에 maxDurationMs가 필요하다: " + item.itemId());
        require(item.choices() == null && item.correctChoiceId() == null,
                "VOICE 문항에 어휘 필드가 있다: " + item.itemId());

        TestDefinition.GuideF0 guideF0 = item.guideF0();
        require(guideF0 != null, "VOICE 문항에 guideF0가 없다 (KAN-10 AC - 누락 시 발행 거부): " + item.itemId());
        require(hasText(guideF0.unit()), "guideF0.unit이 비어 있다: " + item.itemId());
        require(guideF0.frameIntervalMs() > 0, "guideF0.frameIntervalMs는 양수여야 한다: " + item.itemId());
        require(guideF0.values() != null && !guideF0.values().isEmpty(),
                "guideF0.values가 비어 있다: " + item.itemId());
        require(guideF0.bandLow() == null || guideF0.bandLow().size() == guideF0.values().size(),
                "guideF0.bandLow 길이가 values와 다르다: " + item.itemId());
        require(guideF0.bandHigh() == null || guideF0.bandHigh().size() == guideF0.values().size(),
                "guideF0.bandHigh 길이가 values와 다르다: " + item.itemId());
    }

    private static void validateVocabulary(TestDefinition.Item item) {
        require(item.maxDurationMs() == null && item.guideF0() == null,
                "VOCABULARY 문항에 음성 필드가 있다: " + item.itemId());

        List<TestDefinition.Choice> choices = item.choices();
        require(choices != null && choices.size() == CHOICE_COUNT,
                "VOCABULARY 문항은 " + CHOICE_COUNT + "지선다여야 한다: " + item.itemId());
        Set<String> choiceIds = new HashSet<>();
        for (TestDefinition.Choice choice : choices) {
            require(hasText(choice.choiceId()), "choiceId가 비어 있다: " + item.itemId());
            require(choiceIds.add(choice.choiceId()), "choiceId 중복: " + choice.choiceId());
            require(hasText(choice.text()), "선택지 문구가 비어 있다: " + choice.choiceId());
        }
        require(item.correctChoiceId() != null && choiceIds.contains(item.correctChoiceId()),
                "정답이 선택지에 없다 (§6 - 발행 거부): " + item.itemId());
    }

    private static String strongEtag(byte[] body) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return "\"" + HexFormat.of().formatHex(sha256.digest(body)) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 지원하지 않는 JVM", e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException("테스트 정의 발행 거부 - " + message);
        }
    }
}
