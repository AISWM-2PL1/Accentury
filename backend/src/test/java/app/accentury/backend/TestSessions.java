package app.accentury.backend;

import app.accentury.backend.analytics.Traffic;
import app.accentury.backend.session.TestSession;
import app.accentury.backend.session.TestSessionRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 하위 행(시도, 답안, 결과)만 다루는 테스트를 위한 최소 세션 픽스처 (KAN-123).
 * <p>
 * baseline이 세션 FK를 확정하면서, 예전처럼 부모 없는 session_id로 하위 행을 심는
 * 픽스처는 DB가 거부한다 - API로 세션을 만들 이유가 없는 테스트(보존 정리, 타임아웃,
 * 디스패처)는 이 헬퍼로 부모 행만 채운다.
 */
public final class TestSessions {

    private TestSessions() {
    }

    /**
     * 주어진 id의 부모 세션 행이 있게 만든다 - 이미 있으면 그대로 둔다.
     * 같은 클래스의 테스트 여러 개가 같은 id를 요구해도 안전하다
     * ({@link TestSession}은 Persistable이라 무조건 save하면 두 번째가 중복 INSERT로 죽는다).
     */
    public static TestSession ensure(TestSessionRepository repository, String id) {
        return repository.findById(id).orElseGet(() -> repository.save(bare(id)));
    }

    /** 최소 필드만 채운 세션 - token_hash는 유니크 제약(64자 hex)이 있어 id에서 유도한다. */
    public static TestSession bare(String id) {
        Instant now = Instant.now();
        return new TestSession(id, sha256Hex(id), "gn-2026.08.1", "sv-0.3", 1,
                null, null, null, Traffic.REAL, now, now.plus(Duration.ofMinutes(30)));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
