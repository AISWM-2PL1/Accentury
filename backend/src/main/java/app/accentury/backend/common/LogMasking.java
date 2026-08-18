package app.accentury.backend.common;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로그에 나가면 안 되는 값의 마스킹 규칙 (API 명세서 §2.6, NFR-SC-07, KAN-28 AC).
 * <p>
 * 코드가 토큰과 오디오를 로그에 넣지 않는 것이 1차 방어이고, 이 규칙은 그 규약이
 * 깨졌을 때를 위한 마지막 관문이다 - 사람이 실수로 넣든, 프레임워크나 라이브러리가
 * 예외 메시지에 요청 헤더를 끼워 넣든 출력 직전에 지운다. 로그 한 줄과 스택트레이스
 * 양쪽에 적용된다 ({@link MaskingMessageConverter}, {@link MaskingThrowableConverter}).
 * <p>
 * 마스킹은 지우는 쪽으로 판단한다 - 가려도 잃는 것은 디버깅 편의뿐이지만,
 * 흘리면 세션 탈취(토큰)나 원본 음성 유출(§5.5의 즉시 파기 원칙 위반)이 된다.
 */
public final class LogMasking {

    /**
     * {@code Authorization: Bearer ...} 형태 - 헤더 이름 없이 값만 찍힌 경우도 걸린다.
     * <p>
     * 값의 끝을 {@code \S+}가 아니라 구분자 목록으로 잡는 것은 의도다 - JSON 로그에서
     * {@code "Bearer abc"}의 닫는 따옴표까지 먹으면 마스킹이 줄을 깨뜨린다.
     */
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+(?!\\*\\*\\*)[^\\s\",;}]+");

    /**
     * 세션 토큰 원문 - {@code st_} + base64url (§2.1, {@code SessionTokens}).
     * 접두사만 남겨 "토큰이 있었다"는 사실은 추적할 수 있게 한다.
     */
    private static final Pattern SESSION_TOKEN = Pattern.compile("\\bst_[A-Za-z0-9_-]{8,}");

    /**
     * {@code Authorization} 헤더 값 전체 - <b>스킴을 가리지 않는다</b>.
     * <p>
     * {@link #BEARER}만 두면 {@code Basic}, {@code Digest}, 사설 스킴의 자격증명이 그대로
     * 남는다. 프레임워크가 예외 메시지에 헤더를 통째로 끼워 넣는 것이 이 층이 막으려는
     * 실수인데, 그 헤더가 Bearer라는 보장이 없다 (KAN-28 AC).
     * <p>
     * 스킴 단어는 남긴다 - 무엇이 실렸었는지가 인증 버그를 좁히는 단서이고, 스킴만으로는
     * 아무도 사칭할 수 없다. 따옴표로 열린 값과 아닌 값을 나눠 받는 이유는 {@link #NAMED_SECRET}과 같다.
     */
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)\\b(authorization)\\b(\"?\\s*[=:]\\s*)"
                    + "(?:\"([^\"\\r\\n]*)\"|([^\\s\",;}]+)(?:[ \\t]+([^\\s\",;}]+))?)");

    /**
     * {@code sessionToken=...} / {@code "sessionToken": "..."} 처럼 이름이 붙어 나오는 값.
     * 토큰 형식이 바뀌어도(발급 규칙 변경) 이름 쪽에서 한 번 더 걸린다.
     * <p>
     * 관리자 토큰(KAN-106)은 네 갈래 이름으로 새어 나올 수 있어 전부 넣는다 - HTTP 헤더
     * {@code X-Admin-Token}(프레임워크가 예외 메시지에 헤더를 실을 때), 설정 키
     * {@code admin-token}(설정 덤프), 바인딩된 필드 {@code adminToken}(객체 toString),
     * 그리고 환경 변수 {@code ACCENTURY_ANALYTICS_ADMINTOKEN}/{@code ..._ADMIN_TOKEN}
     * (application.yml이 권하는 주입 경로라 환경 덤프에 이 철자로 나온다 - 밑줄이 단어
     * 문자여서 {@code \b}가 이름 중간에서 성립하지 않으므로, 전체 철자를 넣지 않으면
     * 앞의 세 이름 어느 것에도 걸리지 않는다, 2026-08-17 리뷰).
     * {@code Authorization}과 같은 등급의 자격증명인데 {@link #AUTHORIZATION}은 이름으로
     * 잡으므로 걸리지 않는다 - 새 시크릿 헤더를 늘리면 여기도 같이 늘려야 한다.
     * <p>
     * 따옴표로 열린 값은 <b>닫는 따옴표까지</b> 통째로 받는다 - 공백을 만나면 멈추게 두면
     * {@code "opaque value"} 같은 값의 뒷부분이 로그에 그대로 남고, 열린 따옴표만 닫혀
     * JSON 한 줄이 깨진다. 따옴표가 없으면 예전처럼 공백에서 끊는다 -
     * {@code sessionToken=abc itemId=v1}에서 뒤 필드까지 먹으면 안 된다.
     */
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)\\b(sessionToken|X-Admin-Token|adminToken|admin-token|ACCENTURY_ANALYTICS_ADMIN_?TOKEN)\\b"
                    + "(\"?\\s*[=:]\\s*)(?:\"([^\"\\r\\n]*)\"|([^\\s\",;}]+))");

    /**
     * 오디오처럼 긴 이진 덩어리 - base64나 hex로 찍힌 200자 이상의 연속 블록.
     * 정상 로그에 이만한 길이의 연속 문자열은 없다 (UUID 36자, correlation ID 66자,
     * guideF0 배열은 숫자와 쉼표가 섞여 걸리지 않는다).
     */
    private static final Pattern LONG_BLOB = Pattern.compile("[A-Za-z0-9+/_-]{200,}={0,2}");

    /**
     * {@code byte[]}를 그대로 로그에 넘겼을 때의 표현 - {@code [82, 73, 70, 70, ...]}
     * (SLF4J와 {@code Arrays.toString}이 만드는 형태, Codex sol 리뷰 P1).
     * <p>
     * 이 마스킹 층이 막으려는 실수가 정확히 이것인데, base64가 아니라 십진수와 쉼표라
     * {@link #LONG_BLOB}에 걸리지 않는다 - 원본 파형이 그대로, 게다가 되돌릴 수 있는
     * 형태로 남는다. 정수 21개 이상이 이어지는 목록만 지우므로 문항 ID 목록
     * ({@code missingItems=[v5, w4]})이나 소수로 된 guideF0 값은 건드리지 않는다.
     */
    private static final Pattern NUMERIC_BLOB = Pattern.compile(
            "\\[\\s*-?\\d{1,3}(?:\\s*,\\s*-?\\d{1,3}){20,}\\s*\\]");

    private LogMasking() {
    }

    /** 로그로 나갈 문자열에서 토큰과 이진 덩어리를 지운다. null과 빈 문자열은 그대로 */
    public static String mask(String text) {
        if (text.isEmpty()) {
            return text;
        }
        String masked = BEARER.matcher(text).replaceAll("Bearer ***");
        masked = AUTHORIZATION.matcher(masked).replaceAll(LogMasking::maskAuthorization);
        masked = SESSION_TOKEN.matcher(masked).replaceAll("st_***");
        masked = NAMED_SECRET.matcher(masked).replaceAll(matchResult -> {
            // 값이 따옴표로 열렸으면 닫아 준다 - 로그 한 줄이 JSON으로 읽히던 것을 깨지 않는다.
            boolean quoted = matchResult.group(3) != null;
            return Matcher.quoteReplacement(matchResult.group(1) + matchResult.group(2)
                    + (quoted ? "\"***\"" : "***"));
        });
        masked = LONG_BLOB.matcher(masked)
                .replaceAll(matchResult -> "***(" + matchResult.group().length() + "자 생략)");
        return NUMERIC_BLOB.matcher(masked)
                .replaceAll(matchResult -> "[***(" + (matchResult.group().split(",").length)
                        + "개 값 생략)]");
    }

    /**
     * {@code Authorization: <스킴> <자격증명>}에서 자격증명만 지운다.
     * <p>
     * 스킴 없이 값 하나만 온 형태({@code authorization=abc123})는 그 값이 곧 자격증명이므로
     * 통째로 지운다. {@link #BEARER}가 이미 {@code Bearer ***}로 만들어 둔 값이 다시
     * 들어와도 같은 결과가 나온다 - 두 규칙이 겹쳐도 스킴 정보가 사라지지 않는다.
     */
    private static String maskAuthorization(MatchResult matchResult) {
        String name = matchResult.group(1);
        String separator = matchResult.group(2);
        String quoted = matchResult.group(3);
        String replacement;
        if (quoted != null) {
            int space = quoted.indexOf(' ');
            String scheme = space > 0 ? quoted.substring(0, space) + " " : "";
            replacement = "\"" + scheme + "***\"";
        } else {
            // 그룹 5가 있으면 그룹 4는 스킴이고, 없으면 그룹 4 자체가 자격증명이다.
            replacement = matchResult.group(5) != null ? matchResult.group(4) + " ***" : "***";
        }
        return Matcher.quoteReplacement(name + separator + replacement);
    }
}
