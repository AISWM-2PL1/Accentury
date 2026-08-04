package app.accentury.backend.session;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세션 토큰의 보안 시맨틱 검증 (KAN-9 AC, API 명세서 §2.1).
 */
@SpringBootTest
class SessionServiceTest {

    @Autowired
    private SessionService service;

    @Autowired
    private TestSessionRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    // === §2.1 - 토큰은 원문이 아닌 해시로 저장한다 ===

    @Test
    void 토큰은_원문이_아니라_SHA256_해시로_저장된다() {
        SessionResponse created = service.create(null);

        TestSession stored = repository.findById(created.sessionId()).orElseThrow();
        assertNotEquals(created.sessionToken(), stored.tokenHash());
        assertEquals(64, stored.tokenHash().length());
        assertEquals(SessionTokens.hash(created.sessionToken()), stored.tokenHash());
    }

    @Test
    void 발급된_토큰으로_자기_세션을_인증할_수_있다() {
        SessionResponse created = service.create(null);

        TestSession session = service.authenticate(created.sessionId(), created.sessionToken());

        assertEquals(created.sessionId(), session.id());
    }

    // === KAN-9 AC - 세션 토큰으로 다른 세션 데이터에 접근할 수 없다 ===

    @Test
    void 다른_세션의_토큰으로는_접근할_수_없다() {
        SessionResponse mine = service.create(null);
        SessionResponse others = service.create(null);

        ApiException e = assertThrows(ApiException.class,
                () -> service.authenticate(mine.sessionId(), others.sessionToken()));

        // §2.1 - 경로의 sessionId와 토큰의 세션 불일치는 403
        assertEquals(ErrorCode.SESSION_FORBIDDEN, e.code());
    }

    @Test
    void 모르는_토큰은_SESSION_EXPIRED다() {
        SessionResponse created = service.create(null);

        ApiException e = assertThrows(ApiException.class,
                () -> service.authenticate(created.sessionId(), "st_never-issued-token"));

        // 만료 후 삭제된 토큰과 구분하지 않는다 - 존재 여부 노출은 추측 단서가 된다
        assertEquals(ErrorCode.SESSION_EXPIRED, e.code());
    }

    @Test
    void 만료된_세션은_SESSION_EXPIRED다() {
        TestSession expired = saveSessionExpiredAt(Instant.now().minus(1, ChronoUnit.MINUTES), "st_expired_1");

        ApiException e = assertThrows(ApiException.class,
                () -> service.authenticate(expired.id(), "st_expired_1"));

        assertEquals(ErrorCode.SESSION_EXPIRED, e.code());
    }

    // === §2.1 - expires_at + 주기 삭제로 TTL 구현 ===

    @Test
    void 주기_삭제는_만료_세션만_지운다() {
        TestSession expired = saveSessionExpiredAt(Instant.now().minus(1, ChronoUnit.MINUTES), "st_expired_2");
        SessionResponse alive = service.create(null);

        service.purgeExpired();

        assertTrue(repository.findById(expired.id()).isEmpty(), "만료 세션은 삭제되어야 한다");
        assertTrue(repository.findById(alive.sessionId()).isPresent(), "유효 세션은 남아야 한다");
    }

    // === KAN-9 AC - 사용자 계정·광고 식별자를 저장하지 않는다 ===

    @Test
    void 세션에는_개인_식별_컬럼이_없다() {
        // 엔티티에 컬럼을 추가하면 이 목록도 갱신해야 한다 - 개인 식별 정보가
        // 슬쩍 들어오는 것을 리뷰가 아니라 테스트가 막는다
        Set<String> allowed = Set.of("id", "tokenHash", "testVersion", "scoreVersion",
                "platform", "appVersion", "campaignToken", "createdAt", "expiresAt");

        Set<String> actual = Stream.of(TestSession.class.getDeclaredFields())
                .map(Field::getName)
                .filter(name -> !name.startsWith("$"))   // 프록시·계측 필드 제외
                .collect(Collectors.toSet());

        assertEquals(allowed, actual);
    }

    private TestSession saveSessionExpiredAt(Instant expiresAt, String token) {
        return repository.save(new TestSession(
                SessionTokens.newSessionId(), SessionTokens.hash(token),
                "gn-2026.08.1", "sv-0.3", null, null, null,
                expiresAt.minus(30, ChronoUnit.MINUTES), expiresAt));
    }
}
