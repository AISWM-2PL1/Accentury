package app.accentury.backend.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.server.RequestPath;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * API 지연, 오류율, 폴링 비율의 계측 (KAN-38).
 * <p>
 * Boot가 이미 내는 {@code http.server.requests}를 쓰지 않는 이유는 둘이다. 하나는 이름이
 * {@code accentury.}로 시작하지 않아 CloudWatch 레지스트리의 필터에 걸린다는 것이고
 * ({@code CloudWatchMetricsConfig} - 그 필터를 푸는 순간 JVM과 Tomcat 지표까지 요금이 붙는다),
 * 다른 하나는 그 지표의 태그가 {@code uri} x {@code status} x {@code method} x {@code outcome}
 * 이라 CloudWatch 차원 조합이 수십 개로 갈라진다는 것이다. 여기서는 대시보드가 실제로 읽는
 * 세 가지만, 값이 닫힌 태그로 낸다 ({@link ServiceMetrics}의 비용 설명).
 *
 * <h4>어디까지 세는가</h4>
 * <b>{@code /v0/**}만 센다.</b> ALB 헬스체크가 {@code /actuator/health}를 수 초마다 두드리므로
 * (KAN-131, KAN-166) 그것까지 세면 요청 수가 헬스체크로 채워져 "{@code /analyses}가 전체 요청의
 * 몇 %인가"(KAN-24 트리거)가 무의미해지고, 즉답하는 헬스체크가 지연 분포를 아래로 끌어내린다.
 * 운영자 API({@code /admin/v0/**})도 뺀다 - 사람이 가끔 부르는 경로라 사용자 트래픽 지표에
 * 섞이면 안 된다.
 *
 * <h4>필터 순서</h4>
 * {@code CorrelationIdFilter} 바로 뒤다 ({@link HttpMetricsConfig}). 요청 제한 필터
 * ({@code UploadRateLimitFilter})보다 <b>바깥</b>이어야 그 필터가 끊은 429도 요청과 오류로
 * 세어진다 - 안쪽에 두면 한도를 넘긴 트래픽이 지표에서 통째로 사라져, 부하가 몰릴수록
 * 대시보드가 조용해진다.
 */
class HttpMetrics extends OncePerRequestFilter {

    /** 계측 대상 - 사용자 API만. 관리자 API({@code /admin/v0/**})는 이 접두사 밖이다. */
    private static final String MEASURED_PREFIX = "/v0/";

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;
    private static final PathPattern ANALYSES = PARSER.parse("/v0/sessions/*/analyses/**");
    private static final PathPattern COMPLETE = PARSER.parse("/v0/sessions/*/complete");

    private final Timer requests;
    private final Counter clientErrors;
    private final Counter serverErrors;
    private final Counter analysesPolls;
    private final Counter completePolls;

    HttpMetrics(MeterRegistry registry) {
        this.requests = ServiceMetrics.latencyTimer(ServiceMetrics.HTTP_REQUESTS,
                        "사용자 API(/v0/**) 요청의 처리 시간 - 지연 P95와 오류율의 분모 (KAN-38)")
                .register(registry);
        // 백분위는 레지스트리가 알아서 내보내지 않는다 - 게이지로 따로 등록해야 CloudWatch에 오른다.
        ServiceMetrics.registerPercentiles(requests, registry);
        this.clientErrors = errorCounter(registry, "4xx");
        this.serverErrors = errorCounter(registry, "5xx");
        this.analysesPolls = pollingCounter(registry, "analyses");
        this.completePolls = pollingCounter(registry, "complete");
    }

    private static Counter errorCounter(MeterRegistry registry, String status) {
        return Counter.builder(ServiceMetrics.HTTP_ERRORS)
                .description("오류 응답 수 - 오류율은 이 값을 " + ServiceMetrics.HTTP_REQUESTS + "로 나눈 비율 (KAN-38)")
                .tag("status", status)
                .register(registry);
    }

    private static Counter pollingCounter(MeterRegistry registry, String endpoint) {
        return Counter.builder(ServiceMetrics.HTTP_POLLING)
                .description("폴링 경로의 요청 수 - KAN-24 재검토 트리거의 측정값 (KAN-38)")
                .tag("endpoint", endpoint)
                .register(registry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(MEASURED_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        // 경로 판별을 체인보다 먼저 한다 - DispatcherServlet은 자기가 캐시한 경로를 응답 뒤에
        // 지우므로, 나중에 판별하면 요청이 끝난 뒤에 다시 파싱해 재활용될 객체에 속성을 남긴다.
        Counter polling = pollingCounter(request);
        long startNanos = System.nanoTime();
        boolean threw = true;
        try {
            filterChain.doFilter(request, response);
            threw = false;
        } finally {
            // finally인 것은 의도다 - 체인이 예외로 끝나도 요청 한 건이고, 오히려 그쪽이 더
            // 알아야 할 실패다.
            //
            // 이때 응답 상태를 믿을 수 없다. 예외가 필터나 서블릿을 뚫고 올라가면 컨테이너가
            // 500으로 바꾸는 것은 이 finally보다 <b>나중</b>이라, 여기서 읽으면 아직 200이다 -
            // 상태만 보고 세면 가장 심한 실패가 성공으로 집계된다. 그래서 예외로 빠져나갔다는
            // 사실 자체를 5xx로 센다 (MVC 안에서 난 예외는 GlobalExceptionHandler가 잡아
            // 정상적으로 상태를 세팅하므로 여기까지 오지 않는다).
            record(response, polling, threw, System.nanoTime() - startNanos);
        }
    }

    private void record(HttpServletResponse response, @Nullable Counter polling, boolean threw,
                        long elapsedNanos) {
        requests.record(elapsedNanos, TimeUnit.NANOSECONDS);
        int status = response.getStatus();
        if (threw || status >= 500) {
            serverErrors.increment();
        } else if (status >= 400) {
            clientErrors.increment();
        }
        if (polling != null) {
            polling.increment();
        }
    }

    /**
     * 폴링 경로 판별 - MVC 라우팅과 같은 정규화로 매칭한다 ({@code UploadRateLimitFilter}와 같은
     * 이유: raw URI 정규식은 {@code %61nalyses} 같은 변형을 놓쳐 비율이 실제보다 낮게 보인다).
     */
    private @Nullable Counter pollingCounter(HttpServletRequest request) {
        RequestPath path;
        try {
            path = ServletRequestPathUtils.hasParsedRequestPath(request)
                    ? ServletRequestPathUtils.getParsedRequestPath(request)
                    : ServletRequestPathUtils.parseAndCache(request);
        } catch (RuntimeException e) {
            // 경로를 파싱하지 못하는 요청(잘못된 인코딩 등)은 어차피 400으로 끝난다 -
            // 계측 때문에 응답을 바꾸지 않는다.
            return null;
        }
        var within = path.pathWithinApplication();
        if (ANALYSES.matches(within)) {
            return analysesPolls;
        }
        return COMPLETE.matches(within) ? completePolls : null;
    }
}
