package app.accentury.backend.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 모든 요청에 correlation ID를 부여하는 필터.
 * <p>
 * App-Backend API 명세서 §2.2 - 클라이언트가 {@code X-Correlation-Id} 헤더를 보내면
 * 그 값을 쓰고, 없으면 새로 만든다. 응답 헤더로 돌려주고, 로그 컨텍스트(MDC)에도 넣어서
 * 이 요청이 남긴 모든 로그 줄을 같은 ID로 추적할 수 있게 한다 (BE→AI 전파, KAN-38).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // 다른 어떤 처리보다 먼저 실행 - 이후 전 구간이 ID를 갖도록
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** 클라이언트가 보낸 ID의 허용 형식 - 영숫자·점·밑줄·하이픈, 최대 64자.
     *  형식 밖의 값(줄바꿈 등)은 로그 위조에 쓰일 수 있어 무시하고 새로 발급한다. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    @NullMarked
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || !SAFE_ID.matcher(correlationId).matches()) {
            correlationId = "c_" + UUID.randomUUID();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId); // 응답 커밋 전에 미리 세팅
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY); // 스레드 재사용 대비 - 반드시 정리
        }
    }

    /** 예외 핸들러 등에서 현재 요청의 correlation ID를 읽을 때 사용 */
    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : "unknown";
    }
}
