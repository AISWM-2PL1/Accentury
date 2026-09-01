package app.accentury.backend.common;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilterReply;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CloudWatch 내보내기 조립의 두 규칙 (KAN-36) - accentury.* 지표만 나가고, Micrometer 설정 키가
 * Boot 모양의 프로퍼티({@code management.cloudwatch.metrics.export.*})에서 읽힌다.
 */
class CloudWatchMetricsConfigTest {

    @Test
    void 서비스_지표만_내보내고_JVM과_HTTP_지표는_막는다() {
        // 이름마다 월 요금이 붙는다 - 회로 상태와 임시파일 잔존만 올린다.
        assertEquals(MeterFilterReply.NEUTRAL, CloudWatchMetricsConfig.serviceMetricsOnly()
                .accept(id("accentury.ai.circuit.state")));
        assertEquals(MeterFilterReply.NEUTRAL, CloudWatchMetricsConfig.serviceMetricsOnly()
                .accept(id("accentury.upload.temp.files")));
        assertEquals(MeterFilterReply.DENY, CloudWatchMetricsConfig.serviceMetricsOnly()
                .accept(id("jvm.memory.used")));
        assertEquals(MeterFilterReply.DENY, CloudWatchMetricsConfig.serviceMetricsOnly()
                .accept(id("http.server.requests")));
    }

    @Test
    void Micrometer_설정_키를_Boot_모양_프로퍼티에서_읽는다() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.cloudwatch.metrics.export.namespace", "accentury/backend")
                .withProperty("management.cloudwatch.metrics.export.step", "30s");

        CloudWatchConfig config = CloudWatchMetricsConfig.config(environment);

        assertEquals("accentury/backend", config.namespace());
        assertEquals(Duration.ofSeconds(30), config.step());
        // 프로퍼티가 없는 키는 Micrometer 기본값으로 떨어진다 (CloudWatch PutMetricData 상한인 1000).
        assertEquals(1000, config.batchSize());
    }

    private static Meter.Id id(String name) {
        return new Meter.Id(name, Tags.empty(), null, null, Meter.Type.GAUGE);
    }
}
