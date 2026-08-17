package app.accentury.backend.analytics;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import app.accentury.backend.scoring.ScorePolicyRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 집계 카운터 조회 (KAN-106 AC - 5등급 누적 수와 세 점수 평균).
 * <p>
 * 조회 API와 대시보드는 티켓 범위 밖이지만 "조회할 수 있다"는 AC는 확인할 수단을
 * 요구한다 - 그래서 읽기 전용 계산만 하고, 화면도 가공도 없다. 평균은 여기서 나눈다:
 * 저장은 합과 건수뿐이라(개별 점수 행 미저장) 나눗셈이 읽는 쪽 몫이다.
 */
@Service
public class AnalyticsQueryService {

    private final DailyCounterRepository repository;
    private final ZoneId zone;
    private final int maxQueryDays;

    public AnalyticsQueryService(DailyCounterRepository repository, AccenturyProperties properties) {
        this.repository = repository;
        this.zone = properties.analytics().zone();
        this.maxQueryDays = properties.analytics().maxQueryDays();
    }

    /**
     * 기간 조회. 양 끝 일자를 포함한다.
     * <p>
     * 빠진 경계의 기본값은 <b>비대칭</b>이다 (2026-08-17 확정).
     * <ul>
     *   <li>둘 다 없으면 오늘 하루</li>
     *   <li>{@code from}만 있으면 그 날부터 <b>오늘까지</b> - "이 날부터"라는 자연스러운 질의다</li>
     *   <li>{@code to}만 있으면 <b>그 하루</b> - 여기서도 오늘을 기본값으로 잡으면 과거 날짜
     *       하나를 보려던 호출자가 보낸 적도 없는 {@code from}이 뒤라는 400을 받는다</li>
     * </ul>
     *
     * @param from null이면 {@code to}가 정한다 (둘 다 null이면 오늘, 설정 타임존 기준)
     * @param to   null이면 오늘
     * @throws ApiException 400 - 역전된 기간이나 상한을 넘는 기간. 실수로 전 기간을 훑는
     *                      질의가 운영 DB를 붙잡지 않게 막는다
     */
    @Transactional(readOnly = true)
    public AnalyticsResponse query(@Nullable LocalDate from, @Nullable LocalDate to) {
        LocalDate today = LocalDate.now(zone);
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end;

        if (start.isAfter(end)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "from이 to보다 뒤입니다: " + start + " ~ " + end);
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > maxQueryDays) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "조회 기간이 너무 깁니다: " + days + "일 (최대 " + maxQueryDays + "일)");
        }

        List<DailyCounter> found =
                repository.findByStatDateBetweenOrderByStatDateAscTestVersionAscScoreVersionAsc(start, end);
        List<AnalyticsResponse.Row> rows = found.stream()
                .map(c -> new AnalyticsResponse.Row(c.statDate(), c.testVersion(), c.scoreVersion(), counts(c)))
                .toList();
        return new AnalyticsResponse(start, end, zone.getId(), rows, total(found));
    }

    /**
     * 읽기 쪽의 등급 컬럼 → code 대응은 <b>여기 한 곳</b>이다 (Fable 리뷰 P3 - 원래 세 곳에
     * 흩어져 있었다). 순서는 {@link ScorePolicyRegistry#TIER_CODES}의 rank 오름차순이고,
     * 0인 등급도 반드시 실린다 - 빠지면 분포를 읽을 수 없다.
     */
    private static Map<String, Long> tiers(DailyCounter c) {
        Map<String, Long> tiers = new LinkedHashMap<>();
        tiers.put("OUTSIDER", c.tierOutsider());
        tiers.put("TRAVELER", c.tierTraveler());
        tiers.put("WANNABE", c.tierWannabe());
        tiers.put("HONORARY", c.tierHonorary());
        tiers.put("NATIVE", c.tierNative());
        if (!List.copyOf(tiers.keySet()).equals(ScorePolicyRegistry.TIER_CODES)) {
            // 등급이 늘거나 이름이 바뀌거나 rank 순서가 달라지면 여기도 같이 고쳐야 한다 -
            // 조용히 한 등급이 빠지거나 순서가 뒤집힌 분포를 내보내는 대신 즉시 깨뜨린다.
            // 집합이 아니라 목록 비교인 것은 위 javadoc의 rank 오름차순 약속까지 지키기 위해서다
            throw new IllegalStateException("등급 목록이 " + ScorePolicyRegistry.TIER_CODES + "와 어긋난다");
        }
        return tiers;
    }

    private static AnalyticsResponse.Counts counts(DailyCounter c) {
        return build(c.sessionsStarted(), c.sessionsCompleted(), tiers(c), c.scoredCount(),
                c.intonationSum(), c.vocabularySum(), c.overallSum());
    }

    /**
     * 기간 전체 합산. 버전이 섞일 수 있으므로 참고값이다 - 버전별 비교는 {@code rows}로 한다.
     */
    private static AnalyticsResponse.Counts total(List<DailyCounter> found) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (String code : ScorePolicyRegistry.TIER_CODES) {
            totals.put(code, 0L);
        }
        long started = 0;
        long completed = 0;
        long scored = 0;
        long intonation = 0;
        long vocabulary = 0;
        long overall = 0;
        for (DailyCounter c : found) {
            started += c.sessionsStarted();
            completed += c.sessionsCompleted();
            scored += c.scoredCount();
            intonation += c.intonationSum();
            vocabulary += c.vocabularySum();
            overall += c.overallSum();
            tiers(c).forEach((code, count) -> totals.merge(code, count, Long::sum));
        }
        return build(started, completed, totals, scored, intonation, vocabulary, overall);
    }

    private static AnalyticsResponse.Counts build(long started, long completed, Map<String, Long> tiers,
                                                  long scored, long intonation, long vocabulary, long overall) {
        return new AnalyticsResponse.Counts(started, completed,
                started == 0 ? null : round((double) completed / started, 10_000),
                // Map.copyOf가 아니다 - 그쪽은 순회 순서를 보장하지 않아 등급이 rank 순서를 잃는다
                Collections.unmodifiableMap(tiers),
                scored,
                new AnalyticsResponse.Sums(intonation, vocabulary, overall),
                scored == 0 ? null : new AnalyticsResponse.Averages(
                        round((double) intonation / scored, 100),
                        round((double) vocabulary / scored, 100),
                        round((double) overall / scored, 100)));
    }

    private static double round(double value, int scale) {
        return Math.round(value * scale) / (double) scale;
    }
}
