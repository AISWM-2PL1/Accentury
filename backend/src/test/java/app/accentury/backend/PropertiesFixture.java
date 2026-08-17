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
 */
public final class PropertiesFixture {

    private PropertiesFixture() {
    }

    /** 활성 버전만 바꾼 설정 - 레지스트리 기동 검증(KAN-10, 21)이 쓴다 */
    public static AccenturyProperties versions(String testVersion, String scoreVersion) {
        return properties(testVersion, scoreVersion, analysis(), result(), List.of());
    }

    /** 분석 정책만 바꾼 설정 - 폴링 간격(KAN-24)과 디스패처 조립(KAN-24, 28) 검증이 쓴다 */
    public static AccenturyProperties withAnalysis(AccenturyProperties.Analysis analysis) {
        return properties("gn-2026.08.1", "sv-0.3", analysis, result(), List.of());
    }

    /** 결과 자산만 바꾼 설정 - 등급 자산 기동 검증(KAN-25)이 쓴다 */
    public static AccenturyProperties withResult(AccenturyProperties.Result result) {
        return properties("gn-2026.08.1", "sv-0.3", analysis(), result, List.of());
    }

    /** 신뢰 프록시만 바꾼 설정 - 요청 제한 기준 IP 판정(KAN-28)이 쓴다 */
    public static AccenturyProperties withTrustedProxies(List<String> trustedProxies) {
        return properties("gn-2026.08.1", "sv-0.3", analysis(), result(), trustedProxies);
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
        return new AccenturyProperties.Analysis(800, 3000, congestionThreshold, Duration.ofHours(24),
                processingTimeout, Duration.ofMinutes(5), aiBaseUrl, Duration.ofSeconds(10), 2, 4,
                Duration.ofSeconds(2), 5, Duration.ofSeconds(5));
    }

    private static AccenturyProperties.Result result() {
        return new AccenturyProperties.Result(null, Map.of());
    }

    /** 집계 정책만 바꾼 설정 - 일자 경계와 조회 상한(KAN-106) 검증이 쓴다 */
    public static AccenturyProperties withAnalytics(AccenturyProperties.Analytics analytics) {
        return properties("gn-2026.08.1", "sv-0.3", analysis(), result(), List.of(), analytics);
    }

    /** application.yml 기본값 그대로의 집계 정책 (KAN-106) - 내부 조회 토큰은 미설정이 기본이다 */
    public static AccenturyProperties.Analytics analytics() {
        return new AccenturyProperties.Analytics(ZoneId.of("Asia/Seoul"), null, 366);
    }

    private static AccenturyProperties properties(String testVersion, String scoreVersion,
                                                  AccenturyProperties.Analysis analysis,
                                                  AccenturyProperties.Result result,
                                                  List<String> trustedProxies) {
        return properties(testVersion, scoreVersion, analysis, result, trustedProxies, analytics());
    }

    private static AccenturyProperties properties(String testVersion, String scoreVersion,
                                                  AccenturyProperties.Analysis analysis,
                                                  AccenturyProperties.Result result,
                                                  List<String> trustedProxies,
                                                  AccenturyProperties.Analytics analytics) {
        return new AccenturyProperties(testVersion, scoreVersion,
                new AccenturyProperties.Session(Duration.ofMinutes(30), 30),
                analysis,
                new AccenturyProperties.Upload(30, 60, null, Duration.ofMinutes(30)),
                new AccenturyProperties.Vocab(60),
                new AccenturyProperties.Completion(120),
                new AccenturyProperties.Cors(List.of()),
                result,
                analytics,
                trustedProxies);
    }
}
