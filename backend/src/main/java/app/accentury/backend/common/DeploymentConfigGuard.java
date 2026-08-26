package app.accentury.backend.common;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * 배포 프로파일({@code deploy})의 필수 설정 검증 (KAN-129).
 * <p>
 * application.yml은 로컬 전제라 기본값만으로도 서버가 뜬다. 그래서 배포에서 환경 변수를 빼먹으면
 * 에러 없이 <b>조용히</b> 오동작한다 - trusted-proxies가 비면 전원이 ALB IP 하나로 묶여 서로의
 * 요청 제한을 깎고(§2.5), ai-base-url이 비면 분석을 전달하지 않는 개발 모드로 뜨며
 * ({@code NoopAnalysisDispatcher}), admin token이 비면 관리자 API가 404다. 헬스체크는 셋 다 UP이라
 * 부하 테스트나 실사용자 불만에서야 드러난다. 배포 프로파일에서는 빠진 값을 <b>전부 나열하고</b>
 * 기동을 세운다.
 * <p>
 * {@link BeanFactoryPostProcessor}로 거는 이유: 일반 빈이면 DataSource와 Flyway가 먼저 뜨다가
 * "connection refused"로 죽어, 정작 원인(빠진 환경 변수)이 그 뒤에 묻힌다. BFPP는 어떤 싱글턴도
 * 만들어지기 전에 등록되고, 이 클래스의 static {@code @Bean} 메서드는 그 등록 시점에 검사를 끝낸다.
 * 반환하는 후처리기 자체는 할 일이 없다.
 * <p>
 * 검사 대상은 application-deploy.yml이 일부러 비워 둔 값과, 기본값이 없어 미설정이 곧 오동작인
 * 값이다. web-test-url은 main에 prod 도메인 기본값이 있어 더 위험하다 - staging이 값 없이 떠도
 * 겉보기에 멀쩡한 채 공유 카드가 prod로 연결된다. 그래서 deploy yml이 비우고 여기서 요구한다.
 * 자산 완결성은 {@code TierAssets}가, 토큰 길이는 {@code AdminAuth}가, 프록시 대역 형식은
 * {@code ClientIps}가 각자 기동 시 검증하므로 여기서는 "있는지"만 본다. 값 자체는 로그에 남기지
 * 않는다 - 시크릿이 섞여 있다.
 */
@Configuration(proxyBeanMethods = false)
@Profile(DeploymentConfigGuard.PROFILE)
class DeploymentConfigGuard {

    /**
     * 배포 프로파일 이름. staging과 prod가 같은 이름을 쓴다 - 환경 차이는 SSM에서 오는 환경 변수
     * 값뿐이어야 하고(KAN-140), 환경 이름을 프로파일로 쓰면 환경별 yml이 생길 여지가 남는다.
     */
    static final String PROFILE = "deploy";

    /**
     * JDBC URL에 이 파라미터가 있으면 자격 증명은 AWS Advanced JDBC Wrapper의 awsSecretsManager
     * 플러그인이 Secrets Manager에서 읽는다 (application-deploy.yml). 그 경우 username과 password는
     * 비어 있는 것이 맞다 - RDS 관리형 시크릿은 7일마다 회전되므로 값을 복사해 두면 끊긴다.
     */
    private static final String SECRETS_MANAGER_PARAM = "secretsManagerSecretId=";

    @Bean
    static BeanFactoryPostProcessor deploymentConfigGuardProcessor(Environment environment) {
        requireComplete(environment);
        return beanFactory -> {
            // 검사는 빈 등록 시점에 이미 끝났다.
        };
    }

    /**
     * @throws IllegalStateException 빠진 설정이 하나라도 있을 때 - 메시지에 전부 나열한다.
     */
    static void requireComplete(Environment environment) {
        List<String> missing = missing(environment);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("배포 프로파일(" + PROFILE + ") 필수 설정 누락 "
                    + missing.size() + "건 - SSM /accentury/{env}/ 아래 파라미터를 확인한다: "
                    + String.join(", ", missing));
        }
    }

    /** 빠진 설정의 목록 - "프로퍼티 (SSM 파라미터 이름)" 형태. 비어 있으면 통과다. */
    static List<String> missing(Environment environment) {
        List<String> missing = new ArrayList<>();

        String url = environment.getProperty("spring.datasource.url", "");
        if (url.isBlank()) {
            missing.add("spring.datasource.url (SPRING_DATASOURCE_URL)");
        } else if (!url.contains(SECRETS_MANAGER_PARAM) && !hasCredentials(environment)) {
            missing.add("DB 자격 증명 - URL의 " + SECRETS_MANAGER_PARAM + " 또는 spring.datasource.username/password"
                    + " (SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD)");
        }
        if (isBlank(environment, "accentury.analysis.ai-base-url")) {
            missing.add("accentury.analysis.ai-base-url (ACCENTURY_ANALYSIS_AIBASEURL)");
        }
        // 목록이라 단순 getProperty로는 못 읽는다 - yml의 배열과 환경 변수의 쉼표 한 줄을 똑같이 받는다.
        List<String> trustedProxies = Binder.get(environment)
                .bind("accentury.trusted-proxies", Bindable.listOf(String.class))
                .orElse(List.of());
        if (trustedProxies.stream().allMatch(String::isBlank)) {
            missing.add("accentury.trusted-proxies (ACCENTURY_TRUSTEDPROXIES)");
        }
        if (isBlank(environment, "accentury.admin.token")) {
            missing.add("accentury.admin.token (ACCENTURY_ADMIN_TOKEN)");
        }
        if (isBlank(environment, "accentury.result.web-test-url")) {
            missing.add("accentury.result.web-test-url (ACCENTURY_RESULT_WEBTESTURL)");
        }
        return missing;
    }

    private static boolean hasCredentials(Environment environment) {
        return !isBlank(environment, "spring.datasource.username")
                && !isBlank(environment, "spring.datasource.password");
    }

    private static boolean isBlank(Environment environment, String key) {
        return environment.getProperty(key, "").isBlank();
    }
}
