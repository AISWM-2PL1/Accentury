package app.accentury.backend.scoring;

/**
 * 세션 하나의 최종 집계 결과 (KAN-21, API 명세서 §3.7).
 * <p>
 * 결과에는 억양, 단어, 종합 점수와 등급만 있다 - 발음과 리듬 점수는 범위 제외(2026-07-22)이고,
 * 학습 레벨이나 Lv 표기는 만들지 않는다 (KAN-21 AC). {@code /result}(KAN-25)가
 * {@code scores}와 {@code tier}로 그대로 옮긴다. 클라이언트 재계산 금지 - 이 값이 정본이다.
 *
 * @param scoreVersion 집계에 쓴 점수 버전 - 이 값 하나로 가중치와 경계를 재현할 수 있다 (KAN-21 AC)
 * @param intonation   억양 점수 0~100 - 음성 5문항 20점 환산 점수의 합 (= 원점수 평균, 반올림)
 * @param vocabulary   단어 점수 0~100 - 어휘 정답률 x 100 (0/20/40/60/80/100)
 * @param overall      종합 점수 0~100 - 가중 평균 (sv-0.3: 억양 2 : 단어 1, 반올림)
 * @param tier         판정된 등급 - §3.7 tier.code/name/rank
 * @param tierCount    전체 등급 수 (5) - §3.7 tier.of
 */
public record AggregateScore(
        String scoreVersion,
        int intonation,
        int vocabulary,
        int overall,
        ScorePolicy.Tier tier,
        int tierCount) {
}
