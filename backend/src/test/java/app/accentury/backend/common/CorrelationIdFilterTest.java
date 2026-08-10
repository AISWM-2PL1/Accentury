package app.accentury.backend.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * correlation ID 부여 규칙의 실행 가능한 명세 (API 명세서 §2.2, KAN-58 AC
 * "모든 응답에 X-Correlation-Id가 포함된다").
 * <p>
 * 필터 단독 단위 테스트 - 스프링 컨텍스트 없이 실행된다.
 * 실제 MVC 경로에 필터가 끼는 것은 GlobalExceptionHandlerTest가
 * 응답 헤더와 봉투의 correlationId로 함께 검증한다.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    /**
     * 체인 실행 "도중"의 MDC 값을 붙잡아두는 체인 - 필터가 정리한 뒤에는 볼 수 없기 때문
     */
    private static FilterChain capturingChain(AtomicReference<String> mdcDuringChain) {
        return (request, response) -> mdcDuringChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void 헤더가_없으면_새_ID를_발급하고_응답과_MDC에_넣는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(mdcDuringChain));

        String issued = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(issued).startsWith("c_");
        assertThat(mdcDuringChain.get()).isEqualTo(issued); // 컨트롤러와 서비스가 찍는 로그에 같은 ID
    }

    @Test
    void 클라이언트가_보낸_올바른_ID는_그대로_재사용한다() throws Exception {
        // 앱 로그와 서버 로그를 같은 ID로 묶기 위해 (BE→AI 전파, KAN-38)
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "app-req_42.ABC-xyz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(mdcDuringChain));

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("app-req_42.ABC-xyz");
        assertThat(mdcDuringChain.get()).isEqualTo("app-req_42.ABC-xyz");
    }

    @Test
    void 형식을_벗어난_ID는_무시하고_새로_발급한다() throws Exception {
        // 줄바꿈이 들어간 값을 그대로 로그에 쓰면 가짜 로그 줄을 삽입할 수 있다 (로그 위조)
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc\n2026-07-30 INFO 가짜로그");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String issued = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(issued).startsWith("c_");
        assertThat(issued).doesNotContain("가짜로그");
    }

    @Test
    void 최대_64자까지_허용하고_65자부터_거부한다() throws Exception {
        // 경계값 - SAFE_ID 패턴 {1,64}
        MockHttpServletRequest ok = new MockHttpServletRequest();
        ok.addHeader(CorrelationIdFilter.HEADER, "a".repeat(64));
        MockHttpServletResponse okResponse = new MockHttpServletResponse();
        filter.doFilter(ok, okResponse, (req, res) -> { });
        assertThat(okResponse.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("a".repeat(64));

        MockHttpServletRequest tooLong = new MockHttpServletRequest();
        tooLong.addHeader(CorrelationIdFilter.HEADER, "a".repeat(65));
        MockHttpServletResponse tooLongResponse = new MockHttpServletResponse();
        filter.doFilter(tooLong, tooLongResponse, (req, res) -> { });
        assertThat(tooLongResponse.getHeader(CorrelationIdFilter.HEADER)).startsWith("c_");
    }

    @Test
    void 요청이_끝나면_MDC를_정리한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        // 톰캣은 스레드를 재사용한다 - 남아 있으면 다음 요청이 앞 사람 ID를 물려받는다
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void 체인에서_예외가_터져도_MDC는_정리된다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain throwingChain = (req, res) -> {
            throw new ServletException("컨트롤러에서 폭발");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, throwingChain))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull(); // finally 보장
    }

    @Test
    void current는_필터_밖에서_unknown을_반환한다() {
        // GlobalExceptionHandler가 필터를 안 거친 경로에서 불려도 NPE가 나지 않는다
        assertThat(CorrelationIdFilter.current()).isEqualTo("unknown");

        MDC.put(CorrelationIdFilter.MDC_KEY, "c_test");
        try {
            assertThat(CorrelationIdFilter.current()).isEqualTo("c_test");
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
