package app.accentury.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * actuator 헬스체크 최소 노출 (KAN-131).
 * <p>
 * ALB 대상 그룹이 인증 없이 두드리는 경로라 200과 종합 상태만 내려주고, 그 밖의 actuator
 * 엔드포인트는 web에 올리지 않는다. 노출 폭이 Boot 기본값과 같아도 여기서 계약으로 못박는다 -
 * 의존성 추가나 부트 업그레이드가 노출 폭을 조용히 넓히면 이 테스트가 먼저 깨진다.
 * 나머지 관측성(메트릭 exporter, 대시보드)은 KAN-38 범위다.
 */
@AutoConfigureMockMvc
class ActuatorHealthApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 헬스체크는_인증_없이_종합_상태만_내려준다() throws Exception {
        // 이 컨텍스트는 실제 PostgreSQL(Testcontainers)이 결선돼 있으므로 UP은
        // "DB 연결 포함 종합 판정"이 도는 상태에서 나온 값이다.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // 상세가 새면 내부 구성(DB 존재, 디스크 경로)이 무인증 경로로 드러난다 (티켓 AC).
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void health_외_actuator_엔드포인트는_노출되지_않는다() throws Exception {
        // metrics는 KAN-27이 게이지를 등록해 둔 상태라 특히 - 미터가 있어도 web 노출은 별개다.
        // /actuator 목록 페이지와 /actuator/health/{component} 경로까지 함께 잠근다.
        List<String> hidden = List.of(
                "/actuator",
                "/actuator/health/db",
                "/actuator/metrics",
                "/actuator/env",
                "/actuator/beans",
                "/actuator/info",
                "/actuator/loggers",
                "/actuator/threaddump",
                "/actuator/heapdump");

        for (String path : hidden) {
            mockMvc.perform(get(path))
                    .andExpect(status().isNotFound());
        }
    }
}
