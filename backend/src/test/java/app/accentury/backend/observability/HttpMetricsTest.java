package app.accentury.backend.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 계측의 명세 (KAN-38 AC - P95 지연과 오류율 대시보드, {@code /analyses} 요청 비율).
 * <p>
 * 세는 범위와 가르는 기준만 본다. 계측이 응답을 바꾸지 않는다는 것(예외가 나도 요청은 세어지고
 * 예외는 그대로 올라간다)도 여기서 함께 본다 - 지표 코드가 장애를 만들면 안 된다.
 */
class HttpMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final HttpMetrics filter = new HttpMetrics(registry);

    @Test
    void 사용자_API_요청을_지연과_건수로_센다() throws Exception {
        call("GET", "/v0/sessions/s_1/result", 200);

        assertEquals(1, requestCount());
        assertEquals(0, errorCount("4xx"));
        assertEquals(0, errorCount("5xx"));
    }

    @Test
    void 헬스체크와_운영자_API는_세지_않는다() throws Exception {
        // ALB가 수 초마다 두드리는 경로까지 세면 "/analyses가 전체의 몇 %인가"(KAN-24 트리거)가
        // 헬스체크로 희석되고, 즉답하는 헬스체크가 지연 분포를 아래로 끌어내린다.
        call("GET", "/actuator/health", 200);
        call("GET", "/admin/v0/analytics", 200);

        assertEquals(0, requestCount());
    }

    @Test
    void 상태코드로_4xx와_5xx를_가른다() throws Exception {
        call("POST", "/v0/sessions", 429);
        call("GET", "/v0/sessions/s_1/result", 500);

        assertEquals(2, requestCount(), "오류도 요청이다 - 오류율의 분모에 들어야 한다");
        assertEquals(1, errorCount("4xx"));
        assertEquals(1, errorCount("5xx"));
    }

    @Test
    void 폴링_경로를_엔드포인트별로_센다() throws Exception {
        // 일괄 조회와 단건 조회 둘 다 대기 화면의 폴링이다 (§3.4).
        call("GET", "/v0/sessions/s_1/analyses", 200);
        call("GET", "/v0/sessions/s_1/analyses/a_1", 200);
        call("POST", "/v0/sessions/s_1/complete", 200);
        call("GET", "/v0/sessions/s_1/result", 200);

        assertEquals(2, pollingCount("analyses"));
        assertEquals(1, pollingCount("complete"));
        assertEquals(4, requestCount(), "폴링도 전체 요청에 들어간다 - 비율의 분모다");
    }

    @Test
    void 경로_변형으로_폴링_집계를_피할_수_없다() throws Exception {
        // raw URI 정규식이면 %61nalyses가 컨트롤러에는 닿으면서 집계만 비껴간다 -
        // 요청 제한 필터와 같은 이유로 MVC와 같은 정규화로 매칭한다.
        call("GET", "/v0/sessions/s_1/%61nalyses", 200);

        assertEquals(1, pollingCount("analyses"));
    }

    @Test
    void 체인에서_예외가_나면_상태가_아직_200이어도_5xx로_센다() {
        // 예외가 필터를 뚫고 올라가면 컨테이너가 500으로 바꾸는 것은 이 계측보다 나중이다 -
        // 상태만 보고 세면 가장 심한 실패가 성공으로 집계된다.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v0/sessions/s_1/result");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> filter.doFilter(request, response, (req, res) -> {
                    throw new IllegalStateException("컨트롤러 밖에서 터진 오류");
                }));

        assertEquals("컨트롤러 밖에서 터진 오류", thrown.getMessage(), "예외는 그대로 올라가야 한다");
        assertEquals(200, response.getStatus(), "이 시점의 상태는 아직 컨테이너가 바꾸기 전이다");
        assertEquals(1, requestCount());
        assertEquals(1, errorCount("5xx"));
        assertEquals(0, errorCount("4xx"));
    }

    @Test
    void 백분위_게이지가_등록된다() throws Exception {
        // CloudWatch 레지스트리는 Timer의 백분위를 스스로 내보내지 않는다 - 이 게이지가 없으면
        // 대시보드의 P95가 영영 비어 있다 (ServiceMetrics 참고).
        call("GET", "/v0/sessions/s_1/result", 200);

        assertNotNull(registry.find(ServiceMetrics.HTTP_REQUESTS + ".percentile")
                .tag("phi", String.valueOf(ServiceMetrics.PERCENTILE)).gauge(),
                "phi=0.95 백분위 게이지가 있어야 한다");
        assertTrue(registry.get(ServiceMetrics.HTTP_REQUESTS + ".percentile")
                .tag("phi", String.valueOf(ServiceMetrics.PERCENTILE)).gauge().value() >= 0);
    }

    private void call(String method, String uri, int status) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        filter.doFilter(request, response, new MockFilterChain());
    }

    private long requestCount() {
        return Optional.ofNullable(registry.find(ServiceMetrics.HTTP_REQUESTS).timer())
                .map(timer -> timer.count()).orElse(0L);
    }

    private double errorCount(String status) {
        return counterValue(registry.find(ServiceMetrics.HTTP_ERRORS).tag("status", status).counter());
    }

    private double pollingCount(String endpoint) {
        return counterValue(registry.find(ServiceMetrics.HTTP_POLLING).tag("endpoint", endpoint).counter());
    }

    private static double counterValue(Counter counter) {
        return counter == null ? 0 : counter.count();
    }
}
