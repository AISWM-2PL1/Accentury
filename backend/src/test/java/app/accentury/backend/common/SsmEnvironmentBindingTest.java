package app.accentury.backend.common;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SSM 파라미터 이름이 실제로 Spring 프로퍼티에 바인딩되는지 (KAN-129).
 * <p>
 * 배포에서 값은 SSM 파라미터 이름 그대로의 환경 변수(예: {@code ACCENTURY_TRUSTEDPROXIES})로
 * 들어오고, Spring relaxed binding이 그것을 {@code accentury.trusted-proxies}로 읽는다. 이 이름이
 * 코드에는 가드의 오류 메시지 문자열로만 있었기 때문에, Terraform 쪽 이름이 한 글자 틀려도 테스트는
 * 전부 통과했다 (PR 리뷰). 여기서는 두 가지를 못박는다: 가드가 정본으로 든 이름이 환경 변수 형태로
 * 주어졌을 때 의도한 프로퍼티에 실제로 닿는 것, 그리고 Terraform {@code modules/config}가 만드는
 * 이름 집합이 그 정본과 같은 것.
 */
class SsmEnvironmentBindingTest {

    /** Terraform이 만드는 파라미터 이름 - `"${var.ssm_prefix}/NAME"` 자리. */
    private static final Pattern TERRAFORM_NAME = Pattern.compile("\\$\\{var\\.ssm_prefix}/([A-Z0-9_]+)\"");

    /** 가드 정본 밖에서 Terraform이 추가로 만드는 이름 - 프로파일 스위치는 검증 대상이 아니라 검증을 켜는 값이다. */
    private static final Set<String> TERRAFORM_ONLY = Set.of("SPRING_PROFILES_ACTIVE");

    /** 가드 정본에는 있지만 Terraform이 만들지 않는 이름 - 자격 증명은 Secrets Manager에서 온다. */
    private static final Set<String> GUARD_ONLY = Set.of(
            DeploymentConfigGuard.DATASOURCE_USERNAME.ssmName(),
            DeploymentConfigGuard.DATASOURCE_PASSWORD.ssmName());

    @Test
    void SSM_이름_그대로의_환경_변수가_의도한_프로퍼티에_바인딩된다() {
        Map<String, Object> ssm = new LinkedHashMap<>();
        ssm.put("SPRING_PROFILES_ACTIVE", "deploy");
        ssm.put(DeploymentConfigGuard.DATASOURCE_URL.ssmName(),
                "jdbc:aws-wrapper:postgresql://db.internal:5432/accentury?secretsManagerSecretId=arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:rds!db-x");
        ssm.put(DeploymentConfigGuard.AI_BASE_URL.ssmName(), "http://ai:8000");
        ssm.put(DeploymentConfigGuard.TRUSTED_PROXIES.ssmName(), "10.1.0.0/16");
        ssm.put(DeploymentConfigGuard.ADMIN_TOKEN.ssmName(), "0123456789abcdef0123456789abcdef");
        ssm.put(DeploymentConfigGuard.WEB_TEST_URL.ssmName(), "https://staging.accentury.app/t?c=kko_share");

        // OS 환경 변수와 같은 종류의 소스다 - 진짜 셸 값보다 앞에 둔다. 이름이 "-systemEnvironment"로
        // 끝나야 Boot가 환경 변수용 이름 규칙(대시 제거)을 적용한다 - 타입만 맞고 이름이 다르면 일반
        // 소스로 취급해 ACCENTURY_ANALYSIS_AIBASEURL을 못 찾는다. 배포의 진짜 소스 이름은 "systemEnvironment"다.
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("ssm-systemEnvironment", ssm));

        assertEquals("deploy", String.join(",", environment.getActiveProfiles()));
        // Environment.getProperty가 아니라 Binder로 읽는다. 전자는 대시를 밑줄로 바꿀 뿐 제거하지 않아
        // ACCENTURY_ANALYSIS_AIBASEURL을 못 찾는다 - @ConfigurationProperties와 가드가 쓰는 경로는 Binder다.
        // 이 테스트의 첫 버전이 getProperty로 검사하다가 가드의 같은 실수를 드러냈다 (2026-08-26).
        Binder binder = Binder.get(environment);
        for (DeploymentConfigGuard.SsmName name : DeploymentConfigGuard.SSM_NAMES) {
            if (ssm.containsKey(name.ssmName())) {
                assertEquals(ssm.get(name.ssmName()), binder.bind(name.property(), String.class).get(), name.label());
            }
        }
        // 목록 프로퍼티는 쉼표 한 줄이 원소로 갈라져야 한다 (ClientIps가 List<String>으로 받는다).
        assertEquals(List.of("10.1.0.0/16"),
                binder.bind(DeploymentConfigGuard.TRUSTED_PROXIES.property(), Bindable.listOf(String.class)).get());
        assertEquals(List.of(), DeploymentConfigGuard.missing(environment));
    }

    @Test
    void Terraform_config_모듈이_만드는_이름이_가드_정본과_같다() throws IOException {
        // backend/는 독립 Gradle 프로젝트라 작업 디렉터리가 backend/다. 모노레포 밖(이미지 빌드 등)에서는 건너뛴다.
        Path main = Path.of("..", "infra", "modules", "config", "main.tf");
        assumeTrue(Files.exists(main), "infra/modules/config/main.tf 없음 - 모노레포 밖 실행");

        Set<String> terraform = new TreeSet<>();
        Matcher matcher = TERRAFORM_NAME.matcher(Files.readString(main));
        while (matcher.find()) {
            terraform.add(matcher.group(1));
        }

        Set<String> expected = DeploymentConfigGuard.SSM_NAMES.stream()
                .map(DeploymentConfigGuard.SsmName::ssmName)
                .filter(name -> !GUARD_ONLY.contains(name))
                .collect(Collectors.toCollection(TreeSet::new));
        expected.addAll(TERRAFORM_ONLY);

        assertEquals(expected, terraform, "Terraform 파라미터 이름과 backend 정본이 어긋난다");
    }
}
