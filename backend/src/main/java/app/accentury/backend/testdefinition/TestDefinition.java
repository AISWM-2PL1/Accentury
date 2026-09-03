package app.accentury.backend.testdefinition;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 테스트 정의 원본 - 발행본 JSON({@code test_definition.body})과 1:1 (KAN-10, KAN-26).
 * <p>
 * 어휘 문항의 {@code correctChoiceId}(정답)까지 들고 있는 서버 내부 모델이다.
 * 클라이언트 응답은 정답을 뺀 {@link TestDefinitionResponse}로 변환해서 나간다 -
 * 이 타입을 직접 직렬화해 응답하면 안 된다 (KAN-13 정오 미노출).
 * <p>
 * 발행본 하나는 <b>음성 문장 풀</b>(N개, N >= 5)과 어휘 5문항을 담는다 (KAN-182). 세션이
 * 실제로 응시하는 것은 풀에서 유도한 <b>세트</b>(음성 5 + 어휘 5 = 10문항) 하나이고, 세트는
 * 발행본에 손으로 나열하지 않고 {@link VoiceSets}가 규칙으로 유도한다. 그래서 같은 타입이
 * 두 뜻으로 쓰인다 - 발행본 전체(풀 정의, seq 1..N+5)와 세션이 보는 세트 정의(seq 1..10).
 * 어느 쪽인지는 그 값을 준 {@link TestDefinitionRegistry} 메서드가 말한다.
 *
 * @param testVersion          정의 버전 (예: gn-2026.08.1) - 발행 후 불변 (§5.4)
 * @param scoreVersion         이 정의를 채점할 점수 버전 (sv-0.3, KAN-21)
 * @param dialect              대상 방언 - MVP는 GYEONGNAM 고정 (KAN-8 범위 제외)
 * @param estimatedDurationSec 예상 소요 시간 (§3.2)
 * @param items                풀 정의는 VOICE N + VOCABULARY 5, 세트 정의는 VOICE 5 + VOCABULARY 5.
 *                             어느 쪽이든 seq 순서 고정.
 */
public record TestDefinition(
        String testVersion,
        String scoreVersion,
        String dialect,
        int estimatedDurationSec,
        List<Item> items) {

    /**
     * VOICE 문항의 최대 녹음 길이 (ms) - 전 문항 공통 고정값이다 (§3.2, §3.3, KAN-23).
     * <p>
     * 문항별 설정으로 두지 않는다: 앱의 녹음 자동 종료({@code RecordingEngine})가 같은 값을
     * 상수로 갖고 있어, 문항마다 달라지려면 서버만이 아니라 앱 배포가 함께 필요하다.
     * 문항별 길이를 실제로 지원하게 되면 이 상수를 다시 문항 필드로 되돌린다.
     */
    public static final int VOICE_MAX_DURATION_MS = 10_000;

    public enum ItemType { VOICE, VOCABULARY }

    /**
     * 문항 하나. 유형별 필드 소유가 다르다 - VOICE는 {@code scriptKey}와 {@code guideF0},
     * VOCABULARY는 {@code choices}와 {@code correctChoiceId}. 소유 규칙은 발행 검증이 강제한다.
     * 녹음 길이 상한은 문항이 아니라 {@link #VOICE_MAX_DURATION_MS}가 정본이다.
     * <p>
     * 세트 정의의 문항은 풀 정의의 문항 그대로다 (같은 itemId, scriptKey, guideF0) - 세트를
     * 만들 때 바뀌는 것은 {@code seq}뿐이다 ({@link VoiceSets}).
     *
     * @param scriptKey VOICE 전용. 실모델(KAN-159 전달본)이 문장을 찾는 참조 키 문자열이다
     *                  ("1|5" 형식). 값의 의미와 형식은 AI 전달본이 정본이고 BE는 문자열로만
     *                  다뤄 AI로 가는 meta(§4.1)에 그대로 싣는다 (KAN-182, KAN-22에서 이관).
     *                  정의 단위로 전부 있거나 전부 없어야 하며(all-or-nothing), 없으면 meta에서
     *                  생략된다 - 실모델로 채점할 수 없는 더미 정의({@code gn-2026.08.1})가 그렇다.
     */
    public record Item(
            String itemId,
            int seq,
            ItemType type,
            String prompt,
            @Nullable String scriptKey,
            @Nullable GuideF0 guideF0,
            @Nullable List<Choice> choices,
            @Nullable String correctChoiceId) {

        /** scriptKey 없는 문항 - 더미 정의와 픽스처가 쓴다. 발행본 JSON은 정식 생성자로 파싱된다. */
        public Item(String itemId, int seq, ItemType type, String prompt,
                    @Nullable GuideF0 guideF0, @Nullable List<Choice> choices,
                    @Nullable String correctChoiceId) {
            this(itemId, seq, type, prompt, null, guideF0, choices, correctChoiceId);
        }

        /** 같은 문항에 seq만 새로 매긴 사본 - 세트 응답의 출제 순서 부여용 ({@link VoiceSets}). */
        Item withSeq(int newSeq) {
            return new Item(itemId, newSeq, type, prompt, scriptKey, guideF0, choices, correctChoiceId);
        }
    }

    /**
     * 사전 산출된 예측 F0 가이드 곡선 (§3.2, 산출: KAN-17 / 렌더링: KAN-54).
     * <p>
     * 개발 시점에 AI 파이프라인으로 뽑는 정적 데이터다 - 런타임 생성과 서버 호출 없음.
     * 현재 seed의 곡선과 허용 밴드는 KAN-17 미착수 상태의 임시 더미이고, 정본 산출물이
     * 나오면 새 testVersion으로 교체한다 (KAN-26 버전 불변 원칙).
     *
     * @param unit            semitone - 화자 음역 정규화 단위
     * @param frameIntervalMs 시간축 샘플링 간격
     * @param values          정규화된 semitone 배열. 무성 구간은 null 원소다 - 단위가
     *                        정규화 semitone이라 0은 평균 음높이라는 유효한 값이고, 무성을
     *                        숫자로 겹쳐 표현할 방법이 없다 (2026-08-17 확정, KAN-102).
     * @param bandLow         허용 밴드 하한 - required (2026-08-09 확정, §3.2, §6),
     *                        values와 길이가 같아야 하며 발행 검증이 강제한다.
     * @param bandHigh        허용 밴드 상한 - required, 길이 규칙은 bandLow와 같다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GuideF0(
            String unit,
            int frameIntervalMs,
            List<Double> values,
            @Nullable List<Double> bandLow,
            @Nullable List<Double> bandHigh) {
    }

    /** 4지선다 선택지. 정오 정보는 없다 - 정답은 문항의 {@code correctChoiceId}에만 있다. */
    public record Choice(String choiceId, String text) {
    }
}
