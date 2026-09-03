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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 발행된 테스트 정의의 저장소 (KAN-10, KAN-26, KAN-182).
 * <p>
 * <b>발행 입력은 DB다</b> (2026-08-09 확정, 명세서 §6). 정의는 마이그레이션의 INSERT로 들어오고
 * ({@code db/migration/V2__test_definition_publish.sql}), 활성 버전은 {@link ActiveTestVersion}
 * 한 행이 가리킨다. 이전의 classpath JSON seed 로드와 파일명 검증은 이 전환으로 폐기됐다 -
 * {@code testVersion} 중복은 이제 DB의 기본 키가 막는다.
 * <p>
 * 기동 시 전 행을 읽어 검증하므로 유효하지 않은 정의가 하나라도 있으면 서버가 뜨지 않는다
 * (발행 거부 - KAN-10 AC, KAN-26 AC, §6). 로드 후 이 맵은 불변이라 잠금 없이 읽는다 -
 * 발행이 마이그레이션으로만 일어나므로 프로세스가 사는 동안 정의가 늘지 않는다.
 * <p>
 * <b>발행본은 풀이고 세션은 세트를 본다</b> (KAN-182). 발행본 하나는 음성 문장 풀(N >= 5)과
 * 어휘 5문항이고, 기동 시 {@link VoiceSets} 규칙으로 세트(음성 5 + 어휘 5)를 전부 유도해
 * 세트별 공개 응답과 ETag를 미리 만들어 둔다. 활성 전환의 비용은 그대로 포인터 읽기뿐이다.
 * 세션이 유효 문항으로 삼는 것은 자기 세트의 10문항이고, 제출 검증과 상태 조회, 완주 판정,
 * 집계는 전부 {@link #sessionDefinition}이 주는 그 목록만 쓴다 - 열어 두면 한 세션에 음성
 * 점수가 5개 넘게 쌓여 집계가 깨진다.
 * <p>
 * <b>활성 버전은 메모리에 들고 있지 않는다</b> (KAN-167). KAN-26은 활성 정의를 {@code volatile}
 * 참조로 두고 전환 서비스가 커밋 뒤에 갈아 끼웠는데, backend가 Fargate 태스크 여러 개로 돌면
 * (KAN-165, KAN-168) 관리자 호출을 받은 태스크만 바뀌고 나머지는 재기동까지 옛 버전으로 세션을
 * 만든다. 그래서 {@link #active()}는 부를 때마다 {@code active_test_version.CURRENT} 행을 읽어
 * 정본을 DB 하나로 둔다. 캐시를 두지 않는 이유는 그 메서드에 적었다.
 * <p>
 * 미발행과 경북 콘텐츠는 여기 실리지 않으므로 외부에 나갈 수 없다 (KAN-10 요구).
 */
@Component
public class TestDefinitionRegistry {

    private static final Logger log = LoggerFactory.getLogger(TestDefinitionRegistry.class);

    /** MVP 대상 방언 - 경남 고정 (KAN-8 범위 제외). 경북 정의는 발행 불가 (§6) */
    static final String DIALECT_GYEONGNAM = "GYEONGNAM";

    /**
     * 문항 구성 확정(2026-07-27): 세션이 응시하는 것은 음성 5 + 어휘 5 = 10문항이다. 발행본의
     * 음성은 풀이라 5개 이상이면 되고(KAN-182), 5개는 세트 하나의 크기이자 풀의 최소 크기다.
     */
    static final int VOICE_SET_SIZE = VoiceSets.SET_SIZE;
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

    /** 활성 버전 포인터의 정본 - {@link #active()}가 부를 때마다 읽는다 (KAN-167). */
    private final ActiveTestVersionRepository activeVersions;

    /**
     * 발행본 하나 - 풀 정의와 거기서 유도한 세트 전부.
     *
     * @param definition 정답 포함 풀 원본 (seq 오름차순, VOICE N + VOCABULARY 5) - 서버 내부용.
     *                   버전 단위 속성(testVersion, scoreVersion, dialect)을 읽는 자리다. 세션이
     *                   보는 문항 목록은 아니다 - 그것은 {@link #voiceSet(int)}의 세트 정의다.
     * @param voiceSets  세트 번호 순(1..세트 수)의 세트 - 공개 응답과 ETag를 미리 만들어 둔다.
     */
    public record PublishedDefinition(TestDefinition definition, List<VoiceSet> voiceSets) {

        /** 음성 문장 풀 크기 N */
        public int voicePoolSize() {
            return (int) definition.items().stream()
                    .filter(item -> item.type() == TestDefinition.ItemType.VOICE)
                    .count();
        }

        public int voiceSetCount() {
            return voiceSets.size();
        }

        /**
         * 세트 n. 세트 수를 넘으면 404 {@code RESOURCE_NOT_FOUND} - 없는 버전과 같은 취급이다
         * (§3.2). 1 미만은 형식 오류(400)라 호출부가 먼저 거른다.
         */
        public VoiceSet voiceSet(int number) {
            if (number < 1 || number > voiceSets.size()) {
                throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            return voiceSets.get(number - 1);
        }
    }

    /**
     * 세트 하나 - 세션이 실제로 응시하는 10문항.
     *
     * @param number     세트 번호 (1부터)
     * @param definition 정답 포함 세트 정의 (VOICE 5 + VOCABULARY 5, seq 1..10 교차) - KAN-15 답안
     *                   저장, KAN-21 채점의 정본
     * @param response   정답 제외 공개용 - 그대로 직렬화해 응답한다.
     * @param etag       응답 본문 SHA-256의 강한 ETag - 버전과 세트가 URL에 들어가 불변이라
     *                   재검증은 항상 304다 (§3.2). 세트마다 본문이 다르니 ETag도 다르다.
     */
    public record VoiceSet(int number, TestDefinition definition, TestDefinitionResponse response,
                           String etag) {
    }

    public TestDefinitionRegistry(ObjectMapper objectMapper,
                                  StoredTestDefinitionRepository definitions,
                                  ActiveTestVersionRepository activeVersions,
                                  ScorePolicyRegistry scorePolicies) {
        this.activeVersions = activeVersions;
        for (StoredTestDefinition stored : definitions.findAllByOrderByPublishedAtAscTestVersionAsc()) {
            TestDefinition definition = read(objectMapper, stored);
            validate(definition);
            requireMatchingColumns(stored, definition);
            // scoreVersion 참조 유효 검증 (§6, KAN-21) - 활성 버전만 보면 비활성 정의에 고정된
            // 세션이 완료 시점에 500을 맞는다. 발행되는 모든 정의를 본다 (Codex sol 리뷰 P2).
            require(scorePolicies.isPublished(definition.scoreVersion()),
                    "정의가 참조하는 scoreVersion(" + definition.scoreVersion()
                            + ")의 점수 정책 seed가 없다: " + definition.testVersion());

            // 풀 순서는 seq 오름차순 고정 (KAN-10 AC - 순서 고정). 발행본의 배열 순서에 의존하지 않는다.
            List<TestDefinition.Item> ordered = definition.items().stream()
                    .sorted(Comparator.comparingInt(TestDefinition.Item::seq))
                    .toList();
            TestDefinition pool = new TestDefinition(definition.testVersion(), definition.scoreVersion(),
                    definition.dialect(), definition.estimatedDurationSec(), ordered);

            // 세트는 발행본에서 유도한다 (KAN-182) - 발행본에 손으로 나열하지 않는다.
            List<TestDefinition> setDefinitions = VoiceSets.derive(pool);
            List<VoiceSet> voiceSets = new ArrayList<>(setDefinitions.size());
            for (int number = 1; number <= setDefinitions.size(); number++) {
                TestDefinition setDefinition = setDefinitions.get(number - 1);
                TestDefinitionResponse response =
                        TestDefinitionResponse.from(setDefinition, number, setDefinitions.size());
                String etag = strongEtag(objectMapper.writeValueAsBytes(response));
                voiceSets.add(new VoiceSet(number, setDefinition, response, etag));
            }
            published.put(pool.testVersion(), new PublishedDefinition(pool, List.copyOf(voiceSets)));
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

        log.info("테스트 정의 {}종 발행 완료: {} (활성: {}, 점수 버전: {}, 음성 풀 {}문항 = 세트 {}개)",
                published.size(), published.keySet(), activeVersion,
                activeDefinition.definition().scoreVersion(),
                activeDefinition.voicePoolSize(), activeDefinition.voiceSetCount());
    }

    /**
     * 지금 활성인 정의 - 새 세션이 고정할 버전이다 (§3.1, §5.4).
     * <p>
     * <b>부를 때마다 DB의 포인터 행을 읽는다</b> (KAN-167). 다중 인스턴스에서 한 태스크가 전환한
     * 활성 버전을 다른 태스크의 새 세션이 그 즉시 고정해야 하기 때문이다. 캐시를 두지 않는 것은
     * 소비처가 세션 생성 하나뿐이고 그 경로는 이미 세션 INSERT를 하는 쓰기 경로라, 기본 키 1행
     * 조회를 더 얹어도 비용이 드러나지 않아서다 - 폴링마다 불리는 혼잡 판정
     * ({@code AnalysisCongestion})과 달리 캐시가 줄여 줄 부하가 없고, 전파 지연 0이 가장 단순하다.
     * <p>
     * 세션 생성은 이 스냅샷 하나에서 {@code testVersion}과 {@code scoreVersion}, 세트 수를 함께
     * 꺼내야 한다 (KAN-182). 두 번 나눠 읽으면 그 사이에 활성 전환이 끼어들어 한 세션이 두 세대에
     * 걸칠 수 있다 - 예컨대 A의 세트 수로 검증한 세트 번호가 B에는 없어 세션이 조회 불가 세트에
     * 고정된다. 포인터 행 읽기와 정의 조회 사이에 전환이 끼어도 문제없다 - 정의 맵은 불변이고
     * 포인터가 가리키는 버전의 정의를 통째로 돌려주므로, 어느 쪽이든 한 세대의 짝이다.
     *
     * @throws IllegalStateException 포인터 행이 없거나, 가리키는 버전이 이 프로세스에 발행되어
     *                               있지 않을 때. 후자는 새 정의의 마이그레이션이 적용된 뒤 아직
     *                               재기동하지 않은 옛 태스크가 그 버전의 활성 전환을 만난 경우다 -
     *                               2단계 롤아웃(새 정의 배포 완료 후 전환, {@link ActiveVersionService})을
     *                               지키면 도달하지 않는다.
     */
    public PublishedDefinition active() {
        String activeVersion = activeVersions.findById(ActiveTestVersion.CURRENT)
                .map(ActiveTestVersion::testVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "활성 버전 행(active_test_version.CURRENT)이 사라졌다"));
        PublishedDefinition active = published.get(activeVersion);
        if (active == null) {
            throw new IllegalStateException("활성 버전(" + activeVersion
                    + ")의 정의가 이 프로세스에 발행되어 있지 않다 - 새 정의를 실은 배포가 끝나기 전에 전환됐다");
        }
        return active;
    }

    /** 발행된 정의를 찾는다. 미발행 버전은 404 - 존재 여부 외 어떤 정보도 주지 않는다 (KAN-10 요구). */
    public PublishedDefinition get(String testVersion) {
        return find(testVersion).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 이 프로세스에 발행돼 있으면 그 정의, 없으면 빈 값 - 없는 것을 404로 바꾸지 않고 그대로
     * 답해야 하는 호출부의 자리다 (관리자 목록 조회, §6.2).
     * <p>
     * <b>DB에 있어도 이 맵에 없는 버전이 있다.</b> 롤링 배포 중 새 태스크가 정의 마이그레이션을
     * 적용하고 옛 태스크는 아직 재기동하지 않은 창이 그렇다 ({@link #active()}의 같은 창).
     * 그 창에서 DB 행마다 {@link #get}을 부르면 새로 들어온 행 하나 때문에 목록 전체가 404가
     * 되는데, 하필 운영자가 무엇이 발행됐는지 보고 전환과 롤백을 판단하는 순간이다.
     */
    public Optional<PublishedDefinition> find(String testVersion) {
        return Optional.ofNullable(published.get(testVersion));
    }

    /**
     * 세션의 유효 문항 = 어휘 5 + 자기 세트의 음성 5 (KAN-182, §5.4).
     * <p>
     * 세션이 고정한 {@code testVersion}과 {@code voiceSet}으로 세트 정의를 돌려주는 <b>하나뿐인</b>
     * 진입점이다. 제출 검증({@link #requireItem}), 상태 일괄 조회, 진행도, 완주 판정, 집계가 전부
     * 이것만 쓴다 - 한 곳이라도 풀 정의를 보면 세트 밖 문항이 그 경로로 새어 들어온다.
     * 세션의 세트 번호는 생성 시점에 활성 정의의 세트 수 안에서 검증되므로 여기서 404가 나는 것은
     * 데이터 오염이다.
     */
    public TestDefinition sessionDefinition(String testVersion, int voiceSet) {
        return get(testVersion).voiceSet(voiceSet).definition();
    }

    /**
     * 세션 세트의 문항을 유형까지 검증해 찾는다 - 제출 API 공용 (KAN-23 업로드, KAN-15 답안).
     * 세트에 없는 문항은 <b>풀에 있어도</b> 422 ITEM_NOT_IN_VERSION, 유형이 다르면 409
     * ITEM_WRONG_TYPE (§3.3). 풀 기준으로 열어 두면 한 세션에 음성 점수가 5개 넘게 쌓여 집계가
     * 깨진다 (KAN-182).
     */
    public TestDefinition.Item requireItem(String testVersion, int voiceSet, String itemId,
                                           TestDefinition.ItemType expectedType) {
        TestDefinition.Item item = sessionDefinition(testVersion, voiceSet).items().stream()
                .filter(candidate -> candidate.itemId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.ITEM_NOT_IN_VERSION));
        if (item.type() != expectedType) {
            throw new ApiException(ErrorCode.ITEM_WRONG_TYPE);
        }
        return item;
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
     * 발행 전 검증 (명세서 §6, KAN-10 AC, KAN-26, KAN-182). 실패는 {@link IllegalStateException} -
     * 서버 기동 중단.
     * <p>
     * 발행 입력이 DB로 바뀌어도 검증은 그대로 남는다 (KAN-26 요구 - 이 검증을 그대로 가져온다).
     * DB 제약으로 표현할 수 있는 것은 {@code testVersion} 중복(기본 키)뿐이고, 문항 구성과
     * guideF0 밴드 길이 같은 규칙은 여전히 여기서만 걸린다.
     * <p>
     * 문항 구성은 "음성 N (N >= 5) + 어휘 5"다 (KAN-182 - 풀 다중화로 완화). seq는 풀 기준
     * 1..N+5 연속이어야 한다. {@code scriptKey}는 정의 단위 all-or-nothing이고 풀 안에서 중복을
     * 거부한다. 기존 더미 정의 {@code gn-2026.08.1}(scriptKey 없음, 음성 5)은 그대로 통과한다 -
     * 발행 후 불변(§5.4)을 지키려면 새 검증이 기존 행을 깨뜨리면 안 된다.
     */
    static void validate(TestDefinition definition) {
        require(hasText(definition.testVersion()), "testVersion이 비어 있다");
        require(hasText(definition.scoreVersion()), "scoreVersion이 비어 있다");
        require(DIALECT_GYEONGNAM.equals(definition.dialect()),
                "MVP는 경남 정의만 발행할 수 있다 (§6): dialect=" + definition.dialect());
        require(definition.estimatedDurationSec() > 0, "estimatedDurationSec은 양수여야 한다");

        List<TestDefinition.Item> items = definition.items();
        require(items != null && items.size() >= VOICE_SET_SIZE + VOCABULARY_COUNT,
                "문항은 음성 " + VOICE_SET_SIZE + "개 이상 + 어휘 " + VOCABULARY_COUNT + "개여야 한다");

        Set<String> itemIds = new HashSet<>();
        Set<Integer> seqs = new HashSet<>();
        Set<String> scriptKeys = new HashSet<>();
        int voice = 0;
        int voiceWithScriptKey = 0;
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
                    if (item.scriptKey() != null) {
                        voiceWithScriptKey++;
                        require(hasText(item.scriptKey()), "scriptKey가 비어 있다: " + item.itemId());
                        require(scriptKeys.add(item.scriptKey()),
                                "scriptKey 중복 (풀 안에서 유일해야 한다): " + item.scriptKey());
                    }
                }
                case VOCABULARY -> {
                    vocabulary++;
                    validateVocabulary(item);
                }
            }
        }
        require(voice >= VOICE_SET_SIZE && vocabulary == VOCABULARY_COUNT,
                "문항 구성이 음성 " + VOICE_SET_SIZE + " 이상, 어휘 " + VOCABULARY_COUNT
                        + "이 아니다: 음성 " + voice + ", 어휘 " + vocabulary);
        // all-or-nothing (KAN-182) - 일부만 있으면 실모델이 나머지 문항의 문장을 못 찾는다.
        require(voiceWithScriptKey == 0 || voiceWithScriptKey == voice,
                "scriptKey는 전 음성 문항에 있거나 전부 없어야 한다: 음성 " + voice
                        + "개 중 " + voiceWithScriptKey + "개에만 있다");
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
        require(item.guideF0() == null && item.scriptKey() == null,
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
