package app.accentury.backend.analytics;

import app.accentury.backend.scoring.AggregateScore;
import app.accentury.backend.scoring.ScorePolicyRegistry;

/**
 * 집계 행 하나에 더할 증가분 (KAN-106).
 * <p>
 * 증가 지점이 둘뿐이라({@link #sessionStarted()}, {@link #completion}) 두 정적 팩터리가
 * 전부다. 등급을 code 문자열이 아니라 <b>다섯 컬럼의 0/1로 펼쳐서</b> 나르는 이유는
 * 증가 SQL에서 조건 분기(CASE)를 없애기 위해서다 - 쓰기 쪽의 code → 컬럼 대응은 여기
 * 한 곳이고(읽기 쪽은 {@code AnalyticsQueryService.tiers}), DB로 가는 것은 언제나 같은
 * 형태의 덧셈 한 문장이다.
 *
 * @param sessionsStarted   응시 시도 증가분 (0 또는 1)
 * @param sessionsCompleted 완주 증가분 (0 또는 1)
 * @param tierOutsider      등급별 증가분 - 완주 1건이 정확히 하나만 1이다.
 * @param intonationSum     억양 점수 증가분 (0~100)
 * @param vocabularySum     단어 점수 증가분 (0~100)
 * @param overallSum        종합 점수 증가분 (0~100)
 * @param scoredCount       점수 합에 들어간 건수 증가분 - 세 평균의 공통 분모다.
 */
record CounterDelta(long sessionsStarted, long sessionsCompleted,
                    long tierOutsider, long tierTraveler, long tierWannabe,
                    long tierHonorary, long tierNative,
                    long intonationSum, long vocabularySum, long overallSum, long scoredCount) {

    /** 응시 시도 1건 - {@code POST /v0/sessions} 성공 (KAN-9). 이 시점에는 점수도 등급도 없다. */
    static CounterDelta sessionStarted() {
        return new CounterDelta(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * 완주 1건 - {@code /complete}가 결과를 확정한 시점의 집계 결과를 그대로 받는다 (KAN-16, 21).
     *
     * @throws IllegalArgumentException 모르는 등급 code - 세는 자리가 없는 등급이 조용히
     *                                  버려지면 분포 합과 완주 수가 어긋난다.
     *                                  {@link ScorePolicyRegistry#TIER_CODES} 발행 검증이
     *                                  앞에서 막고 있어 실제로는 도달하지 않는다.
     */
    static CounterDelta completion(AggregateScore score) {
        String code = score.tier().code();
        return new CounterDelta(0, 1,
                is(code, "OUTSIDER"), is(code, "TRAVELER"), is(code, "WANNABE"),
                is(code, "HONORARY"), is(code, "NATIVE"),
                score.intonation(), score.vocabulary(), score.overall(), 1)
                .requireExactlyOneTier(code);
    }

    private static long is(String code, String expected) {
        return code.equals(expected) ? 1 : 0;
    }

    private CounterDelta requireExactlyOneTier(String code) {
        long counted = tierOutsider + tierTraveler + tierWannabe + tierHonorary + tierNative;
        if (counted != 1) {
            throw new IllegalArgumentException(
                    "집계할 자리가 없는 등급 code다: " + code + " (허용: " + ScorePolicyRegistry.TIER_CODES + ")");
        }
        return this;
    }
}
