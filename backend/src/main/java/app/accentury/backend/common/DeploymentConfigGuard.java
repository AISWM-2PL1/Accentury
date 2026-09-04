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
import java.util.regex.Pattern;

/**
 * 배포 프로파일({@code deploy})의 필수 설정 검증 (KAN-129).
 * <p>
 * application.yml은 로컬 전제라 기본값만으로도 서버가 뜬다. 그래서 배포에서 환경 변수를 빼먹으면
 * 에러 없이 <b>조용히</b> 오동작한다 - trusted-proxies가 비면 전원이 ALB IP 하나로 묶여 서로의
 * 요청 제한을 깎고(§2.5), ai-base-url이 비면 분석을 전달하지 않는 개발 모드로 뜨며
 * ({@code NoopAnalysisDispatcher}), ai-token이 비면 AI가 모든 분석을 401로 끊어 회로가 열리며(KAN-36),
 * admin token이 비면 관리자 API가 404다. 헬스체크는 전부 UP이라
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
 * <p>
 * 프로퍼티와 SSM 파라미터 이름의 대응({@link #SSM_NAMES})은 이 클래스가 정본이다. 이름은 Spring
 * Boot relaxed binding 규칙(점은 밑줄, 대시는 제거, 대문자)을 따르고, Terraform
 * {@code infra/modules/config/main.tf}가 같은 이름을 만든다 - 두 쪽의 일치는
 * {@code SsmEnvironmentBindingTest}가 대조한다. 값 조회는 전부 {@link Binder}로 한다 -
 * {@code Environment.getProperty}는 대시를 밑줄로만 바꾸지 제거하지는 않아서
 * {@code ACCENTURY_ANALYSIS_AIBASEURL}을 못 찾고(빈 값으로 오판), {@code @ConfigurationProperties}가
 * 쓰는 Binder만 그 이름을 읽는다 (이 테스트가 잡은 버그, 2026-08-26).
 */
@Configuration(proxyBeanMethods = false)
@Profile(DeploymentConfigGuard.PROFILE)
class DeploymentConfigGuard {

    /**
     * 배포 프로파일 이름. staging과 prod가 같은 이름을 쓴다 - 환경 차이는 SSM에서 오는 환경 변수
     * 값뿐이어야 하고(KAN-140), 환경 이름을 프로파일로 쓰면 환경별 yml이 생길 여지가 남는다.
     */
    static final String PROFILE = "deploy";

    /** 프로퍼티와 그 값을 싣는 SSM 파라미터(= 컨테이너 환경 변수) 이름. */
    record SsmName(String property, String ssmName) {
        String label() {
            return property + " (" + ssmName + ")";
        }
    }

    static final SsmName DATASOURCE_URL = new SsmName("spring.datasource.url", "SPRING_DATASOURCE_URL");
    static final SsmName DATASOURCE_USERNAME = new SsmName("spring.datasource.username", "SPRING_DATASOURCE_USERNAME");
    static final SsmName DATASOURCE_PASSWORD = new SsmName("spring.datasource.password", "SPRING_DATASOURCE_PASSWORD");
    static final SsmName AI_BASE_URL = new SsmName("accentury.analysis.ai-base-url", "ACCENTURY_ANALYSIS_AIBASEURL");
    static final SsmName AI_TOKEN = new SsmName("accentury.analysis.ai-token", "ACCENTURY_ANALYSIS_AITOKEN");
    static final SsmName TRUSTED_PROXIES = new SsmName("accentury.trusted-proxies", "ACCENTURY_TRUSTEDPROXIES");
    static final SsmName ADMIN_TOKEN = new SsmName("accentury.admin.token", "ACCENTURY_ADMIN_TOKEN");
    static final SsmName WEB_TEST_URL = new SsmName("accentury.result.web-test-url", "ACCENTURY_RESULT_WEBTESTURL");
    static final SsmName ASSET_BASE_URL = new SsmName("accentury.result.asset-base-url", "ACCENTURY_RESULT_ASSETBASEURL");

    /** 배포에서 값이 와야 하는 프로퍼티 전부 (자격 증명 둘은 Secrets Manager URL이면 비어 있어도 된다). */
    static final List<SsmName> SSM_NAMES = List.of(DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD,
            AI_BASE_URL, AI_TOKEN, TRUSTED_PROXIES, ADMIN_TOKEN, WEB_TEST_URL, ASSET_BASE_URL);

    /**
     * JDBC URL에 이 파라미터가 <b>값과 함께</b> 있으면 자격 증명은 AWS Advanced JDBC Wrapper의
     * awsSecretsManager 플러그인이 Secrets Manager에서 읽는다 (application-deploy.yml). 그 경우
     * username과 password는 비어 있는 것이 맞다 - RDS 관리형 시크릿은 7일마다 회전되므로 값을 복사해
     * 두면 끊긴다. {@code secretsManagerSecretId=}처럼 값이 빈 것은 인정하지 않는다 (PR 리뷰).
     */
    private static final Pattern SECRETS_MANAGER_PARAM = Pattern.compile("[?&]secretsManagerSecretId=[^&#]+");

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

        Binder binder = Binder.get(environment);
        String url = value(binder, DATASOURCE_URL.property());
        if (url.isBlank()) {
            missing.add(DATASOURCE_URL.label());
        } else if (!SECRETS_MANAGER_PARAM.matcher(url).find() && !hasCredentials(binder)) {
            missing.add("DB 자격 증명 - URL의 secretsManagerSecretId 값 또는 "
                    + DATASOURCE_USERNAME.label() + "와 " + DATASOURCE_PASSWORD.label());
        }
        if (isBlank(binder, AI_BASE_URL.property())) {
            missing.add(AI_BASE_URL.label());
        }
        // 없으면 헤더 없이 부르고 AI가 전부 401로 끊는다 - 회로가 열린 채 "AI 장애"로 보여 원인이 묻힌다 (KAN-36).
        if (isBlank(binder, AI_TOKEN.property())) {
            missing.add(AI_TOKEN.label());
        }
        // 목록이라 단순 getProperty로는 못 읽는다 - yml의 배열과 환경 변수의 쉼표 한 줄을 똑같이 받는다.
        List<String> trustedProxies = binder
                .bind(TRUSTED_PROXIES.property(), Bindable.listOf(String.class))
                .orElse(List.of());
        if (trustedProxies.stream().allMatch(String::isBlank)) {
            missing.add(TRUSTED_PROXIES.label());
        }
        if (isBlank(binder, ADMIN_TOKEN.property())) {
            missing.add(ADMIN_TOKEN.label());
        }
        if (isBlank(binder, WEB_TEST_URL.property())) {
            missing.add(WEB_TEST_URL.label());
        }
        // 등급 이미지 기준 URL (KAN-132) - web-test-url과 같은 이유로 비운다. main 기본값이 prod 도메인이라
        // staging이 값 없이 뜨면 공유 카드 이미지가 prod 버킷을 가리킨다.
        if (isBlank(binder, ASSET_BASE_URL.property())) {
            missing.add(ASSET_BASE_URL.label());
        }
        return missing;
    }

    private static boolean hasCredentials(Binder binder) {
        return !isBlank(binder, DATASOURCE_USERNAME.property())
                && !isBlank(binder, DATASOURCE_PASSWORD.property());
    }

    private static boolean isBlank(Binder binder, String key) {
        return value(binder, key).isBlank();
    }

    private static String value(Binder binder, String key) {
        return binder.bind(key, String.class).orElse("");
    }
}
