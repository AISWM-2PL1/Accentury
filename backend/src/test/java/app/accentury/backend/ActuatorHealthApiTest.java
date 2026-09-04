package app.accentury.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ConfigurableApplicationContext;
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
 * <p>
 * readiness와 liveness 그룹은 health 아래 경로다 (KAN-166) - 종료 신호에 readiness만 내려가
 * 집계 health가 503이 되고(로드밸런서가 대상을 뺀다), liveness는 UP으로 남는다(오케스트레이터가
 * 조기에 죽이지 않는다).
 */
@AutoConfigureMockMvc
class ActuatorHealthApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConfigurableApplicationContext context;

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

    @Test
    void 종료_신호에_readiness와_집계_health만_내려가고_liveness는_남는다() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // Boot가 컨텍스트 close 첫 줄에서 발행하는 것과 같은 이벤트다 - 캐시된 컨텍스트를 닫을 수는
        // 없으므로 이벤트만 흉내 내고, 끝나면 되돌린다 (뒤에 도는 테스트가 같은 컨텍스트를 받는다).
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        try {
            mockMvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
            // ALB 대상 그룹이 두드리는 집계 경로가 함께 내려가야 대상이 빠진다.
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"))
                    .andExpect(jsonPath("$.components").doesNotExist());
            mockMvc.perform(get("/actuator/health/liveness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
