package app.accentury.backend.feedback;

import app.accentury.backend.analytics.Traffic;
import app.accentury.backend.common.SsmPlaceholder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 슬랙 메시지 조립과 꺼짐 판단 (KAN-211 2단계).
 * <p>
 * 스프링 컨텍스트 없이 돈다 - 여기서 보는 것은 "무엇을 싣고 무엇을 싣지 않는가"이고, 커밋 뒤에
 * 실제로 불리는지는 {@link FeedbackSlackNotifyApiTest}가 본다. 가장 중요한 단언은 이메일 문자열이
 * 메시지에 없다는 것이다 - 채널은 개발팀 전원이 보고 슬랙 무료 플랜은 지난 메시지를 지우지 않는다.
 */
class FeedbackSlackNotifierTest {

    private static final String WEBHOOK = "https://hooks.slack.com/services/T000/B000/test";
    private static final String EMAIL = "tester@example.com";

    // === 메시지 조립 ===

    @Test
    void 머리_줄에_별점과_스냅샷과_연락처_유무가_들어간다() {
        String message = FeedbackSlackNotifier.message(
                feedback((short) 5, "좋았어요", EMAIL, "android"), "staging.accentury.app");

        assertEquals("[staging.accentury.app] 새 이용 후기 ★5 · 등급 NATIVE · gn-2026.09.3/sv-0.3"
                        + " · android · REAL · 세션 sess_abc · 연락처 있음\n> 좋았어요",
                message);
    }

    @Test
    void 이메일_값은_메시지에_실리지_않는다() {
        // 채널에 한 번 올라간 개인 식별 정보는 되돌릴 방법이 없다 - 유무만 적고 답장할 후기는
        // 세션 id로 DB에서 찾는다.
        String message = FeedbackSlackNotifier.message(
                feedback((short) 3, "연락 주세요 " + EMAIL.replace("@", " 골뱅이 "), EMAIL, "web"),
                "local");

        assertFalse(message.contains(EMAIL), message);
        assertTrue(message.contains("연락처 있음"), message);
    }

    @Test
    void 별점이_없으면_별점_없음이고_연락처가_없으면_없음이다() {
        String message = FeedbackSlackNotifier.message(
                feedback(null, "한 줄만 씁니다", null, "ios"), "local");

        assertTrue(message.contains("새 이용 후기 별점 없음 ·"), message);
        assertFalse(message.contains("★"), message);
        assertTrue(message.contains("연락처 없음"), message);
    }

    @Test
    void 플랫폼이_없으면_web으로_읽는다() {
        // 세션 생성 때 플랫폼을 안 보내는 경로가 웹이다 - 빈 자리를 그대로 두면 읽는 사람이
        // "어디서 쓴 후기인가"를 매번 되묻는다.
        String message = FeedbackSlackNotifier.message(feedback((short) 1, "음", null, null), "local");

        assertTrue(message.contains(" · web · REAL · "), message);
    }

    @Test
    void 본문은_줄마다_인용문_접두가_붙는다() {
        String message = FeedbackSlackNotifier.message(
                feedback((short) 4, "첫 줄\n둘째 줄\n셋째 줄", null, "web"), "local");

        assertTrue(message.endsWith("\n> 첫 줄\n> 둘째 줄\n> 셋째 줄"), message);
    }

    @Test
    void 슬랙_문법_문자_셋을_이스케이프한다() {
        // 이스케이프하지 않으면 후기 본문의 <!channel>이 채널 전체 멘션이 된다 (슬랙 mrkdwn).
        String message = FeedbackSlackNotifier.message(
                feedback((short) 2, "<!channel> a & b > c", null, "web"), "local");

        assertTrue(message.endsWith("\n> &lt;!channel&gt; a &amp; b &gt; c"), message);
        // & 를 먼저 바꾸지 않으면 &lt;의 앰퍼샌드까지 다시 바뀌어 &amp;lt;가 된다.
        assertFalse(message.contains("&amp;lt;"), message);
    }

    @Test
    void 세션_id는_앞_8자만_싣는다() {
        SessionFeedback feedback = new SessionFeedback("fb_1", "sess_0123456789abcdef", "key",
                (short) 5, "좋아요", null, "NATIVE", "gn-2026.09.3", "sv-0.3", "web",
                Traffic.REAL, Instant.now());

        String message = FeedbackSlackNotifier.message(feedback, "local");

        assertTrue(message.contains("세션 sess_012 ·"), message);
        assertFalse(message.contains("sess_0123"), message);
    }

    @Test
    void 합성_트래픽_후기는_그렇게_표시된다() {
        // E2E 스모크(KAN-138)가 남긴 후기를 채널에서 바로 걸러낼 수 있어야 한다.
        SessionFeedback feedback = new SessionFeedback("fb_1", "sess_abc", "key", (short) 5,
                "스모크", null, "NATIVE", "gn-2026.09.3", "sv-0.3", "web",
                Traffic.SYNTHETIC, Instant.now());

        assertTrue(FeedbackSlackNotifier.message(feedback, "local").contains(" · SYNTHETIC · "));
    }

    // === 환경 라벨 ===

    @Test
    void 라벨은_결과_URL의_호스트이고_없으면_local이다() {
        // staging과 prod가 같은 deploy 프로파일을 쓰므로 프로파일로는 구분이 안 된다 - 이미
        // 환경마다 다른 값이 주입되는 결과 URL의 호스트를 쓴다.
        assertEquals("accentury.app",
                FeedbackSlackNotifier.label("https://accentury.app/t?c=kko_share"));
        assertEquals("staging.accentury.app",
                FeedbackSlackNotifier.label("https://staging.accentury.app/t?c=kko_share"));
        assertEquals(FeedbackSlackNotifier.LOCAL_LABEL, FeedbackSlackNotifier.label(null));
        assertEquals(FeedbackSlackNotifier.LOCAL_LABEL, FeedbackSlackNotifier.label("  "));
        // 호스트가 없는 값(설정 실수)에도 기동이 멈추지 않는다 - 알림은 부수 기능이다.
        assertEquals(FeedbackSlackNotifier.LOCAL_LABEL, FeedbackSlackNotifier.label("/t?c=kko_share"));
        assertEquals(FeedbackSlackNotifier.LOCAL_LABEL, FeedbackSlackNotifier.label("h ttp://x"));
    }

    // === 꺼짐 ===

    @Test
    void URL이_없거나_비었거나_자리_표시_값이면_보내지_않는다() {
        for (String url : new String[]{null, "", "   ", SsmPlaceholder.UNSET, " " + SsmPlaceholder.UNSET + " "}) {
            RecordingClient client = new RecordingClient();
            FeedbackSlackNotifier notifier =
                    new FeedbackSlackNotifier(client, new StubRepository(stored()), url, null);

            assertFalse(notifier.enabled(), "url=" + url);
            notifier.onFeedbackSubmitted(new FeedbackSubmitted("fb_1"));
            assertEquals(List.of(), client.posted, "url=" + url);
        }
    }

    @Test
    void 켜져_있으면_저장된_행을_읽어_한_번_보낸다() {
        RecordingClient client = new RecordingClient();
        FeedbackSlackNotifier notifier =
                new FeedbackSlackNotifier(client, new StubRepository(stored()), WEBHOOK, null);

        assertTrue(notifier.enabled());
        notifier.onFeedbackSubmitted(new FeedbackSubmitted("fb_1"));

        assertEquals(1, client.posted.size());
        assertTrue(client.posted.getFirst().startsWith("[local] 새 이용 후기 ★5 "), client.posted.getFirst());
    }

    @Test
    void 행이_사라졌으면_아무것도_보내지_않는다() {
        // 보존 기간 정리가 그 사이 지웠다는 뜻이다 - 없는 후기를 채널에 올릴 이유가 없다.
        RecordingClient client = new RecordingClient();
        FeedbackSlackNotifier notifier =
                new FeedbackSlackNotifier(client, new StubRepository(null), WEBHOOK, null);

        notifier.onFeedbackSubmitted(new FeedbackSubmitted("fb_gone"));

        assertEquals(List.of(), client.posted);
    }

    @Test
    void 전송이_실패해도_리스너는_던지지_않는다() {
        // 커밋 뒤라 되돌릴 것이 없고 후기 원본은 DB에 있다 - 예외가 올라가면 실행기 스레드만 죽는다.
        FeedbackSlackNotifier notifier = new FeedbackSlackNotifier(
                text -> {
                    throw new IllegalStateException("슬랙 500");
                },
                new StubRepository(stored()), WEBHOOK, null);

        notifier.onFeedbackSubmitted(new FeedbackSubmitted("fb_1"));
    }

    // === 헬퍼 ===

    private static SessionFeedback stored() {
        return feedback((short) 5, "좋아요", null, "web");
    }

    private static SessionFeedback feedback(@Nullable Short rating, String body,
                                            @Nullable String contactEmail, @Nullable String platform) {
        return new SessionFeedback("fb_1", "sess_abc", "key", rating, body, contactEmail,
                "NATIVE", "gn-2026.09.3", "sv-0.3", platform, Traffic.REAL, Instant.now());
    }

    /** 보낸 메시지를 그대로 들고 있는 클라이언트 - 실제 웹훅은 이 테스트 어디에서도 부르지 않는다. */
    private static final class RecordingClient implements SlackWebhookClient {

        private final List<String> posted = new ArrayList<>();

        @Override
        public void post(String text) {
            posted.add(text);
        }
    }

    /** id 조회 하나만 답하는 저장소 - 나머지 메서드는 이 테스트에서 불리지 않는다. */
    private record StubRepository(@Nullable SessionFeedback row) implements SessionFeedbackRepository {

        @Override
        public Optional<SessionFeedback> findById(String id) {
            return Optional.ofNullable(row);
        }

        @Override
        public Optional<SessionFeedback> findBySessionId(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionFeedback save(SessionFeedback feedback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countBySessionId(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long count() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteByCreatedAtBefore(Instant cutoff) {
            throw new UnsupportedOperationException();
        }
    }
}
