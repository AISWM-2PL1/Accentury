package app.accentury.backend.testdefinition;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * {@code GET /v0/tests/{testVersion}} 응답 (API 명세서 §3.2).
 * <p>
 * {@link TestDefinition}에서 정답({@code correctChoiceId})을 뺀 공개용 사본이다.
 * 응답 전용 타입을 따로 두는 이유: 도메인에 필드가 추가돼도 여기 명시적으로 옮기지
 * 않는 한 클라이언트로 새지 않는다 (KAN-10 AC - 응답에 정답 정보 미포함).
 * 기준 음성 관련 필드는 아예 없다 (범위 제외 - 2026-07-31, KAN-12).
 */
public record TestDefinitionResponse(
        String testVersion,
        String scoreVersion,
        String dialect,
        int estimatedDurationSec,
        List<Item> items) {

    static TestDefinitionResponse from(TestDefinition definition) {
        return new TestDefinitionResponse(
                definition.testVersion(),
                definition.scoreVersion(),
                definition.dialect(),
                definition.estimatedDurationSec(),
                definition.items().stream().map(Item::from).toList());
    }

    /** 유형별 미소유 필드(VOICE의 choices, VOCABULARY의 maxDurationMs와 guideF0)는 직렬화에서 빠진다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String itemId,
            int seq,
            TestDefinition.ItemType type,
            String prompt,
            @Nullable Integer maxDurationMs,
            TestDefinition.@Nullable GuideF0 guideF0,
            @Nullable List<TestDefinition.Choice> choices) {

        static Item from(TestDefinition.Item item) {
            // 정의에는 없는 필드다 - 전 문항 공통 상수를 응답에서 채운다. 클라이언트 계약(§3.2)은
            // 문항별 값을 유지하므로, 나중에 문항별로 열더라도 응답 형태는 바뀌지 않는다.
            Integer maxDurationMs = item.type() == TestDefinition.ItemType.VOICE
                    ? TestDefinition.VOICE_MAX_DURATION_MS
                    : null;
            return new Item(item.itemId(), item.seq(), item.type(), item.prompt(),
                    maxDurationMs, item.guideF0(), item.choices());
        }
    }
}
