package app.accentury.backend.testdefinition;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import app.accentury.backend.scoring.ScorePolicyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
 * 발행된 테스트 정의의 저장소 (KAN-10, KAN-26).
 * <p>
 * <b>발행 입력은 DB다</b> (2026-08-09 확정, 명세서 §6). 정의는 마이그레이션의 INSERT로 들어오고
 * ({@code db/migration/V2__test_definition_publish.sql}), 활성 버전은 {@link ActiveTestVersion}
 * 한 행이 가리킨다. 이전의 classpath JSON seed 로드와 파일명 검증은 이 전환으로 폐기됐다 -
 * {@code testVersion} 중복은 이제 DB의 기본 키가 막는다.
 * <p>
 * 기동 시 전 행을 읽어 검증하므로 유효하지 않은 정의가 하나라도 있으면 서버가 뜨지 않는다
 * (발행 거부 - KAN-10 AC, KAN-26 AC, §6). 로드 후 이 맵은 불변이라 잠금 없이 읽는다 -
 * 발행이 마이그레이션으로만 일어나므로 프로세스가 사는 동안 정의가 늘지 않는다. 바뀌는 것은
 * 활성 버전 하나뿐이고, 그것만 {@code volatile} 참조로 둔다.
 * <p>
 * 미발행과 경북 콘텐츠는 여기 실리지 않으므로 외부에 나갈 수 없다 (KAN-10 요구).
 */
@Component
public class TestDefinitionRegistry {

    private static final Logger log = LoggerFactory.getLogger(TestDefinitionRegistry.class);

    /** MVP 대상 방언 - 경남 고정 (KAN-8 범위 제외). 경북 정의는 발행 불가 (§6) */
    static final String DIALECT_GYEONGNAM = "GYEONGNAM";

    /** 문항 구성 확정(2026-07-27): 음성 5 + 어휘 5 = 10문항 */
    static final int VOICE_COUNT = 5;
    static final int VOCABULARY_COUNT = 5;

    /** 어휘 문항은 4지선다다 (SRS 확정, KAN-13). */
    static final int CHOICE_COUNT = 4;

    /**
     * 문항/선택지 식별자의 최대 길이 - 제출 저장 컬럼({@code analysis_job.item_id},
     * {@code vocab_answer.item_id}/{@code choice_id}, 전부 varchar(40))과 같다.
     * 발행 검증이 막지 않으면 정의 조회와 검증은 통과하고 제출 시점의 INSERT가
     * 500으로 터진다 (Codex sol 리뷰 P2).
     */
    static final int MAX_ID_LENGTH = 40;

    private final Map<String, PublishedDefinition> published = new HashMap<>();

    /**
     * 활성 정의. 활성 전환({@link ActiveVersionService})이 커밋 뒤에 갈아 끼우므로 {@code volatile}이다 -
     * 참조 하나를 통째로 바꾸는 형태라, 세션 생성이 testVersion과 scoreVersion을 서로 다른
     * 세대에서 집어 가는 일이 없다.
     */
    private volatile PublishedDefinition active;

    /**
     * @param definition 정답 포함 원본 - 서버 내부용 (KAN-15 답안 저장, KAN-21 채점)
     * @param response   정답 제외 공개용 - 그대로 직렬화해 응답한다.
     * @param etag       응답 본문 SHA-256의 강한 ETag - 버전 경로가 불변이라 재검증은 항상 304다 (§3.2).
     */
    public record PublishedDefinition(TestDefinition definition, TestDefinitionResponse response, String etag) {
    }

    public TestDefinitionRegistry(ObjectMapper objectMapper,
                                  StoredTestDefinitionRepository definitions,
                                  ActiveTestVersionRepository activeVersions,
                                  ScorePolicyRegistry scorePolicies) {
        for (StoredTestDefinition stored : definitions.findAllByOrderByPublishedAtAscTestVersionAsc()) {
            TestDefinition definition = read(objectMapper, stored);
            validate(definition);
            requireMatchingColumns(stored, definition);
            // scoreVersion 참조 유효 검증 (§6, KAN-21) - 활성 버전만 보면 비활성 정의에 고정된
            // 세션이 완료 시점에 500을 맞는다. 발행되는 모든 정의를 본다 (Codex sol 리뷰 P2).
            require(scorePolicies.isPublished(definition.scoreVersion()),
                    "정의가 참조하는 scoreVersion(" + definition.scoreVersion()
                            + ")의 점수 정책 seed가 없다: " + definition.testVersion());

            // 응답은 seq 오름차순 고정 (KAN-10 AC - 순서 고정). 발행본의 배열 순서에 의존하지 않는다.
            List<TestDefinition.Item> ordered = definition.items().stream()
                    .sorted(Comparator.comparingInt(TestDefinition.Item::seq))
                    .toList();
            TestDefinition sorted = new TestDefinition(definition.testVersion(), definition.scoreVersion(),
                    definition.dialect(), definition.estimatedDurationSec(), ordered);

            TestDefinitionResponse response = TestDefinitionResponse.from(sorted);
            String etag = strongEtag(objectMapper.writeValueAsBytes(response));
            published.put(sorted.testVersion(), new PublishedDefinition(sorted, response, etag));
        }
        require(!published.isEmpty(), "발행된 테스트 정의가 하나도 없다 - 마이그레이션이 적용되지 않았다");

        String activeVersion = activeVersions.findById(ActiveTestVersion.CURRENT)
                .map(ActiveTestVersion::testVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "테스트 정의 발행 거부 - 활성 버전 행(active_test_version.CURRENT)이 없다"));
        // DB의 FK가 이미 막지만, 이 검사는 그 FK가 닿지 않는 경우(손으로 만든 스키마, 미래의
        // 제약 변경)까지 덮는다. 활성 버전이 없으면 세션을 만들 수 없으므로 기동 자체를 멈춘다.
        PublishedDefinition activeDefinition = published.get(activeVersion);
        require(activeDefinition != null, "활성 버전(" + activeVersion + ")의 정의가 발행되어 있지 않다");
        this.active = activeDefinition;

        log.info("테스트 정의 {}종 발행 완료: {} (활성: {}, 점수 버전: {})",
                published.size(), published.keySet(), activeVersion,
                activeDefinition.definition().scoreVersion());
    }

    /**
     * 지금 활성인 정의 - 새 세션이 고정할 버전이다 (§3.1, §5.4).
     * <p>
     * 세션 생성은 이 스냅샷 하나에서 {@code testVersion}과 {@code scoreVersion}을 함께 꺼내야
     * 한다. 두 번 나눠 읽으면 그 사이에 활성 전환이 끼어들어 한 세션이 두 세대에 걸칠 수 있다.
     */
    public PublishedDefinition active() {
        return active;
    }

    /** 발행된 정의를 찾는다. 미발행 버전은 404 - 존재 여부 외 어떤 정보도 주지 않는다 (KAN-10 요구). */
    public PublishedDefinition get(String testVersion) {
        PublishedDefinition found = published.get(testVersion);
        if (found == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return found;
    }

    /**
     * 세션 버전의 문항을 유형까지 검증해 찾는다 - 제출 API 공용 (KAN-23 업로드, KAN-15 답안).
     * 버전에 없는 문항은 422 ITEM_NOT_IN_VERSION, 유형이 다르면 409 ITEM_WRONG_TYPE (§3.3).
     */
    public TestDefinition.Item requireItem(String testVersion, String itemId,
                                           TestDefinition.ItemType expectedType) {
        TestDefinition.Item item = get(testVersion).definition().items().stream()
                .filter(candidate -> candidate.itemId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.ITEM_NOT_IN_VERSION));
        if (item.type() != expectedType) {
            throw new ApiException(ErrorCode.ITEM_WRONG_TYPE);
        }
        return item;
    }

    /**
     * 활성 버전을 갈아 끼운다 - {@link ActiveVersionService}가 <b>DB 트랜잭션 커밋 뒤에만</b> 부른다.
     * <p>
     * 커밋 전에 바꾸면 롤백된 전환이 메모리에만 남아 DB와 갈라지고, 그 뒤에 만들어진 세션이
     * 실제로는 활성이 아닌 버전을 고정한다. 진행 중 세션은 이 교체의 영향을 받지 않는다 -
     * 세션은 생성 시점의 버전을 자기 행에 들고 있다 (§5.4, KAN-26 AC).
     *
     * @throws ApiException 발행되지 않은 버전 (404) - 호출부가 먼저 검증하므로 도달하지 않는다.
     */
    void applyActivation(String testVersion) {
        this.active = get(testVersion);
    }

    /**
     * 발행본 JSON을 파싱한다. 실패는 어느 버전이 문제인지 알려주고 기동을 멈춘다.
     * <p>
     * {@link JacksonException}은 unchecked라 잡지 않으면 여기를 그냥 지나친다. Jackson 예외
     * 메시지는 소스를 가리므로({@code INCLUDE_SOURCE_IN_LOCATION} 기본 off) 버전 정보는 이
     * 래핑에만 남는다 (Claude 리뷰 P3).
     */
    private static TestDefinition read(ObjectMapper objectMapper, StoredTestDefinition stored) {
        try {
            return objectMapper.readValue(stored.body(), TestDefinition.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("테스트 정의 본문을 읽을 수 없다: " + stored.testVersion(), e);
        }
    }

    /**
     * 행의 사본 컬럼과 본문이 같은 말을 하는지 확인한다 (KAN-26).
     * <p>
     * {@code dialect}와 {@code score_version}은 본문 안에도 있는 값을 관리자 목록 조회(§6)가
     * 13KB 본문을 파싱하지 않고 답하려고 꺼내 둔 사본이고, {@code test_version}은 그 위에 기본
     * 키까지 겸한다. 마이그레이션을 손으로 쓰는 이상 둘이 어긋난 행은 언제든 들어올 수 있는데,
     * 그러면 목록은 A라고 답하고 정의 조회는 B를 주는 배포가 조용히 나간다. 파일명과 본문
     * 버전을 대조하던 검사(classpath seed 시절)가 DB로 옮겨 온 자리다.
     */
    private static void requireMatchingColumns(StoredTestDefinition stored, TestDefinition definition) {
        require(stored.testVersion().equals(definition.testVersion()),
                "행의 test_version(" + stored.testVersion()
                        + ")과 본문의 testVersion(" + definition.testVersion() + ")이 다르다");
        require(stored.dialect().equals(definition.dialect()),
                "행의 dialect(" + stored.dialect() + ")와 본문의 dialect(" + definition.dialect()
                        + ")가 다르다: " + stored.testVersion());
        require(stored.scoreVersion().equals(definition.scoreVersion()),
                "행의 score_version(" + stored.scoreVersion() + ")과 본문의 scoreVersion("
                        + definition.scoreVersion() + ")이 다르다: " + stored.testVersion());
    }

    /**
     * 발행 전 검증 (명세서 §6, KAN-10 AC, KAN-26). 실패는 {@link IllegalStateException} - 서버 기동 중단.
     * <p>
     * 발행 입력이 DB로 바뀌어도 검증은 그대로 남는다 (KAN-26 요구 - 이 검증을 그대로 가져온다).
     * DB 제약으로 표현할 수 있는 것은 {@code testVersion} 중복(기본 키)뿐이고, 문항 구성과
     * guideF0 밴드 길이 같은 규칙은 여전히 여기서만 걸린다.
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
            require(item.itemId().length() <= MAX_ID_LENGTH,
                    "itemId가 " + MAX_ID_LENGTH + "자를 넘는다: " + item.itemId());
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
                "문항 구성이 음성 " + VOICE_COUNT + ", 어휘 " + VOCABULARY_COUNT
                        + "이 아니다: 음성 " + voice + ", 어휘 " + vocabulary);
        for (int seq = 1; seq <= items.size(); seq++) {
            require(seqs.contains(seq), "seq는 1부터 연속이어야 한다: " + seq + " 누락");
        }
    }

    private static void validateVoice(TestDefinition.Item item) {
        require(item.choices() == null && item.correctChoiceId() == null,
                "VOICE 문항에 어휘 필드가 있다: " + item.itemId());

        TestDefinition.GuideF0 guideF0 = item.guideF0();
        require(guideF0 != null, "VOICE 문항에 guideF0가 없다 (KAN-10 AC - 누락 시 발행 거부): " + item.itemId());
        require(hasText(guideF0.unit()), "guideF0.unit이 비어 있다: " + item.itemId());
        require(guideF0.frameIntervalMs() > 0, "guideF0.frameIntervalMs는 양수여야 한다: " + item.itemId());
        require(guideF0.values() != null && !guideF0.values().isEmpty(),
                "guideF0.values가 비어 있다: " + item.itemId());
        // 허용 밴드는 required다 (2026-08-09 확정, §3.2, §6) - 없으면 발행 거부
        require(guideF0.bandLow() != null && guideF0.bandLow().size() == guideF0.values().size(),
                "guideF0.bandLow가 없거나 길이가 values와 다르다: " + item.itemId());
        require(guideF0.bandHigh() != null && guideF0.bandHigh().size() == guideF0.values().size(),
                "guideF0.bandHigh가 없거나 길이가 values와 다르다: " + item.itemId());
    }

    private static void validateVocabulary(TestDefinition.Item item) {
        require(item.guideF0() == null,
                "VOCABULARY 문항에 음성 필드가 있다: " + item.itemId());

        List<TestDefinition.Choice> choices = item.choices();
        require(choices != null && choices.size() == CHOICE_COUNT,
                "VOCABULARY 문항은 " + CHOICE_COUNT + "지선다여야 한다: " + item.itemId());
        Set<String> choiceIds = new HashSet<>();
        for (TestDefinition.Choice choice : choices) {
            require(hasText(choice.choiceId()), "choiceId가 비어 있다: " + item.itemId());
            require(choice.choiceId().length() <= MAX_ID_LENGTH,
                    "choiceId가 " + MAX_ID_LENGTH + "자를 넘는다: " + choice.choiceId());
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
