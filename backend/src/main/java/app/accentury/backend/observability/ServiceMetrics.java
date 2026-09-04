package app.accentury.backend.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramGauges;

import java.time.Duration;

/**
 * 내보내는 서비스 지표의 이름 한 곳 (KAN-38).
 * <p>
 * 미터 등록은 값이 나는 자리에서 한다 - 회로 상태는 {@code AnalysisDispatchConfig}, 임시파일
 * 잔존은 {@code VoiceTempSweeper}, HTTP는 {@link HttpMetrics}처럼. 이름만 여기 모으는 이유는
 * 소비처가 코드 밖에 있기 때문이다: CloudWatch 대시보드와 경보(infra/modules/monitoring)가
 * 문자열로 이 이름들을 적어 두므로, 이름을 바꾸면 그래프가 조용히 빈다. 여기 상수를 고칠 때
 * Terraform 쪽도 같이 고친다는 규약이 그 조용한 실패를 막는 유일한 장치다.
 *
 * <h4>CloudWatch에 실리는 이름</h4>
 * Micrometer의 CloudWatch 레지스트리가 미터 종류마다 접미사를 붙인다 (1.17.0 확인) - 경보와
 * 대시보드는 <b>접미사가 붙은 이름</b>을 적어야 한다:
 * <ul>
 *   <li>Gauge -&gt; {@code <이름>.value}</li>
 *   <li>Counter -&gt; {@code <이름>.count}</li>
 *   <li>Timer -&gt; {@code <이름>.sum}, {@code .count}, {@code .avg}, {@code .max} (단위 ms)</li>
 * </ul>
 * <b>백분위는 레지스트리가 내보내지 않는다.</b> Timer의 스냅샷에는 있어도 CloudWatch 쪽 코드가
 * sum, count, avg, max만 쓴다. CloudWatch가 자기 쪽에서 p95를 계산하게 하려면 관측값이 낱개로
 * 올라가야 하는데 레지스트리는 분당 집계값 하나만 올리므로, {@code p95(avg)}는 "분당 평균들의
 * p95"라서 지연 P95가 아니다. 그래서 {@link #registerPercentiles}가 백분위를 게이지로 따로
 * 등록한다 - 이름은 {@code <이름>.percentile.value}, 차원은 {@code phi}다.
 *
 * <h4>지표를 늘릴 때의 비용</h4>
 * CloudWatch 커스텀 지표는 <b>이름 x 차원 조합</b>마다 월 0.30달러다. 태그 하나를 늘리면 값의
 * 가짓수만큼 곱해지므로, 여기 있는 태그는 전부 값이 다섯 이하로 닫혀 있다 - 세션 ID나 IP,
 * 문항 ID처럼 열린 값은 태그로 쓰지 않는다 (그러면 요금이 트래픽에 비례한다).
 */
public final class ServiceMetrics {

    // ---- HTTP (HttpMetrics) ----

    /** 전 요청의 처리 시간과 건수. 지연 P95와 오류율의 분모다. */
    public static final String HTTP_REQUESTS = "accentury.http.requests";

    /** 오류 응답 수 - 태그 {@code status}는 {@code 4xx}, {@code 5xx}. */
    public static final String HTTP_ERRORS = "accentury.http.errors";

    /**
     * 폴링 경로의 요청 수 - 태그 {@code endpoint}는 {@code analyses}, {@code complete}.
     * KAN-24 재검토 트리거 "전체 요청의 30% 초과"는 이 값을 {@link #HTTP_REQUESTS}로 나눈 비율이다.
     */
    public static final String HTTP_POLLING = "accentury.http.polling";

    /**
     * 429로 끊긴 요청 수 (KAN-28의 제한 5축) - 태그는 둘이다.
     * {@code axis}가 {@code ip}인지 {@code session}인지로 "세션별, IP별"을 가르고(KAN-38 AC),
     * {@code scope}가 다섯 축 중 어느 것인지를 말한다.
     */
    public static final String RATE_LIMITED = "accentury.ratelimit.rejected";

    // ---- 세션 (SessionMetrics) ----

    /** 아직 만료되지 않은 세션 행 수 - KAN-24 트리거 "동시 응시자 만 명대"의 측정값이다. */
    public static final String SESSIONS_ACTIVE = "accentury.sessions.active";

    // ---- 분석 (AnalysisMetrics) ----

    /** 전 인스턴스의 PROCESSING 작업 수 - 큐가 없으므로 이 값이 곧 AI에 걸린 압력이다. */
    public static final String ANALYSIS_PROCESSING = "accentury.analysis.processing";

    /** 이 인스턴스가 붙들고 있는 전달 건수 ({@code AnalysisBacklog}) - 태스크별 몫이다. */
    public static final String ANALYSIS_INFLIGHT = "accentury.analysis.inflight";

    /**
     * 타임아웃으로 종결된 작업 수 - 태그 {@code reason}은 {@code stuck}(실행 잔류),
     * {@code lost}(큐 유실)다.
     */
    public static final String ANALYSIS_TIMEOUTS = "accentury.analysis.timeouts";

    /**
     * 폴링 간격 산출 횟수 - 태그 {@code congested}가 {@code true}면 혼잡 간격을 준 회차다.
     * 혼잡 발동 비율은 {@code true} / (전체)다.
     */
    public static final String ANALYSIS_POLL_DECISIONS = "accentury.analysis.poll";

    /** 전달 접수부터 종결까지 걸린 시간 - NFR-PF-01(3초)의 측정값이다. */
    public static final String ANALYSIS_DURATION = "accentury.analysis.duration";

    /** 대시보드와 경보가 읽는 유일한 백분위. 늘리면 지표 수가 그만큼 늘어난다. */
    public static final double PERCENTILE = 0.95;

    /**
     * 백분위 창은 발행 주기와 같아야 한다 - {@code management.cloudwatch.metrics.export.step}
     * (배포에서 1분)와 맞춘 값이다. 기본값(2분, 버퍼 3개)이면 한 번 튄 지연이 두 발행 주기에
     * 걸쳐 보고돼 대시보드에서 지속 시간을 실제보다 길게 읽는다.
     */
    public static final Duration PERCENTILE_WINDOW = Duration.ofMinutes(1);

    private ServiceMetrics() {
    }

    /**
     * 지연 지표용 Timer를 만든다 - 백분위 창과 발행 백분위를 한 곳에서 정한다.
     * 등록 자체는 {@link #registerPercentiles}가 이어서 한다.
     */
    public static Timer.Builder latencyTimer(String name, String description) {
        return Timer.builder(name)
                .description(description)
                .publishPercentiles(PERCENTILE)
                // 히스토그램 버킷은 내보내지 않는다 - CloudWatch가 읽지 않는데 지표 수만 늘린다.
                .publishPercentileHistogram(false)
                .distributionStatisticExpiry(PERCENTILE_WINDOW);
    }

    /**
     * Timer의 백분위를 게이지로 등록한다 - 이것이 없으면 CloudWatch에 P95가 아예 올라가지 않는다
     * (위 "CloudWatch에 실리는 이름" 참고).
     * <p>
     * Micrometer의 {@link HistogramGauges}를 그대로 쓴다 - 발행 주기마다 스냅샷을 한 번만 뜨는
     * 처리가 들어 있어, 백분위 게이지를 직접 만들면 폴링마다 스냅샷을 떠 값이 게이지끼리 어긋난다.
     */
    public static void registerPercentiles(Timer timer, MeterRegistry registry) {
        HistogramGauges.registerWithCommonFormat(timer, registry);
    }
}
