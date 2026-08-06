package app.accentury.backend.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * 서비스 전역 설정 - 활성 테스트·점수 버전과 세션 정책.
 * <p>
 * 세션은 생성 시점의 {@code testVersion}·{@code scoreVersion}에 고정된다 (API 명세서 §5.4).
 * 프로토타입에서는 활성 버전을 이 설정 파일로 관리하고,
 * 버전 발행·활성 전환(KAN-26)이 들어오면 DB 관리로 옮긴다.
 *
 * @param testVersion  활성 테스트 정의 버전 (예: gn-2026.08.1)
 * @param scoreVersion 활성 점수 버전 - 집계식·등급 경계의 기준 (sv-0.3, KAN-21)
 * @param session      익명 세션 정책 (KAN-9)
 * @param analysis     분석 상태 폴링 정책 (KAN-23, KAN-24)
 * @param upload       음성 업로드 요청 제한 (KAN-23)
 * @param cors         웹 테스트 CORS allowlist (KAN-23, KAN-31)
 */
@ConfigurationProperties(prefix = "accentury")
public record AccenturyProperties(String testVersion, String scoreVersion, Session session,
                                  @DefaultValue Analysis analysis, @DefaultValue Upload upload,
                                  @DefaultValue Cors cors) {

    /**
     * @param ttl 세션 토큰 수명 - 테스트 소요 5분의 여유 배수인 30분 (§2.1·§7)
     */
    public record Session(Duration ttl) {
    }

    /**
     * @param pollAfterMs 다음 상태 조회까지 클라이언트가 기다릴 시간 - 서버가 통제하고,
     *                    부하 상승 시 값을 올려 폴링 압력을 줄인다 (§5.3)
     * @param retention   분석 작업 보존 기간 - 세션·결과와 같은 24시간 (§5.5)
     */
    public record Analysis(@DefaultValue("800") long pollAfterMs,
                           @DefaultValue("24h") Duration retention) {
    }

    /**
     * @param rateLimitPerMinute IP당 분당 업로드 허용 횟수 (§2.5, NFR-SC-04).
     *                           임계치는 부하 테스트 후 확정한다 (§7, KAN-28)
     */
    public record Upload(@DefaultValue("30") int rateLimitPerMinute) {
    }

    /**
     * @param allowedOrigins 스탠드얼론 웹 테스트(KAN-31) 오리진 allowlist (§2.5).
     *                       비어 있으면 교차 출처 요청을 허용하지 않는다
     */
    public record Cors(@DefaultValue List<String> allowedOrigins) {
    }
}
