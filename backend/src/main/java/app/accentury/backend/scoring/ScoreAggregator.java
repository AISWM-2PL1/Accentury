package app.accentury.backend.scoring;

import app.accentury.backend.testdefinition.TestDefinition;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * sv-0.3 확정 집계식의 구현 (KAN-21, API 명세서 §4.3).
 * <p>
 * 순수 산술이다 - AI 호출, DB 조회, 상태가 없다. 같은 입력이면 언제나 같은 점수와
 * 등급이 나온다 (KAN-21 AC - 결정성). 가중치와 경계값은 {@link ScorePolicy} seed가
 * 정본이고, 여기는 식의 뼈대만 안다.
 * <p>
 * 입력 완전성을 강제한다: 음성 문항 하나라도 성공 점수가 없으면 종합 점수를 만들지
 * 않는다 (KAN-21 AC - KAN-25 복구 경로). 완주 검증과 사용자 오류 응답(RESULT_INCOMPLETE,
 * RESULT_RETAKE_REQUIRED)은 {@code /complete}(KAN-16)의 몫이고, 여기 도달한 불완전
 * 입력은 서버 버그이므로 {@link IllegalArgumentException}으로 크게 실패한다.
 */
@Service
public class ScoreAggregator {

    private final ScorePolicyRegistry policies;

    public ScoreAggregator(ScorePolicyRegistry policies) {
        this.policies = policies;
    }

    /**
     * 세션 전체 문항 결과를 종합 점수와 등급으로 접는다. {@code /complete} 시점에 1회 호출된다 (§4.3).
     * <ul>
     *   <li>억양 점수 = 음성 5문항 점수의 합. 문항 점수는 AI 원점수(0~100) ÷ 5(20점 만점)라
     *       합은 원점수 평균과 같은 값이다 (2026-08-09 확정 - AI 계약 무변경, KAN-22).</li>
     *   <li>단어 점수 = 정답 수 x 100 ÷ 문항 수. 어휘는 AI를 거치지 않고 정의의 정답표와
     *       대조한다 (§4.3, §5.7).</li>
     *   <li>종합 점수 = 가중 평균, 등급 = {@link ScorePolicy#tierFor}</li>
     * </ul>
     * 사용자에게 보이는 세 점수는 모두 반올림한 정수이고, 등급은 그 정수 종합 점수로
     * 판정한다 - 표시 점수로 검산한 결과와 등급이 어긋나지 않게 하기 위해서다
     * (KAN-21 - 검산 가능성: 억양 78, 단어 60 → 72 → 명예주민). 반올림은 값을 내리지
     * 않으므로 억양 점수가 오르면 종합 점수와 등급도 절대 내려가지 않는다 (AC - 단조성).
     *
     * @param scoreVersion           세션이 생성 시점에 고정한 점수 버전 (§5.4)
     * @param definition             세션의 세트 정의 (음성 5 + 어휘 5) - 문항 구성과 어휘 정답표의
     *                               출처. 풀 정의가 아니라 {@code TestDefinitionRegistry#sessionDefinition}이
     *                               준 세트여야 한다 (KAN-182) - 점수 규칙은 세트 도입 전과 같다.
     * @param intonationScoreByItem  음성 itemId → AI 원점수 0~100. 채점 대상은 문항당 최신 성공
     *                               시도 1건이다 ({@code AnalysisStatusService#representativeByItem}의
     *                               COMPLETED 대표, §5.1)
     * @param chosenChoiceIdByItem   어휘 itemId → 제출된 choiceId (KAN-15 답안 저장소)
     * @throws IllegalArgumentException 문항 누락과 초과, 범위 밖 점수, 정의에 없는 선택지 -
     *                                  전부 호출부 버그거나 데이터 오염이다.
     */
    public AggregateScore aggregate(String scoreVersion, TestDefinition definition,
                                    Map<String, Integer> intonationScoreByItem,
                                    Map<String, String> chosenChoiceIdByItem) {
        ScorePolicy policy = policies.get(scoreVersion);
        // 정의가 지정한 채점 버전과 세션의 버전이 어긋나면 한쪽 정의의 정답표에 다른 쪽
        // 정책의 가중치와 경계가 섞인 결과가 정본 행세를 한다 (Codex sol 리뷰 P2).
        // 활성 쌍은 기동 검사가 맞추지만(TestDefinitionRegistry), 복수 버전 공존 시의
        // 호출부 버그는 여기서만 잡을 수 있다.
        require(definition.scoreVersion().equals(policy.scoreVersion()),
                "정의의 scoreVersion(" + definition.scoreVersion()
                        + ")이 채점 버전(" + policy.scoreVersion() + ")과 다르다");

        int intonationSum = 0;
        int voiceCount = 0;
        int correctCount = 0;
        int vocabularyCount = 0;
        for (TestDefinition.Item item : definition.items()) {
            switch (item.type()) {
                case VOICE -> {
                    Integer score = intonationScoreByItem.get(item.itemId());
                    // 일부 실패나 미제출 세션은 종합 점수를 만들지 않는다 (KAN-21 AC).
                    require(score != null, "음성 문항의 성공 점수가 없다: " + item.itemId());
                    require(score >= 0 && score <= 100,
                            "억양 원점수가 0~100 밖이다: " + item.itemId() + " = " + score);
                    intonationSum += score;
                    voiceCount++;
                }
                case VOCABULARY -> {
                    String chosen = chosenChoiceIdByItem.get(item.itemId());
                    require(chosen != null, "어휘 문항의 답안이 없다: " + item.itemId());
                    // 제출 API(KAN-15)가 검증한 choiceId만 저장되므로, 벗어난 값은 데이터 오염이다.
                    require(item.choices() != null && item.choices().stream()
                                    .anyMatch(choice -> choice.choiceId().equals(chosen)),
                            "정의에 없는 선택지다: " + item.itemId() + " = " + chosen);
                    if (chosen.equals(item.correctChoiceId())) {
                        correctCount++;
                    }
                    vocabularyCount++;
                }
            }
        }
        // 정의 밖 itemId가 섞이면 "음성 5문항인데 결과 7개" 부류의 집계 버그다 (§5.1).
        require(intonationScoreByItem.size() == voiceCount,
                "정의에 없는 음성 점수가 있다: " + intonationScoreByItem.keySet());
        require(chosenChoiceIdByItem.size() == vocabularyCount,
                "정의에 없는 어휘 답안이 있다: " + chosenChoiceIdByItem.keySet());

        // 문항 구성(5+5)의 정본 검증은 레지스트리 몫이다 - 여기서는 0문항 정의가 아래
        // 나눗셈을 0으로 나누지 않게 존재만 강제한다.
        require(voiceCount > 0, "음성 문항이 없는 정의다: " + definition.testVersion());
        require(vocabularyCount > 0, "어휘 문항이 없는 정의다: " + definition.testVersion());

        int intonation = roundHalfUp(intonationSum, voiceCount);
        int vocabulary = roundHalfUp(correctCount * 100, vocabularyCount);
        int overall = roundHalfUp(
                (long) intonation * policy.intonationWeight() + (long) vocabulary * policy.vocabularyWeight(),
                policy.intonationWeight() + (long) policy.vocabularyWeight());
        return new AggregateScore(policy.scoreVersion(), intonation, vocabulary, overall,
                policy.tierFor(overall), policy.tiers().size());
    }

    /**
     * 음이 아닌 정수 나눗셈의 사사오입 - 부동소수점을 쓰지 않아 플랫폼과 무관하게
     * 결정적이다 (KAN-21 AC). floor((n + d/2) / d)와 같고, 정확히 .5는 올린다.
     * long 산술이라 배증(2x)이 어떤 가중치 조합에서도 오버플로하지 않는다.
     */
    private static int roundHalfUp(long numerator, long denominator) {
        return (int) ((2 * numerator + denominator) / (2 * denominator));
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException("집계 불가 - " + message);
        }
    }
}
