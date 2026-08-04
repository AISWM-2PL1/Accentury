package app.accentury.backend.session;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 익명 세션의 생성·인증·만료 정리 (KAN-9).
 * <p>
 * 세션 저장소는 PostgreSQL이다 (2026-07-30 확정, §2.1) - {@code expires_at} 컬럼 +
 * 요청 시 만료 검사 + 주기 삭제로 TTL을 구현한다. Redis 전환은 BE 다중 인스턴스
 * 또는 폴링 부하 증가 시점에 검토한다.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final TestSessionRepository repository;
    private final AccenturyProperties properties;

    public SessionService(TestSessionRepository repository, AccenturyProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 새 익명 세션을 만든다. 재응시도 이 호출이며, 이전 세션과 어떤 이력도 연결하지 않는다 (KAN-9 AC).
     */
    public SessionResponse create(@Nullable CreateSessionRequest request) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.session().ttl());
        String sessionId = SessionTokens.newSessionId();
        String token = SessionTokens.newToken();

        CreateSessionRequest.Client client = request != null ? request.client() : null;
        repository.save(new TestSession(
                sessionId,
                SessionTokens.hash(token),
                properties.testVersion(),
                properties.scoreVersion(),
                client != null && client.platform() != null ? client.platform().name() : null,
                client != null ? client.appVersion() : null,
                request != null ? request.campaignToken() : null,
                now,
                expiresAt));

        // 토큰은 로그에 남기지 않는다 (§2.6, NFR-SC-07)
        log.info("세션 생성 sessionId={} platform={} testVersion={}",
                sessionId, client != null ? client.platform() : null, properties.testVersion());

        return new SessionResponse(sessionId, token, properties.testVersion(), properties.scoreVersion(), expiresAt);
    }

    /**
     * 토큰이 해당 세션의 것인지 검증한다. 이후 인증 필요 API(KAN-10·23·24·25)가 공용으로 쓴다.
     * <ul>
     *   <li>모르는 토큰·만료된 토큰 → 401 SESSION_EXPIRED - 이 둘은 어떤 경로로도 구분되면
     *       안 된다. 주기 삭제 전후의 만료 토큰이 다른 응답을 받으면 저장소 상태를 추측하는
     *       단서가 되므로, 만료 검사는 반드시 세션 ID 비교보다 먼저다 (Codex sol 리뷰 P1).</li>
     *   <li>유효한데 다른 세션의 토큰 → 403 SESSION_FORBIDDEN (§2.1 - 경로 {sessionId}와 토큰 세션 불일치)</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public TestSession authenticate(String sessionId, String sessionToken) {
        TestSession session = repository.findByTokenHash(SessionTokens.hash(sessionToken))
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_EXPIRED));
        if (session.isExpired(Instant.now())) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED);
        }
        if (!session.id().equals(sessionId)) {
            throw new ApiException(ErrorCode.SESSION_FORBIDDEN);
        }
        return session;
    }

    /**
     * 만료 세션 주기 삭제 (§2.1). 요청 시 만료 검사가 이미 접근을 막고 있으므로
     * 이 잡은 저장소 크기 관리용이다 - 실패해도 보안에 영향 없다.
     */
    @Scheduled(initialDelay = 10, fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void purgeExpired() {
        long removed = repository.deleteByExpiresAtBefore(Instant.now());
        if (removed > 0) {
            log.info("만료 세션 {}건 삭제", removed);
        }
    }
}
