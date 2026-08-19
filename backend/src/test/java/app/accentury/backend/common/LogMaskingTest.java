package app.accentury.backend.common;

import app.accentury.backend.IntegrationTest;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 로그 마스킹 (API 명세서 §2.6, NFR-SC-07, KAN-28 AC - "로그에서 토큰, 원본 음성이 마스킹된다").
 * <p>
 * 규칙 자체와, 그 규칙이 <b>실제 콘솔 어펜더에 걸려 있는지</b>를 함께 본다 - 규칙만
 * 맞고 패턴에 안 걸리면 운영 로그는 그대로 샌다.
 */
class LogMaskingTest extends IntegrationTest {

    private static final String TOKEN = "st_" + "A1b2C3d4E5f6G7h8I9j0KLMNOPqrstuv";

    // === 규칙 ===

    @Test
    void Bearer_토큰을_지운다() {
        String masked = LogMasking.mask("Authorization: Bearer " + TOKEN + " 로 인증 실패");

        assertFalse(masked.contains(TOKEN), masked);
        assertTrue(masked.contains("Bearer ***"), masked);
    }

    @Test
    void 접두사만_남기고_세션_토큰_원문을_지운다() {
        // 헤더 형식이 아니라 값만 찍혀도 걸려야 한다 - "어떤 토큰이었나"는 못 봐도
        // "토큰이 찍혔다"는 추적할 수 있게 접두사는 남긴다.
        String masked = LogMasking.mask("세션 조회 실패 token=" + TOKEN);

        assertFalse(masked.contains(TOKEN), masked);
        assertTrue(masked.contains("st_***"), masked);
    }

    @Test
    void 이름이_붙은_값도_지운다() {
        // 토큰 발급 형식이 바뀌어도 이름 쪽에서 한 번 더 걸린다.
        String masked = LogMasking.mask("""
                {"sessionToken": "opaque-value-1234", "itemId": "v1"}""");

        assertFalse(masked.contains("opaque-value-1234"), masked);
        assertTrue(masked.contains("v1"), "무관한 필드는 그대로여야 한다: " + masked);
    }

    @Test
    void 관리자_토큰도_이름_네_형태_모두에서_지운다() {
        // 관리자 토큰(KAN-106)은 Authorization과 같은 등급의 자격증명인데 헤더 이름이 달라
        // AUTHORIZATION 규칙에 안 걸린다. 프레임워크가 예외 메시지에 헤더를 싣거나
        // 설정이 덤프되면 운영 시크릿이 평문으로 남는 자리다.
        assertEquals("X-Admin-Token: ***",
                LogMasking.mask("X-Admin-Token: s3cr3t-admin-value"),
                "요청 헤더로 찍힌 경우");
        assertEquals("accentury.admin.token=***",
                LogMasking.mask("accentury.admin.token=s3cr3t-admin-value"),
                "설정 키로 찍힌 경우");
        assertEquals("accentury.analytics.admin-token=***",
                LogMasking.mask("accentury.analytics.admin-token=s3cr3t-admin-value"),
                "옛 설정 키로 찍힌 경우 - KAN-26에서 옮기기 전 이름이 남아 있는 배포도 덮는다");
        assertEquals("""
                {"adminToken": "***"}""",
                LogMasking.mask("""
                        {"adminToken": "s3cr3t-admin-value"}"""),
                "바인딩된 필드로 찍힌 경우");
        // 환경 변수 철자는 밑줄이 단어 문자라 \b가 이름 중간에서 성립하지 않는다 -
        // 전체 철자를 패턴에 넣지 않으면 위 세 이름 어느 것에도 걸리지 않는 자리다
        // (2026-08-17 리뷰). application.yml이 권하는 주입 경로가 정확히 이 철자다.
        assertEquals("ACCENTURY_ADMINTOKEN=***",
                LogMasking.mask("ACCENTURY_ADMINTOKEN=s3cr3t-admin-value"),
                "환경 변수(대시 제거형)로 찍힌 경우");
        assertEquals("ACCENTURY_ADMIN_TOKEN=***",
                LogMasking.mask("ACCENTURY_ADMIN_TOKEN=s3cr3t-admin-value"),
                "환경 변수(밑줄 분리형)로 찍힌 경우");
        assertEquals("ACCENTURY_ANALYTICS_ADMIN_TOKEN=***",
                LogMasking.mask("ACCENTURY_ANALYTICS_ADMIN_TOKEN=s3cr3t-admin-value"),
                "옛 환경 변수 철자로 찍힌 경우");
    }

    @Test
    void 값에_공백이_있어도_따옴표_끝까지_지운다() {
        // 공백에서 끊으면 뒷부분이 로그에 그대로 남고, 열린 따옴표만 닫혀 JSON 한 줄이
        // 깨진다 - 마스킹이 유출과 로그 수집 실패를 동시에 만드는 자리다.
        String masked = LogMasking.mask("""
                {"sessionToken": "opaque value with space", "itemId": "v1"}""");

        assertFalse(masked.contains("value with space"), masked);
        assertEquals("""
                {"sessionToken": "***", "itemId": "v1"}""", masked,
                "가린 뒤에도 JSON으로 읽혀야 한다");
    }

    @Test
    void Bearer가_아닌_인증_스킴의_자격증명도_지운다() {
        // 프레임워크가 예외 메시지에 끼워 넣는 헤더가 Bearer라는 보장이 없다 (KAN-28 AC).
        // 스킴만 남기고 자격증명을 지운다 - 스킴은 인증 버그를 좁히는 단서이고 사칭에는 못 쓴다.
        assertEquals("Authorization: Basic ***",
                LogMasking.mask("Authorization: Basic dXNlcjpwYXNzd29yZA=="));
        assertEquals("Authorization: Token ***",
                LogMasking.mask("Authorization: Token SECRETVALUE123"));
        assertEquals("Authorization: Weird ***",
                LogMasking.mask("Authorization: Weird custom-credential-xyz"));
    }

    @Test
    void 두_번_가려도_스킴_정보가_사라지지_않는다() {
        // Bearer 규칙과 Authorization 규칙이 같은 문자열에 겹쳐 걸린다 - 뒤 규칙이 앞
        // 결과를 다시 덮으면 "무엇이 실렸었나"를 알려주는 스킴까지 ***가 된다.
        String once = LogMasking.mask("Authorization: Bearer " + TOKEN);

        assertEquals("Authorization: Bearer ***", once);
        assertEquals(once, LogMasking.mask(once), "이미 가려진 줄은 그대로여야 한다");
    }

    @Test
    void 오디오_같은_긴_이진_덩어리를_지운다() {
        String base64Audio = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAgD4AAAB9AAACABAAZGF0YQAAAAA"
                .repeat(6);
        String masked = LogMasking.mask("업로드 본문: " + base64Audio);

        assertFalse(masked.contains(base64Audio), masked);
        assertTrue(masked.contains("자 생략"), masked);
    }

    @Test
    void byte_배열로_찍힌_오디오도_지운다() {
        // SLF4J에 byte[]를 그대로 넘기면 [82, 73, 70, ...] 십진수 목록이 된다 - base64가
        // 아니라 긴 덩어리 규칙에 걸리지 않고, 되돌릴 수 있는 원본 파형이 그대로 남는다
        // (Codex sol 리뷰 P1).
        byte[] wav = wavHeaderBytes();
        String masked = LogMasking.mask("업로드 audio=" + java.util.Arrays.toString(wav));

        assertFalse(masked.contains("82, 73, 70"), masked);
        assertTrue(masked.contains("개 값 생략"), masked);
    }

    @Test
    void 짧은_숫자_목록은_건드리지_않는다() {
        // 오류 봉투의 문항 목록(§2.3)과 짧은 수치는 디버깅에 필요하다.
        String line = "완료 판정 missingItems=[v5, w4] pendingItems=[v4] scores=[78, 60, 72]";

        assertEquals(line, LogMasking.mask(line));
    }

    @Test
    void 정상_로그는_건드리지_않는다() {
        // 과잉 마스킹은 디버깅을 못 하게 만든다 - 세션 ID(s_), 문항 ID, correlation ID는 남아야 한다.
        String line = "음성 업로드 접수 sessionId=s_2f9c4b1e-77a1-4a8e-9a1f-0a5f2c6d8e31 "
                + "itemId=v3 attempt=2 jobId=a_9d1c2b3a-4e5f-6a7b-8c9d-0e1f2a3b4c5d";

        assertEquals(line, LogMasking.mask(line));
    }

    // === 실제 어펜더에 걸려 있는가 ===

    @Test
    void 콘솔_어펜더가_메시지의_토큰을_지운다() {
        String line = encodeThroughConsoleAppender(
                event("세션 인증 실패 Authorization: Bearer " + TOKEN, null));

        assertFalse(line.contains(TOKEN), line);
        assertTrue(line.contains("Bearer ***"), line);
        assertTrue(line.endsWith(System.lineSeparator()), "줄바꿈이 붙어야 한다: " + line);
    }

    @Test
    void 콘솔_어펜더가_예외_스택트레이스의_토큰도_지운다() {
        // 토큰이 실릴 가능성이 가장 큰 곳이 예외 메시지다 - HTTP 클라이언트와 파서가
        // 요청 헤더 일부를 메시지에 담는다. 메시지만 가리면 절반이다.
        String line = encodeThroughConsoleAppender(event("예상치 못한 오류",
                new IllegalStateException("요청 거절 Bearer " + TOKEN)));

        assertFalse(line.contains(TOKEN), line);
        assertTrue(line.contains("IllegalStateException"), "스택트레이스 자체는 남아야 한다: " + line);
    }

    /** WAV 헤더로 시작하는 더미 오디오 - 로그에 실리면 안 되는 원본 바이트 */
    private static byte[] wavHeaderBytes() {
        byte[] bytes = new byte[64];
        bytes[0] = 82;  // R
        bytes[1] = 73;  // I
        bytes[2] = 70;  // F
        bytes[3] = 70;  // F
        return bytes;
    }

    private static LoggingEvent event(String message, @Nullable Throwable throwable) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        // 실제 로깅 경로와 같은 생성자다 - MDC 스냅샷과 컨텍스트가 채워져야 %X 같은
        // 변환어가 NPE 없이 렌더링된다.
        return new LoggingEvent(ch.qos.logback.classic.Logger.FQCN,
                context.getLogger(LogMaskingTest.class), Level.INFO, message, throwable, null);
    }

    /** 운영에서 실제로 쓰는 콘솔 어펜더의 인코더를 그대로 태운다. */
    private static String encodeThroughConsoleAppender(ILoggingEvent event) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Appender<ILoggingEvent> appender =
                context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
        assertNotNull(appender, "logback-spring.xml이 등록하는 CONSOLE 어펜더가 있어야 한다");
        Encoder<ILoggingEvent> encoder =
                assertInstanceOf(OutputStreamAppender.class, appender).getEncoder();
        assertNotNull(encoder);
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }
}
