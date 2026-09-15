package app.accentury.backend.common;

import app.accentury.backend.observability.ServiceMetrics;
import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilterReply;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CloudWatch 내보내기 조립의 두 규칙 (KAN-36) - accentury.* 지표만 나가고, Micrometer 설정 키가
 * Boot 모양의 프로퍼티({@code management.cloudwatch.metrics.export.*})에서 읽힌다.
 */
class CloudWatchMetricsConfigTest {

    @Test
    void 지표_이름_목록이_전부_내보내기_필터를_통과한다() throws Exception {
        // ServiceMetrics는 대시보드와 경보(infra/modules/monitoring)가 문자열로 적어 두는 이름의
        // 정본이다 (KAN-38). 접두사를 어긴 이름을 하나 더하면 그 지표만 조용히 안 올라가고,
        // 그래프가 빈 이유를 배포 뒤에야 알게 된다 - 목록째로 필터에 태워 막는다.
        int checked = 0;
        for (Field field : ServiceMetrics.class.getDeclaredFields()) {
            if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String name = (String) field.get(null);
            assertEquals(MeterFilterReply.NEUTRAL, CloudWatchMetricsConfig.serviceMetricsOnly().accept(id(name)),
                    field.getName() + "(" + name + ")이 내보내기 필터에 막힌다");
            checked++;
        }
        assertTrue(checked >= 10, "이름 상수를 읽지 못했다 - 필드가 " + checked + "개뿐이다");
    }

    @Test
    void 서비스_지표만_내보내고_JVM과_HTTP_지표는_막는다() {
        // 이름마다 월 요금이 붙는다 - accentury.* 서비스 지표만 올린다 (KAN-36 회로 상태와
        // 임시파일 잔존, KAN-38 관측성 지표).
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

    @Test
    void 배포_프로파일_yml이_레지스트리가_요구하는_키를_전부_준다() throws IOException {
        // 부팅 테스트는 전부 export를 끄고 돈다(올릴 곳도 자격 증명도 없다) - 그래서 키 이름 하나가 어긋나도
        // CI는 초록이고 배포 기동에서야 죽는다 (리뷰). yml 자체를 읽어 조립 경로를 한 번 지나게 한다.
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("deploy", new ClassPathResource("application-deploy.yml"))
                .forEach(environment.getPropertySources()::addLast);

        assertEquals("true", environment.getProperty(CloudWatchMetricsConfig.PROPERTY_PREFIX + "enabled"));
        assertEquals("ap-northeast-2", environment.getRequiredProperty(CloudWatchMetricsConfig.PROPERTY_PREFIX + "region"));
        CloudWatchConfig config = CloudWatchMetricsConfig.config(environment);
        // 네임스페이스는 compute 모듈 IAM 조건과 경보(infra/modules/monitoring)가 보는 이름과 같아야 한다.
        assertEquals("accentury/backend", config.namespace());
        assertEquals(Duration.ofMinutes(1), config.step());
        assertEquals("staging", environment.resolvePlaceholders("${ACCENTURY_ENV:staging}"),
                "env 태그는 기동 스크립트가 넣는 ACCENTURY_ENV 자리표시자다");
    }

    private static Meter.Id id(String name) {
        return new Meter.Id(name, Tags.empty(), null, null, Meter.Type.GAUGE);
    }
}
