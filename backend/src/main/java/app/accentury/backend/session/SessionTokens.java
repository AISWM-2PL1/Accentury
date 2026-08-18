package app.accentury.backend.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 세션 ID와 토큰 생성, 해시 (KAN-9).
 * <p>
 * 토큰은 의미 없는 난수(불투명 토큰)라서 서버 저장소 없이는 아무 정보도 담지 않는다 (§2.1).
 */
final class SessionTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    /** 토큰 난수 길이 - 256bit. 추측 불가능성의 근거 */
    private static final int TOKEN_BYTES = 32;

    private SessionTokens() {
    }

    /** 형식: {@code s_} + UUID (§3.1 예시의 접두사 규칙) */
    static String newSessionId() {
        return "s_" + UUID.randomUUID();
    }

    /** 형식: {@code st_} + base64url 난수 256bit */
    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return "st_" + BASE64_URL.encodeToString(bytes);
    }

    /** 저장과 조회용 SHA-256 해시 (hex 64자). 토큰 원문은 DB에 넣지 않는다 (§2.1). */
    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 지원하지 않는 JVM", e);
        }
    }
}
