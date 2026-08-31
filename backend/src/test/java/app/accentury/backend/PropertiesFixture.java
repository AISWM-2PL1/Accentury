package app.accentury.backend;

import app.accentury.backend.common.AccenturyProperties;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 단위 테스트용 {@link AccenturyProperties} 조립기.
 * <p>
 * 값은 {@code application.yml}의 기본값과 같게 둔다 - 테스트가 실제 배포 설정과 다른
 * 세계에서 돌지 않게 하려는 것이다. 시나리오마다 관심 있는 축만 바꿔 쓴다.
 * <p>
 * 설정 레코드에 필드가 하나 늘 때마다 테스트 여섯 곳을 고치던 중복을 여기로 모았다
 * (KAN-28).
 * <p>
 * 활성 테스트 버전과 점수 버전은 여기 없다 - 설정이 아니라 DB가 정본이다 (KAN-26).
 */
public final class PropertiesFixture {

    private PropertiesFixture() {
    }

    /** 기본값 그대로의 설정 - 관심 있는 축이 따로 없는 테스트가 쓴다. */
    public static AccenturyProperties defaults() {
        return properties(analysis(), result(), List.of());
    }

    /** 분석 정책만 바꾼 설정 - 폴링 간격(KAN-24)과 디스패처 조립(KAN-24, 28) 검증이 쓴다. */
    public static AccenturyProperties withAnalysis(AccenturyProperties.Analysis analysis) {
        return properties(analysis, result(), List.of());
    }

    /** 결과 자산만 바꾼 설정 - 등급 자산 기동 검증(KAN-25)이 쓴다. */
    public static AccenturyProperties withResult(AccenturyProperties.Result result) {
        return properties(analysis(), result, List.of());
    }

    /** 신뢰 프록시만 바꾼 설정 - 요청 제한 기준 IP 판정(KAN-28)이 쓴다. */
    public static AccenturyProperties withTrustedProxies(List<String> trustedProxies) {
        return properties(analysis(), result(), trustedProxies);
    }

    /** application.yml 기본값 그대로의 분석 정책 */
    public static AccenturyProperties.Analysis analysis() {
        return analysis(30, null, Duration.ofSeconds(60));
    }

    /**
     * @param congestionThreshold 혼잡 판정 기준 (§5.3)
     * @param aiBaseUrl           null이면 전달하지 않는 개발 모드 (§4.1)
     * @param processingTimeout   실행 잔류 한도 (§3.4)
     */
    public static AccenturyProperties.Analysis analysis(int congestionThreshold,
                                                        @Nullable String aiBaseUrl,
                                                        Duration processingTimeout) {
        return analysis(congestionThreshold, aiBaseUrl, processingTimeout, Duration.ofSeconds(90));
    }

    /** @param shutdownBudget 종료 시 실행 중 분석의 완료 대기 상한 (KAN-166) */
    public static AccenturyProperties.Analysis analysis(int congestionThreshold,
                                                        @Nullable String aiBaseUrl,
                                                        Duration processingTimeout,
                                                        Duration shutdownBudget) {
        return new AccenturyProperties.Analysis(800, 3000, congestionThreshold, Duration.ofHours(24),
                processingTimeout, Duration.ofMinutes(5), aiBaseUrl, Duration.ofSeconds(10), 2, 4,
                Duration.ofSeconds(2), 5, Duration.ofSeconds(5), shutdownBudget);
    }

    private static AccenturyProperties.Result result() {
        return new AccenturyProperties.Result(null, Map.of());
    }

    /** 집계 정책만 바꾼 설정 - 일자 경계와 조회 상한(KAN-106) 검증이 쓴다. */
    public static AccenturyProperties withAnalytics(AccenturyProperties.Analytics analytics) {
        return properties(analysis(), result(), List.of(), analytics, admin());
    }

    /** 관리자 토큰만 바꾼 설정 - 운영자 API 인증(KAN-106, KAN-26) 검증이 쓴다. */
    public static AccenturyProperties withAdmin(AccenturyProperties.Admin admin) {
        return properties(analysis(), result(), List.of(), analytics(), admin);
    }

    /** application.yml 기본값 그대로의 집계 정책 (KAN-106) */
    public static AccenturyProperties.Analytics analytics() {
        return new AccenturyProperties.Analytics(ZoneId.of("Asia/Seoul"), 366);
    }

    /** application.yml 기본값 그대로의 관리자 설정 - 토큰은 미설정이 기본이다 (KAN-26). */
    public static AccenturyProperties.Admin admin() {
        return new AccenturyProperties.Admin(null);
    }

    private static AccenturyProperties properties(AccenturyProperties.Analysis analysis,
                                                  AccenturyProperties.Result result,
                                                  List<String> trustedProxies) {
        return properties(analysis, result, trustedProxies, analytics(), admin());
    }

    private static AccenturyProperties properties(AccenturyProperties.Analysis analysis,
                                                  AccenturyProperties.Result result,
                                                  List<String> trustedProxies,
                                                  AccenturyProperties.Analytics analytics,
                                                  AccenturyProperties.Admin admin) {
        return new AccenturyProperties(
                new AccenturyProperties.Session(Duration.ofMinutes(30), 30),
                analysis,
                new AccenturyProperties.Upload(30, 60, null, Duration.ofMinutes(30)),
                new AccenturyProperties.Vocab(60),
                new AccenturyProperties.Completion(120),
                new AccenturyProperties.Cors(List.of()),
                result,
                analytics,
                admin,
                trustedProxies);
    }
}
