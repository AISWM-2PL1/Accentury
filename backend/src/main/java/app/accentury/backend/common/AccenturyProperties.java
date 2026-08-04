package app.accentury.backend.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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
 */
@ConfigurationProperties(prefix = "accentury")
public record AccenturyProperties(String testVersion, String scoreVersion, Session session) {

    /**
     * @param ttl 세션 토큰 수명 - 테스트 소요 5분의 여유 배수인 30분 (§2.1·§7)
     */
    public record Session(Duration ttl) {
    }
}
