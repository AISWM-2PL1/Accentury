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
 * 익명 세션의 생성과 인증, 만료 정리 (KAN-9).
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
     * {@code Authorization: Bearer {token}} 헤더로 인증한다 (§2.1, §2.2) -
     * 인증 필요 API(KAN-23, 24, 15, 16, 25)의 공용 진입점.
     * 헤더 부재나 형식 오류도 401 SESSION_EXPIRED다 - 미인증과 만료를 구분해주지 않는다.
     */
    @Transactional(readOnly = true)
    public TestSession authenticateBearer(String sessionId, @Nullable String authorizationHeader) {
        return authenticate(sessionId, bearerToken(authorizationHeader));
    }

    /**
     * {@code /result} 전용 인증 (KAN-25, 2026-08-14 확정) - 완료된 세션은 만료됐어도
     * 통과시켜 결과 만료 판정(410 RESULT_EXPIRED)이 세션 만료(401)보다 먼저 서게 한다.
     * <p>
     * 완료 시 세션 수명이 결과 수명과 같아지므로({@link TestSession#markCompleted}) 이
     * 완화가 실제로 여는 구간은 만료 후 세션 행이 주기 삭제되기 전까지다 - 삭제된 뒤는
     * 모르는 토큰과 같은 401이고, 그 안내 문구도 410처럼 재응시로 이끈다.
     * <p>
     * {@link #authenticate}의 보안 규칙은 유지한다: 만료 토큰과 모르는 토큰은 구분되면
     * 안 되므로, 만료된 토큰을 다른 세션 경로에 대면 403이 아니라 401이다. 미완료
     * 세션의 만료 검사도 그대로다 - 완화는 완료된 세션의 자기 결과 조회에만 적용된다.
     * <p>
     * 완화 자체가 구분 금지 규칙 위반이라는 지적(Codex sol 리뷰 P1)은 기각한다
     * (2026-08-14 확정 설계) - 구분이 생기는 조합은 자기 sessionId + 자기 토큰뿐이라
     * 제3자의 저장소 상태 탐지에 쓸 수 없고, 소유자에게 드러나는 정보도 "내 결과가
     * 만료됐다"라는 제품 의도(§3.7의 410 안내) 그 자체다.
     */
    @Transactional(readOnly = true)
    public TestSession authenticateBearerForResult(String sessionId, @Nullable String authorizationHeader) {
        TestSession session = repository.findByTokenHash(SessionTokens.hash(bearerToken(authorizationHeader)))
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_EXPIRED));
        if (session.isExpired(Instant.now())
                && !(session.isCompleted() && session.id().equals(sessionId))) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED);
        }
        if (!session.id().equals(sessionId)) {
            throw new ApiException(ErrorCode.SESSION_FORBIDDEN);
        }
        return session;
    }

    /** 헤더에서 Bearer 토큰을 꺼낸다. 부재나 형식 오류는 401 - 미인증과 만료를 구분해주지 않는다 */
    private static String bearerToken(@Nullable String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED);
        }
        return authorizationHeader.substring(7).strip();
    }

    /**
     * 토큰이 해당 세션의 것인지 검증한다.
     * <ul>
     *   <li>모르는 토큰과 만료된 토큰 → 401 SESSION_EXPIRED - 이 둘은 어떤 경로로도 구분되면
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
