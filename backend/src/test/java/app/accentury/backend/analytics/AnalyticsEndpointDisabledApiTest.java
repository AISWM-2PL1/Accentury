package app.accentury.backend.analytics;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.common.AdminAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 엔드포인트의 기본 상태 - <b>토큰을 설정하지 않으면 아예 없다</b> (KAN-106, 2026-08-17 확정).
 * <p>
 * 설정을 빼먹어도 열려 있는 경로가 생기지 않게 하는 안전한 기본값이다 (신뢰 프록시 목록과
 * 같은 계열, §2.5). 기본 테스트 프로파일에는 {@code accentury.admin.token}이 없으므로 이
 * 컨텍스트가 곧 운영의 기본 상태다 - "토큰만 비우면 잠긴다"가 아니라 "빈이 뜨지 않는다"까지
 * 확인한다. 버전 관리 엔드포인트(KAN-26)도 같은 토큰 하나에 함께 걸려 있어 여기서 같이 본다.
 */
@AutoConfigureMockMvc
class AnalyticsEndpointDisabledApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void 토큰이_설정되지_않으면_컨트롤러_빈이_없다() {
        assertEquals(0, context.getBeanNamesForType(AnalyticsController.class).length);
        // 인증기 자체가 없으므로 관리자 컨트롤러는 어느 것도 조립될 수 없다 (KAN-26).
        assertEquals(0, context.getBeanNamesForType(AdminAuth.class).length);
    }

    @Test
    void 토큰이_설정되지_않으면_경로가_404다() throws Exception {
        // 다른 없는 경로와 구분되지 않는다 - 있는데 잠겼다는 신호조차 주지 않는다.
        mockMvc.perform(get("/admin/v0/analytics"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/admin/v0/test-definitions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 집계_조회_서비스는_토큰과_무관하게_있다() {
        // 엔드포인트만 조건부다 - 조회 자체는 테스트와 운영 DB 직접 조회의 공통 계산이다.
        assertEquals(1, context.getBeanNamesForType(AnalyticsQueryService.class).length);
    }
}
