package app.accentury.backend.feedback;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.SessionTestFlow;
import app.accentury.backend.SessionTestFlow.SessionHandle;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobTransitions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 후기 저장과 슬랙 전송이 이어지는가 (KAN-211 2단계).
 * <p>
 * {@link FeedbackApiTest}가 저장 계약을 보고 여기서는 그 뒤를 본다 - 커밋 뒤에 한 번만 나가는지,
 * 재전송에는 나가지 않는지, 슬랙이 실패해도 API가 멀쩡한지. 웹훅 URL은 형식만 흉내 낸 가짜이고
 * 클라이언트 빈을 기록용으로 바꿔 두므로 이 테스트는 네트워크에 나가지 않는다.
 * <p>
 * 전송이 비동기라(전용 실행기, {@code FeedbackNotifyConfig}) 단언 전에 기다린다. 테스트가 먼저
 * 끝나면 {@code DatabaseWipeExtension}이 행을 지워 알림이 "행 없음"으로 조용히 끝난다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties =
        // 실제 채널의 URL이 아니라 슬랙 URL 형식을 흉내 낸 가짜다 - 아래 기록용 클라이언트가
        // 가로채므로 이 값으로 나가는 요청은 없다. 값이 "있다"는 것만이 이 설정의 몫이다.
        "accentury.feedback.slack-webhook-url=https://hooks.slack.com/services/T000/B000/test")
class FeedbackSlackNotifyApiTest extends IntegrationTest {

    /** 비동기 전송을 기다리는 상한 - 넘으면 실패로 본다. */
    private static final Duration WAIT = Duration.ofSeconds(5);

    /** "더는 안 온다"를 확인할 때 지켜보는 시간 - 짧게 두어도 늦게 오는 한 건은 여기서 드러난다. */
    private static final Duration SETTLE = Duration.ofMillis(500);

    @TestConfiguration
    static class RecordingClientConfig {

        @Bean
        @Primary
        RecordingSlackWebhookClient recordingSlackWebhookClient() {
            return new RecordingSlackWebhookClient();
        }
    }

    /** 보낸 메시지를 모으는 클라이언트. {@code failing}을 켜면 슬랙 장애를 흉내 낸다. */
    static class RecordingSlackWebhookClient implements SlackWebhookClient {

        private final List<String> posted = new ArrayList<>();
        volatile boolean failing;

        @Override
        public void post(String text) {
            synchronized (posted) {
                posted.add(text);
            }
            if (failing) {
                throw new IllegalStateException("슬랙 전송 실패 시뮬레이션");
            }
        }

        List<String> snapshot() {
            synchronized (posted) {
                return List.copyOf(posted);
            }
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private AnalysisJobTransitions transitions;

    @Autowired
    private SessionFeedbackRepository feedbackRepository;

    @Autowired
    private RecordingSlackWebhookClient slack;

    @Autowired
    private FeedbackSlackNotifier notifier;

    private SessionTestFlow flow;

    @BeforeEach
    void setUp() {
        flow = new SessionTestFlow(mockMvc, objectMapper, analysisJobRepository, transitions);
        slack.failing = false;
        synchronized (slack.posted) {
            slack.posted.clear();
        }
    }

    @Test
    void URL이_있으면_알림이_켜진다() {
        assertTrue(notifier.enabled());
    }

    @Test
    void 저장이_커밋된_뒤_슬랙으로_한_번_나간다() throws Exception {
        SessionHandle session = completedSession();

        mockMvc.perform(feedback(session, "fb-notify", "{\"rating\":5,\"body\":\"재밌었어요\"}"))
                .andExpect(status().isCreated());

        List<String> posted = awaitPosts(1);
        assertTrue(posted.getFirst().contains("새 이용 후기 ★5"), posted.getFirst());
        assertTrue(posted.getFirst().endsWith("\n> 재밌었어요"), posted.getFirst());
        // 라벨은 결과 URL의 호스트다 - application.yml의 기본값(prod 도메인)이 그대로 쓰인다.
        assertTrue(posted.getFirst().startsWith("[accentury.app] "), posted.getFirst());
    }

    @Test
    void 같은_키의_재전송은_추가로_보내지_않는다() throws Exception {
        // 이벤트를 저장 경로에서만 발행하기 때문이다 - 같은 후기가 채널에 두 번 올라가면
        // 읽는 사람은 그것이 재전송인지 새 후기인지 알 수 없다.
        SessionHandle session = completedSession();
        mockMvc.perform(feedback(session, "fb-once", "{\"body\":\"처음 보낸 후기\"}"))
                .andExpect(status().isCreated());
        awaitPosts(1);

        mockMvc.perform(feedback(session, "fb-once", "{\"body\":\"처음 보낸 후기\"}"))
                .andExpect(status().isOk());

        assertStaysAt(1);
    }

    @Test
    void 슬랙이_실패해도_API는_201이고_후기는_남는다() throws Exception {
        // 슬랙은 부수 기능이다 - 채널이 죽었다고 사용자의 후기가 사라지면 안 된다.
        slack.failing = true;
        SessionHandle session = completedSession();

        mockMvc.perform(feedback(session, "fb-slack-down", "{\"body\":\"슬랙이 죽어도 남아야 한다\"}"))
                .andExpect(status().isCreated());

        awaitPosts(1);
        assertEquals(1, feedbackRepository.countBySessionId(session.id()));
        assertEquals("슬랙이 죽어도 남아야 한다",
                feedbackRepository.findBySessionId(session.id()).orElseThrow().body());
    }

    // === 헬퍼 ===

    /** 전송이 {@code expected}건에 이를 때까지 기다린다 - 비동기 실행기에서 도는 일이다. */
    private List<String> awaitPosts(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline && slack.snapshot().size() < expected) {
            Thread.sleep(20);
        }
        List<String> posted = slack.snapshot();
        assertEquals(expected, posted.size(), "슬랙 전송 건수: " + posted);
        return posted;
    }

    /** 지켜보는 동안 건수가 늘지 않는다 - "안 보낸다"는 기다려 봐야만 확인된다. */
    private void assertStaysAt(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + SETTLE.toNanos();
        while (System.nanoTime() < deadline) {
            assertEquals(expected, slack.snapshot().size(), "슬랙 전송 건수: " + slack.snapshot());
            Thread.sleep(20);
        }
    }

    private SessionHandle completedSession() throws Exception {
        SessionHandle session = flow.createSession();
        flow.answerVocab(session);
        flow.completeVoice(session);
        flow.complete(session, "complete-" + session.id());
        return session;
    }

    private RequestBuilder feedback(SessionHandle session, String idempotencyKey, String body) {
        return post("/v0/sessions/" + session.id() + "/feedback")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
