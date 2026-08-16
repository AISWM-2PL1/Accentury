package app.accentury.backend.common;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * API 요청 제한 정책의 한 자리 (API 명세서 §2.5, NFR-SC-04, KAN-28).
 * <p>
 * 명세의 "IP 단위 + 세션 단위 이중 제한"을 범위(scope)별 한도로 편다 - 판정 로직은
 * {@link FixedWindowRateLimiter} 하나이고, 여기는 어떤 경로를 무엇으로 세는지와
 * 한도만 정한다. 흩어져 있던 업로드(KAN-23)와 완료(KAN-16) 제한도 여기로 모았다:
 * 제한 축이 다섯이 되면서 범위마다 클래스와 정리 스케줄을 하나씩 두면 스케줄러 풀만
 * 잠식하고 정책을 한눈에 볼 수 없기 때문이다.
 *
 * <h4>키 선택 규칙</h4>
 * <ul>
 *   <li>인증 없는 경로(세션 생성)는 <b>IP</b>가 유일한 키다 - 계정이 없다.</li>
 *   <li>인증 뒤 경로는 <b>세션</b>이 키다 - NAT 뒤의 정상 응시자들이 서로의 한도를
 *       깎으면 안 된다.</li>
 *   <li>업로드만 둘 다다 - 본문이 큰 유일한 경로라 파싱 비용을 IP로 먼저 끊고
 *       (multipart 해석 전 {@code UploadRateLimitFilter}), 인증 뒤에 세션으로 다시 센다.</li>
 * </ul>
 * <p>
 * 프로토타입은 인메모리로 충분하다 - 다중 인스턴스 공유 저장소(Redis)는 세션 저장소를
 * 옮기는 시점(§2.1)과 같이 간다. 임계치는 부하 테스트 후 확정한다 (§7, KAN-40).
 */
@Component
public class RateLimits {

    /** 제한 축 - 이름이 곧 "무엇을 무엇으로 세는가"다 */
    public enum Scope {
        /** {@code POST /v0/sessions} - IP당 (인증 없는 유일한 쓰기 경로, §3.1) */
        SESSION_CREATE,
        /** 음성 업로드 - IP당 (§3.3, multipart 해석 전에 집행) */
        VOICE_UPLOAD_IP,
        /** 음성 업로드 - 세션당 (§3.3, 인증 뒤 집행) */
        VOICE_UPLOAD_SESSION,
        /** 어휘 답안 - 세션당 (§3.5) */
        VOCAB_ANSWER,
        /** 완료 폴링 - 세션당 (§3.6) */
        COMPLETE
    }

    private final Map<Scope, FixedWindowRateLimiter> limiters;

    @Autowired
    public RateLimits(AccenturyProperties properties) {
        this(limitsFrom(properties), Clock.systemUTC());
    }

    RateLimits(Map<Scope, Integer> limitsPerMinute, Clock clock) {
        Map<Scope, FixedWindowRateLimiter> byScope = new EnumMap<>(Scope.class);
        for (Scope scope : Scope.values()) {
            Integer limit = limitsPerMinute.get(scope);
            if (limit == null) {
                // 축을 추가하고 한도를 안 정하면 그 경로만 조용히 무제한이 된다 - 기동 시점에 세운다
                throw new IllegalStateException("요청 제한 한도가 없는 범위: " + scope);
            }
            if (limit < 1) {
                // 없는 한도의 반대쪽 사고다 - 0이면 첫 요청부터 429라 그 경로가 통째로 막힌다.
                // 한도가 네 개 설정 섹션에 흩어져 있어(§2.5) 부분만 채운 배포 설정에서 나온다
                throw new IllegalStateException(
                        "요청 제한 한도는 1 이상이어야 한다: " + scope + "=" + limit);
            }
            byScope.put(scope, new FixedWindowRateLimiter(limit, clock));
        }
        this.limiters = Map.copyOf(byScope);
    }

    /** 설정에서 범위별 한도를 뽑는다 - 한도가 어느 설정 키에서 오는지가 여기 한 곳에 있다 */
    private static Map<Scope, Integer> limitsFrom(AccenturyProperties properties) {
        return Map.of(
                Scope.SESSION_CREATE, properties.session().rateLimitPerMinute(),
                Scope.VOICE_UPLOAD_IP, properties.upload().rateLimitPerMinute(),
                Scope.VOICE_UPLOAD_SESSION, properties.upload().sessionRateLimitPerMinute(),
                Scope.VOCAB_ANSWER, properties.vocab().rateLimitPerMinute(),
                Scope.COMPLETE, properties.completion().rateLimitPerMinute());
    }

    /** 한도 초과면 429 {@code RATE_LIMITED} + {@code Retry-After} (§2.2, §2.3) */
    public void check(Scope scope, String key) {
        limiters.get(scope).check(key);
    }

    /**
     * 지나간 윈도우 정리 - 무계정 서비스라 IP와 세션 키가 무한히 쌓이는 것을 막는다.
     * 판정에는 영향이 없다(만료 윈도우는 {@code check}가 이미 무시한다) - 순수한 메모리 관리다.
     */
    @Scheduled(initialDelay = 10, fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    public void evictExpired() {
        limiters.values().forEach(FixedWindowRateLimiter::evictExpired);
    }

    /** 추적 중인 키 수 - 정리가 실제로 맵을 줄이는지 확인하는 관찰점이다 */
    int trackedKeys(Scope scope) {
        return limiters.get(scope).trackedKeys();
    }
}
