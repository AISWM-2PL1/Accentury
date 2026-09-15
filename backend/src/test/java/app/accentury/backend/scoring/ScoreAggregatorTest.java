package app.accentury.backend.scoring;

import app.accentury.backend.testdefinition.TestDefinition;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sv-0.3 집계식과 등급 판정(KAN-21 AC), sv-0.4 억양 전처리(KAN-200 AC)의 단위 명세.
 * <p>
 * 실제 발행 seed(score-versions/sv-0.3.json, sv-0.4.json)를 로드한 레지스트리로 검증한다 -
 * 코드가 아니라 설정 파일의 가중치와 경계, 계수 규칙이 실제로 쓰임을 함께 확인하기 위해서다.
 */
class ScoreAggregatorTest {

    private final ScoreAggregator aggregator = new ScoreAggregator(
            new ScorePolicyRegistry(JsonMapper.builder().build()));

    // === KAN-21 - 검산 가능성: 티켓의 예시 그대로 ===

    @Test
    void 억양78_단어60은_종합72_명예주민이다() {
        AggregateScore score = aggregator.aggregate("sv-0.3", definition(),
                voiceScores(78, 78, 78, 78, 78), answersWithCorrect(3));

        assertEquals("sv-0.3", score.scoreVersion());
        assertEquals(78, score.intonation());
        assertEquals(60, score.vocabulary());
        assertEquals(72, score.overall());    // (78 x 2 + 60) / 3
        assertEquals("HONORARY", score.tier().code());
        assertEquals("명예주민", score.tier().name());
        assertEquals(4, score.tier().rank());
        assertEquals(5, score.tierCount());
    }

    // === AC - 확정 표와 동일한 경계에서 결정적으로 판정, 경계값은 상위 등급 ===

    @Test
    void 경계값_20_40_60_80은_상위_등급이다() {
        assertEquals("TRAVELER", tierCodeAt(20));
        assertEquals("WANNABE", tierCodeAt(40));
        assertEquals("HONORARY", tierCodeAt(60));
        assertEquals("NATIVE", tierCodeAt(80));
    }

    @Test
    void 경계_바로_아래는_하위_등급이다() {
        // 억양 79, 단어 80 → (158 + 80) / 3 = 79.33 → 79 - NATIVE 문턱 바로 아래
        AggregateScore score = aggregator.aggregate("sv-0.3", definition(),
                voiceScores(79, 79, 79, 79, 79), answersWithCorrect(4));
        assertEquals(79, score.overall());
        assertEquals("HONORARY", score.tier().code());
    }

    @Test
    void 최저점은_외지인_최고점은_경남_토박이다() {
        AggregateScore lowest = aggregator.aggregate("sv-0.3", definition(),
                voiceScores(0, 0, 0, 0, 0), answersWithCorrect(0));
        assertEquals(0, lowest.overall());
        assertEquals("OUTSIDER", lowest.tier().code());
        assertEquals(1, lowest.tier().rank());

        AggregateScore highest = aggregator.aggregate("sv-0.3", definition(),
                voiceScores(100, 100, 100, 100, 100), answersWithCorrect(5));
        assertEquals(100, highest.overall());
        assertEquals("NATIVE", highest.tier().code());
        assertEquals(5, highest.tier().rank());
    }

    // === AC - 동일 입력에 대해 종합 점수와 등급이 항상 동일하다 ===

    @Test
    void 동일_입력은_항상_같은_결과다() {
        AggregateScore first = aggregator.aggregate("sv-0.3", definition(),
                voiceScores(63, 77, 41, 88, 92), answersWithCorrect(2));
        AggregateScore second = aggregator.aggregate("sv-0.3", definition(),
                voiceScores(63, 77, 41, 88, 92), answersWithCorrect(2));
        assertEquals(first, second);
    }

    // === AC - 단조성: 억양 점수만 오르면 종합 점수와 등급이 절대 내려가지 않는다 ===

    @Test
    void 억양_원점수만_오르면_종합_점수와_등급이_내려가지_않는다() {
        int previousOverall = -1;
        int previousRank = -1;
        for (int raw = 0; raw <= 100; raw++) {
            AggregateScore score = aggregator.aggregate("sv-0.3", definition(),
                    voiceScores(raw, 50, 50, 50, 50), answersWithCorrect(2));
            assertTrue(score.overall() >= previousOverall,
                    "원점수 " + raw + "에서 종합 점수가 내려갔다: " + previousOverall + " → " + score.overall());
            assertTrue(score.tier().rank() >= previousRank,
                    "원점수 " + raw + "에서 등급이 내려갔다: " + previousRank + " → " + score.tier().rank());
            previousOverall = score.overall();
            previousRank = score.tier().rank();
        }
    }

    // === §4.3 - 문항 20점 환산 합 = 원점수 평균, 표시 점수는 반올림 정수 ===

    @Test
    void 억양_점수는_원점수_평균의_반올림이다() {
        // 합 392 → 평균 78.4 → 78 / 합 393 → 평균 78.6 → 79
        assertEquals(78, aggregator.aggregate("sv-0.3", definition(),
                voiceScores(78, 78, 78, 78, 80), answersWithCorrect(0)).intonation());
        assertEquals(79, aggregator.aggregate("sv-0.3", definition(),
                voiceScores(78, 78, 78, 79, 80), answersWithCorrect(0)).intonation());
    }

    @Test
    void 종합_점수는_표시된_정수_점수의_가중_평균_반올림이다() {
        // 억양 79, 단어 0 → (158 + 0) / 3 = 52.67 → 53. 등급도 반올림된 종합 점수로 판정한다.
        AggregateScore score = aggregator.aggregate("sv-0.3", definition(),
                voiceScores(79, 79, 79, 79, 79), answersWithCorrect(0));
        assertEquals(53, score.overall());
        assertEquals("WANNABE", score.tier().code());
    }

    // === KAN-200 - sv-0.4 억양 전처리: 5문항 평균에 구간별 계수를 1회 곱한다 ===

    @Test
    void sv04_구간_경계에서_전처리_억양_점수가_계수표와_같다() {
        // 티켓 AC의 (5문항 합 → 전처리 억양 점수) 표. 소수는 반올림 전 값이라 100배 정수로 대조한다.
        assertPreprocessed(500, 10000);    // 100
        assertPreprocessed(480, 9600);     // 96
        assertPreprocessed(476, 9520);     // 95.2 - 96~100 구간이라 계수 1
        assertPreprocessed(475, 9025);     // 90.25 - 95는 91~95 구간이라 계수 0.95 (상한 포함)
        assertPreprocessed(455, 8645);     // 86.45
        assertPreprocessed(450, 8100);     // 81
        assertPreprocessed(425, 7225);     // 72.25
        assertPreprocessed(25, 25);        // 0.25
        assertPreprocessed(1, 1);          // 0.01
        assertPreprocessed(0, 0);          // 0
    }

    @Test
    void sv04_합_0부터_500까지_전처리_억양_점수는_단조_비감소다() {
        ScorePolicy policy = policies().get("sv-0.4");
        long previous = -1;
        for (int sum = 0; sum <= 500; sum++) {
            long numerator = sum * (long) policy.intonationCoefficientPercent(sum, 5);
            assertTrue(numerator >= previous, "합 " + sum + "에서 전처리 억양 점수가 내려갔다");
            previous = numerator;
        }
    }

    @Test
    void sv04_억양_원점수만_오르면_종합_점수와_등급이_내려가지_않는다() {
        int previousOverall = -1;
        int previousRank = -1;
        for (int raw = 0; raw <= 100; raw++) {
            AggregateScore score = aggregator.aggregate("sv-0.4", definition("sv-0.4"),
                    voiceScores(raw, 50, 50, 50, 50), answersWithCorrect(2));
            assertTrue(score.overall() >= previousOverall,
                    "원점수 " + raw + "에서 종합 점수가 내려갔다: " + previousOverall + " → " + score.overall());
            assertTrue(score.tier().rank() >= previousRank,
                    "원점수 " + raw + "에서 등급이 내려갔다: " + previousRank + " → " + score.tier().rank());
            previousOverall = score.overall();
            previousRank = score.tier().rank();
        }
    }

    @Test
    void sv04에서_원점수_95_다섯_개와_단어_60은_억양90_종합80_경남_토박이다() {
        // 같은 입력이 sv-0.3에서는 억양 95, 종합 83이다 - 전처리(95 x 0.95 = 90.25 → 90)만 다르다.
        AggregateScore sv04 = aggregator.aggregate("sv-0.4", definition("sv-0.4"),
                voiceScores(95, 95, 95, 95, 95), answersWithCorrect(3));
        assertEquals("sv-0.4", sv04.scoreVersion());
        assertEquals(90, sv04.intonation());
        assertEquals(60, sv04.vocabulary());
        assertEquals(80, sv04.overall());    // (90 x 2 + 60) / 3
        assertEquals("NATIVE", sv04.tier().code());

        AggregateScore sv03 = aggregator.aggregate("sv-0.3", definition(),
                voiceScores(95, 95, 95, 95, 95), answersWithCorrect(3));
        assertEquals(95, sv03.intonation());
        assertEquals(83, sv03.overall());    // (95 x 2 + 60) / 3 = 83.33
    }

    @Test
    void sv04_계수는_문항별이_아니라_5문항_평균에_1회_곱한다() {
        // 100, 100, 100, 100, 75: 평균 95 x 0.95 = 90.25 → 90. 문항별로 곱하면
        // (100 x 4 + 75 x 0.75) / 5 = 91.25 → 91이라 이 테스트가 적용 단위를 고정한다 (2026-09-08 확정).
        AggregateScore score = aggregator.aggregate("sv-0.4", definition("sv-0.4"),
                voiceScores(100, 100, 100, 100, 75), answersWithCorrect(5));
        assertEquals(90, score.intonation());
    }

    @Test
    void sv04_전처리_결과는_표시_단계에서_1회만_반올림한다() {
        // 합 455 → 평균 91 x 0.95 = 86.45 → 86. 합 453 → 평균 90.6 x 0.95 = 86.07 → 86.
        // 합 452 → 평균 90.4 x 0.95 = 85.88 → 86 - 평균을 먼저 90으로 반올림하면 구간이 내려가
        // 90 x 0.90 = 81이 되므로, 이 값이 중간 반올림이 없음을 고정한다.
        assertEquals(86, aggregator.aggregate("sv-0.4", definition("sv-0.4"),
                voiceScores(91, 91, 91, 91, 91), answersWithCorrect(0)).intonation());
        assertEquals(86, aggregator.aggregate("sv-0.4", definition("sv-0.4"),
                voiceScores(91, 91, 91, 90, 90), answersWithCorrect(0)).intonation());
        assertEquals(86, aggregator.aggregate("sv-0.4", definition("sv-0.4"),
                voiceScores(91, 91, 90, 90, 90), answersWithCorrect(0)).intonation());
    }

    @Test
    void sv04_최저점과_최고점은_sv03과_같다() {
        AggregateScore lowest = aggregator.aggregate("sv-0.4", definition("sv-0.4"),
                voiceScores(0, 0, 0, 0, 0), answersWithCorrect(0));
        assertEquals(0, lowest.intonation());
        assertEquals("OUTSIDER", lowest.tier().code());

        AggregateScore highest = aggregator.aggregate("sv-0.4", definition("sv-0.4"),
                voiceScores(100, 100, 100, 100, 100), answersWithCorrect(5));
        assertEquals(100, highest.intonation());
        assertEquals(100, highest.overall());
        assertEquals("NATIVE", highest.tier().code());
    }

    @Test
    void sv03_세션의_결과는_전처리_없이_현행과_같다() {
        // sv-0.3.json 무변경 - 계수 100%를 거쳐도 합 / 5 반올림과 같은 값이어야 한다 (KAN-200 AC).
        for (int sum = 0; sum <= 500; sum++) {
            int expected = (2 * sum + 5) / 10;
            int v = sum / 5;
            int rest = sum - v * 5;
            AggregateScore score = aggregator.aggregate("sv-0.3", definition(),
                    voiceScores(v + (rest > 0 ? 1 : 0), v + (rest > 1 ? 1 : 0), v + (rest > 2 ? 1 : 0),
                            v + (rest > 3 ? 1 : 0), v), answersWithCorrect(2));
            assertEquals(expected, score.intonation(), "합 " + sum);
        }
    }

    // === §4.3 - 어휘는 AI 없이 정의의 정답표와 대조한다 ===

    @Test
    void 어휘_점수는_정답표_대조_정답률이다() {
        Map<String, String> answers = new HashMap<>();
        answers.put("w1", "w1a");    // 정답
        answers.put("w2", "w2b");    // 오답
        answers.put("w3", "w3a");    // 정답
        answers.put("w4", "w4c");    // 오답
        answers.put("w5", "w5a");    // 정답
        AggregateScore score = aggregator.aggregate("sv-0.3", definition(),
                voiceScores(50, 50, 50, 50, 50), answers);
        assertEquals(60, score.vocabulary());    // 3/5 x 100
    }

    // === AC - 음성 문항 일부가 실패한 세션은 종합 점수를 만들지 않는다 ===

    @Test
    void 음성_점수가_하나라도_없으면_집계를_거부한다() {
        Map<String, Integer> missing = voiceScores(80, 80, 80, 80, 80);
        missing.remove("v3");
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> aggregator.aggregate("sv-0.3", definition(), missing, answersWithCorrect(5)));
        assertTrue(rejected.getMessage().contains("v3"), rejected.getMessage());
    }

    @Test
    void 어휘_답안이_하나라도_없으면_집계를_거부한다() {
        Map<String, String> missing = answersWithCorrect(5);
        missing.remove("w2");
        assertThrows(IllegalArgumentException.class,
                () -> aggregator.aggregate("sv-0.3", definition(), voiceScores(80, 80, 80, 80, 80), missing));
    }

    // === §5.1 - 정의 밖 데이터가 섞이면 "음성 5문항인데 결과 7개" 부류의 버그다 ===

    @Test
    void 정의에_없는_문항의_점수가_섞이면_집계를_거부한다() {
        Map<String, Integer> extra = voiceScores(80, 80, 80, 80, 80);
        extra.put("v9", 80);
        assertThrows(IllegalArgumentException.class,
                () -> aggregator.aggregate("sv-0.3", definition(), extra, answersWithCorrect(5)));
    }

    @Test
    void 범위_밖_원점수는_집계를_거부한다() {
        assertThrows(IllegalArgumentException.class,
                () -> aggregator.aggregate("sv-0.3", definition(),
                        voiceScores(101, 80, 80, 80, 80), answersWithCorrect(5)));
        assertThrows(IllegalArgumentException.class,
                () -> aggregator.aggregate("sv-0.3", definition(),
                        voiceScores(-1, 80, 80, 80, 80), answersWithCorrect(5)));
    }

    @Test
    void 정의에_없는_선택지는_집계를_거부한다() {
        Map<String, String> corrupted = answersWithCorrect(5);
        corrupted.put("w1", "w1z");
        assertThrows(IllegalArgumentException.class,
                () -> aggregator.aggregate("sv-0.3", definition(), voiceScores(80, 80, 80, 80, 80), corrupted));
    }

    @Test
    void 발행되지_않은_점수_버전이면_실패한다() {
        assertThrows(IllegalStateException.class,
                () -> aggregator.aggregate("sv-9.9", definition(),
                        voiceScores(80, 80, 80, 80, 80), answersWithCorrect(5)));
    }

    @Test
    void 정의의_scoreVersion이_채점_버전과_다르면_집계를_거부한다() {
        // 한쪽 정의의 정답표에 다른 쪽 정책의 가중치와 경계가 섞이는 것을 막는다 (Codex sol 리뷰 P2).
        TestDefinition base = definition();
        TestDefinition otherVersion = new TestDefinition(base.testVersion(), "sv-9.9",
                base.dialect(), base.estimatedDurationSec(), base.items());
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> aggregator.aggregate("sv-0.3", otherVersion,
                        voiceScores(80, 80, 80, 80, 80), answersWithCorrect(5)));
        assertTrue(rejected.getMessage().contains("scoreVersion"), rejected.getMessage());
    }

    // === 레지스트리 밖 정의 방어 - 0문항 정의는 0 나눗셈 대신 계약대로 거부한다 ===

    @Test
    void 음성이나_어휘_문항이_없는_정의는_집계를_거부한다() {
        TestDefinition base = definition();
        TestDefinition vocabularyOnly = new TestDefinition(base.testVersion(), base.scoreVersion(),
                base.dialect(), base.estimatedDurationSec(), base.items().stream()
                        .filter(item -> item.type() == TestDefinition.ItemType.VOCABULARY).toList());
        assertThrows(IllegalArgumentException.class,
                () -> aggregator.aggregate("sv-0.3", vocabularyOnly, Map.of(), answersWithCorrect(5)));

        TestDefinition voiceOnly = new TestDefinition(base.testVersion(), base.scoreVersion(),
                base.dialect(), base.estimatedDurationSec(), base.items().stream()
                        .filter(item -> item.type() == TestDefinition.ItemType.VOICE).toList());
        assertThrows(IllegalArgumentException.class,
                () -> aggregator.aggregate("sv-0.3", voiceOnly, voiceScores(80, 80, 80, 80, 80), Map.of()));
    }

    // === 픽스처 ===

    private static ScorePolicyRegistry policies() {
        return new ScorePolicyRegistry(JsonMapper.builder().build());
    }

    /** sv-0.4 전처리 억양 점수를 100배 정수(반올림 전)로 대조한다: 합 x 계수% = 기대값 x 5. */
    private static void assertPreprocessed(int sum, long expectedHundredths) {
        ScorePolicy policy = policies().get("sv-0.4");
        assertEquals(expectedHundredths * 5, sum * (long) policy.intonationCoefficientPercent(sum, 5),
                "합 " + sum);
    }

    /** 모든 음성 원점수와 정답 수를 같은 값 계열로 맞춰 종합 점수가 정확히 그 값이 되게 한다. */
    private String tierCodeAt(int overall) {
        AggregateScore score = aggregator.aggregate("sv-0.3", definition(),
                voiceScores(overall, overall, overall, overall, overall), answersWithCorrect(overall / 20));
        assertEquals(overall, score.overall());
        return score.tier().code();
    }

    private static Map<String, Integer> voiceScores(int v1, int v2, int v3, int v4, int v5) {
        Map<String, Integer> scores = new HashMap<>();
        scores.put("v1", v1);
        scores.put("v2", v2);
        scores.put("v3", v3);
        scores.put("v4", v4);
        scores.put("v5", v5);
        return scores;
    }

    /** 앞에서부터 {@code correct}개는 정답(a), 나머지는 오답(b)을 고른 답안 */
    private static Map<String, String> answersWithCorrect(int correct) {
        Map<String, String> answers = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            answers.put("w" + i, "w" + i + (i <= correct ? "a" : "b"));
        }
        return answers;
    }

    /** 정본 구성과 같은 음성 5 + 어휘 5. 어휘 정답은 항상 a 선택지다. */
    private static TestDefinition definition() {
        return definition("sv-0.3");
    }

    private static TestDefinition definition(String scoreVersion) {
        List<TestDefinition.Item> items = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            items.add(new TestDefinition.Item("v" + i, i * 2 - 1, TestDefinition.ItemType.VOICE, "밥 뭇나?",
                    new TestDefinition.GuideF0("semitone", 10, List.of(-0.8, 0.3, 2.8),
                            List.of(-2.3, -1.2, 1.3), List.of(0.7, 1.8, 4.3)), null, null));
            String w = "w" + i;
            items.add(new TestDefinition.Item(w, i * 2, TestDefinition.ItemType.VOCABULARY,
                    "'정구지'는 표준어로 무엇일까요?",
                    null, List.of(
                            new TestDefinition.Choice(w + "a", "부추"),
                            new TestDefinition.Choice(w + "b", "미나리"),
                            new TestDefinition.Choice(w + "c", "쑥갓"),
                            new TestDefinition.Choice(w + "d", "시금치")),
                    w + "a"));
        }
        return new TestDefinition("gn-2026.08.1", scoreVersion, "GYEONGNAM", 240, items);
    }
}
