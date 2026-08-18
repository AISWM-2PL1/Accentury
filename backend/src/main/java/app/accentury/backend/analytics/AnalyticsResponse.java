package app.accentury.backend.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 내부 집계 조회 응답 (KAN-106 AC - 등급별 누적 수와 세 점수 평균 확인).
 * <p>
 * 클라이언트 API가 아니라 운영자용이다 (§2.3 오류 봉투는 공유하지만 이 성공 응답은
 * 명세서 계약이 아니다). 대시보드는 이 티켓 범위가 아니므로 화면 없이 JSON만 준다.
 *
 * @param from   조회 시작 일자 (포함)
 * @param to     조회 종료 일자 (포함)
 * @param zone   일자 경계를 정한 타임존 - 이 값을 모르면 "8월 17일"이 언제인지 알 수 없다
 * @param rows   일자와 버전별 한 줄 - 저장된 행 그대로다
 * @param totals 기간 전체 합산 - 버전이 섞이므로 등급 분포와 평균은 참고값이다
 */
public record AnalyticsResponse(LocalDate from, LocalDate to, String zone,
                                List<Row> rows, Counts totals) {

    /**
     * @param date         집계 일자 ({@code zone} 기준)
     * @param testVersion  테스트 정의 버전 - 다르면 문항이 다르므로 같은 통계가 아니다
     * @param scoreVersion 점수 버전 - 다르면 등급 경계가 다르므로 분포를 비교할 수 없다
     */
    public record Row(LocalDate date, String testVersion, String scoreVersion, Counts counts) {
    }

    /**
     * @param sessionsStarted   응시 시도 수
     * @param sessionsCompleted 완주 수
     * @param completionRate    완주율 (완주/시도) - 시도가 0이면 null이다. 소수점 넷째 자리 반올림.
     *                          시도와 완주를 각자 일어난 날에 세므로 자정을 넘겨 끝낸 응시는
     *                          두 날에 갈라진다 - 하루 단위로는 1.0을 넘거나 시도 0에 완주만
     *                          있는 행이 나올 수 있고, 그건 기간 합계로 봐야 한다 (버그가 아니다)
     * @param tiers             등급 code → 누적 수. 순서는 rank 오름차순이다
     * @param scoredCount       점수 합에 들어간 건수 - 평균의 분모다
     * @param sums              점수 합 - 평균의 검산에 쓴다 (개별 점수 행이 없으므로 이것이 원본이다)
     * @param averages          세 점수 평균 - {@code scoredCount}가 0이면 null이다. 소수점 둘째 자리 반올림
     */
    // 값이 없는 평균과 완주율은 필드를 통째로 뺀다 - 0으로 나눌 수 없는 것과 0인 것은 다르다
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Counts(long sessionsStarted, long sessionsCompleted,
                         @Nullable Double completionRate,
                         Map<String, Long> tiers, long scoredCount,
                         Sums sums, @Nullable Averages averages) {

        /**
         * 원자료(시도, 완주, 등급 분포, 분모, 합)에서 파생 필드(완주율, 평균)를 계산해
         * 조립한다 - 반올림 자리와 null 규칙이 위 필드 주석과 한 파일에서 움직이게
         * 파생 계산을 정의 옆에 둔다 (2026-08-17 리뷰).
         */
        public static Counts of(long sessionsStarted, long sessionsCompleted,
                                Map<String, Long> tiers, long scoredCount, Sums sums) {
            return new Counts(sessionsStarted, sessionsCompleted,
                    sessionsStarted == 0 ? null
                            : round((double) sessionsCompleted / sessionsStarted, 10_000),
                    // Map.copyOf가 아니다 - 그쪽은 순회 순서를 보장하지 않아 등급이 rank 순서를 잃는다.
                    Collections.unmodifiableMap(tiers),
                    scoredCount, sums,
                    scoredCount == 0 ? null : new Averages(
                            round((double) sums.intonation() / scoredCount, 100),
                            round((double) sums.vocabulary() / scoredCount, 100),
                            round((double) sums.overall() / scoredCount, 100)));
        }
    }

    private static double round(double value, int scale) {
        return Math.round(value * scale) / (double) scale;
    }

    /** 점수 합 (0~100의 누적) */
    public record Sums(long intonation, long vocabulary, long overall) {
    }

    /** 점수 평균 (0~100) */
    public record Averages(double intonation, double vocabulary, double overall) {
    }
}
