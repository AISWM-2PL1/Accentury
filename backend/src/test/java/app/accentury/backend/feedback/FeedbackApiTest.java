package app.accentury.backend.feedback;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.SessionTestFlow;
import app.accentury.backend.SessionTestFlow.SessionHandle;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobTransitions;
import app.accentury.backend.analytics.Traffic;
import app.accentury.backend.result.TestResultRepository;
import app.accentury.backend.session.TestSession;
import app.accentury.backend.session.TestSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v0/sessions/{sid}/feedback}의 실행 가능한 명세 (KAN-211, 2026-09-15 결정).
 * <p>
 * 후기는 결과를 보고 쓰는 것이라 완료된 세션이 전제다 - 세션을 {@link SessionTestFlow}로
 * 생성 → 어휘 답안 → 음성 종결 → {@code /complete}까지 밀어 만든다. 시간 경과(만료)는
 * 세션 행의 {@code expires_at}을 과거로 되돌려 흉내낸다 (ResultApiTest와 같은 방식).
 */
@AutoConfigureMockMvc
class FeedbackApiTest extends IntegrationTest {

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
    private TestResultRepository resultRepository;

    @Autowired
    private TestSessionRepository sessionRepository;

    @Autowired
    private FeedbackSlackNotifier slackNotifier;

    private SessionTestFlow flow;

    @BeforeEach
    void setUp() {
        flow = new SessionTestFlow(mockMvc, objectMapper, analysisJobRepository, transitions);
    }

    // === 슬랙 알림 (KAN-211 2단계) ===

    @Test
    void 웹훅_URL이_없는_기본_설정에서는_알림이_꺼진다() {
        // 로컬, 테스트, 그리고 SSM 값을 아직 안 넣은 배포가 전부 이 상태다. 슬랙은 후기 저장의
        // 부수 기능이라 꺼져도 위 시나리오가 전부 그대로 통과해야 한다 - 이 클래스가 그 증거다.
        // 켜졌을 때의 동작은 FeedbackSlackNotifyApiTest가 본다.
        assertFalse(slackNotifier.enabled());
    }

    // === 정상 흐름 ===

    @Test
    void 완료된_세션의_후기는_201로_저장되고_스냅샷이_함께_남는다() throws Exception {
        SessionHandle session = completedSession();

        mockMvc.perform(feedback(session, "fb-1",
                        "{\"rating\":5,\"body\":\"사투리 억양 판정이 재밌었어요\","
                                + "\"contactEmail\":\"tester@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(true));

        assertEquals(1, feedbackRepository.countBySessionId(session.id()));
        SessionFeedback stored = feedbackRepository.findBySessionId(session.id()).orElseThrow();
        assertEquals(Short.valueOf((short) 5), stored.rating());
        assertEquals("사투리 억양 판정이 재밌었어요", stored.body());
        assertEquals("tester@example.com", stored.contactEmail());
        // 세션 행이 24시간 뒤 사라져도 후기를 읽을 수 있어야 한다 - 그래서 스냅샷이다 (FK 없음).
        TestSession source = sessionRepository.findById(session.id()).orElseThrow();
        assertEquals(resultRepository.findBySessionId(session.id()).orElseThrow().tierCode(),
                stored.tierCode());
        assertEquals(source.testVersion(), stored.testVersion());
        assertEquals(source.scoreVersion(), stored.scoreVersion());
        assertEquals(source.platform(), stored.platform());
        // 합성 트래픽(E2E 스모크) 후기를 나중에 걸러내기 위한 구분 - 일반 세션은 REAL이다.
        assertEquals(Traffic.REAL, stored.traffic());
    }

    @Test
    void 별점과_이메일_없이_본문만_보내도_201이다() throws Exception {
        // 둘 다 선택이다 - 한 줄만 쓰고 보내는 것이 가장 흔한 경로다.
        SessionHandle session = completedSession();

        mockMvc.perform(feedback(session, "fb-only-body", "{\"body\":\"좋아요\"}"))
                .andExpect(status().isCreated());

        SessionFeedback stored = feedbackRepository.findBySessionId(session.id()).orElseThrow();
        assertNull(stored.rating());
        assertNull(stored.contactEmail());
    }

    @Test
    void 응답_필드는_accepted_하나다() throws Exception {
        // 후기 id도 저장 시각도 돌려주지 않는다 - 클라이언트가 할 수 있는 일이 없다.
        SessionHandle session = completedSession();

        MvcResult result = mockMvc.perform(feedback(session, "fb-fields", "{\"body\":\"좋아요\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertEquals(Set.of("accepted"), fieldNames(result));
    }

    // === 멱등과 재제출 ===

    @Test
    void 같은_키의_재전송은_200이고_행은_하나다() throws Exception {
        SessionHandle session = completedSession();
        mockMvc.perform(feedback(session, "fb-same", "{\"body\":\"처음 보낸 후기\"}"))
                .andExpect(status().isCreated());

        // 본문을 대조하지 않는다 - 같은 키는 같은 요청으로 본다 (어휘 답안과 다른 규칙).
        mockMvc.perform(feedback(session, "fb-same", "{\"body\":\"처음 보낸 후기\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        assertEquals(1, feedbackRepository.countBySessionId(session.id()));
    }

    @Test
    void 다른_키의_재제출은_409다() throws Exception {
        // 세션당 후기는 하나다 - 고쳐 다시 보내는 경로가 없다 (2026-09-15 결정).
        SessionHandle session = completedSession();
        mockMvc.perform(feedback(session, "fb-first", "{\"body\":\"처음 보낸 후기\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(feedback(session, "fb-second", "{\"body\":\"다시 쓴 후기\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEEDBACK_ALREADY_SUBMITTED"))
                .andExpect(jsonPath("$.retryable").value(false));

        assertEquals(1, feedbackRepository.countBySessionId(session.id()));
        assertEquals("처음 보낸 후기",
                feedbackRepository.findBySessionId(session.id()).orElseThrow().body());
    }

    @Test
    void 같은_세션에_다른_키로_동시_제출해도_한_건만_저장된다() throws Exception {
        // 계약상 409여야 할 요청이 유니크 제약 위반(500)으로 끝나지 않는다는 증거다 (Codex 리뷰 P2).
        // 순서가 어느 쪽으로 갈리든 결과가 같아야 한다 - 늦게 온 쪽은 세션 행 잠금 뒤에서 기다렸다가
        // 이미 저장된 후기를 보고 409로 접힌다 (FeedbackService의 잠금 구간).
        SessionHandle session = completedSession();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> pending = List.of(
                    pool.submit(submitOnSignal(start, session, "fb-race-a")),
                    pool.submit(submitOnSignal(start, session, "fb-race-b")));
            start.countDown();
            for (Future<Integer> future : pending) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        statuses.sort(null);
        assertEquals(List.of(201, 409), statuses, "정확히 하나만 저장되고 나머지는 409다");
        assertEquals(1, feedbackRepository.countBySessionId(session.id()));
    }

    // === 완료 가드 ===

    @Test
    void 완료되지_않은_세션은_409_RESULT_NOT_READY다() throws Exception {
        // 결과를 보고 쓴 후기여야 한다 - 테스트 도중의 후기는 받지 않는다.
        SessionHandle session = flow.createSession();

        mockMvc.perform(feedback(session, "fb-early", "{\"body\":\"아직 안 끝났는데요\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESULT_NOT_READY"));

        assertEquals(0, feedbackRepository.countBySessionId(session.id()));
    }

    @Test
    void 만료됐어도_완료된_세션이면_201이다() throws Exception {
        // 결과 조회와 같은 인증 규칙이다 (authenticateBearerForResult, KAN-25) - 결과 화면은
        // 세션 expiresAt이 지난 뒤에도 열려 있으므로, 그 화면의 후기만 401이 되면 모순이다.
        SessionHandle session = completedSession();
        expireCompletedSession(session);

        mockMvc.perform(feedback(session, "fb-expired", "{\"body\":\"한참 뒤에 씁니다\"}"))
                .andExpect(status().isCreated());

        assertEquals(1, feedbackRepository.countBySessionId(session.id()));
    }

    // === 인증 (§2.1) ===

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        SessionHandle session = completedSession();

        mockMvc.perform(post(url(session))
                        .header("Idempotency-Key", "fb-no-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"좋아요\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void 다른_세션의_토큰이면_403이다() throws Exception {
        SessionHandle mine = completedSession();
        SessionHandle other = completedSession();

        mockMvc.perform(post(url(mine))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.token())
                        .header("Idempotency-Key", "fb-wrong-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"좋아요\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SESSION_FORBIDDEN"));
    }

    // === 검증 (400) ===

    @Test
    void Idempotency_Key가_없으면_400이다() throws Exception {
        SessionHandle session = completedSession();

        mockMvc.perform(post(url(session))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"좋아요\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 본문이_공백이거나_500자를_넘으면_400이다() throws Exception {
        SessionHandle session = completedSession();

        mockMvc.perform(feedback(session, "fb-blank", "{\"body\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(feedback(session, "fb-long",
                        "{\"body\":\"" + "가".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 500자 정확히는 통과한다 - 컬럼 길이와 같은 경계다.
        mockMvc.perform(feedback(session, "fb-exact",
                        "{\"body\":\"" + "가".repeat(500) + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void 별점이_1에서_5_밖이면_400이다() throws Exception {
        SessionHandle session = completedSession();

        mockMvc.perform(feedback(session, "fb-r0", "{\"rating\":0,\"body\":\"좋아요\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(feedback(session, "fb-r6", "{\"rating\":6,\"body\":\"좋아요\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertEquals(0, feedbackRepository.countBySessionId(session.id()));
    }

    @Test
    void 별점이_소수면_400이다() throws Exception {
        // 계약의 증거다 (Codex 리뷰 P1). 고치기 전에는 2.5가 2로 잘려 201로 저장됐다 - 화면이
        // Number.isInteger로 거르고 있어 사용자에게 드러나지 않았을 뿐, BE만 놓고 보면 사용자가
        // 고르지 않은 별점이 남는 경로였다 (2026-09-15 실측).
        SessionHandle session = completedSession();

        mockMvc.perform(feedback(session, "fb-fraction", "{\"rating\":2.5,\"body\":\"좋아요\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertEquals(0, feedbackRepository.countBySessionId(session.id()));
    }

    @Test
    void 소수점_표기라도_정수_값이면_201이다() throws Exception {
        // 따지는 것은 값이지 표기가 아니다 - 5.0은 사용자가 고른 칸이 5라는 뜻이므로 통과한다.
        SessionHandle session = completedSession();

        mockMvc.perform(feedback(session, "fb-int-as-float", "{\"rating\":5.0,\"body\":\"좋아요\"}"))
                .andExpect(status().isCreated());

        assertEquals(Short.valueOf((short) 5),
                feedbackRepository.findBySessionId(session.id()).orElseThrow().rating());
    }

    @Test
    void 이메일_형식이_틀리거나_254자를_넘으면_400이다() throws Exception {
        SessionHandle session = completedSession();

        mockMvc.perform(feedback(session, "fb-e1",
                        "{\"body\":\"좋아요\",\"contactEmail\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 형식은 맞지만 컬럼 길이를 넘는 주소 - 검증 없이 저장하면 400이 아니라 500이 된다.
        String tooLong = "a".repeat(255 - "@example.com".length()) + "@example.com";
        mockMvc.perform(feedback(session, "fb-e2",
                        "{\"body\":\"좋아요\",\"contactEmail\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertEquals(0, feedbackRepository.countBySessionId(session.id()));
    }

    @Test
    void 이메일이_빈_문자열이면_미입력으로_본다() throws Exception {
        // 폼의 빈 칸을 400으로 돌려줄 이유가 없다.
        SessionHandle session = completedSession();

        mockMvc.perform(feedback(session, "fb-empty-email",
                        "{\"body\":\"좋아요\",\"contactEmail\":\"\"}"))
                .andExpect(status().isCreated());

        assertNull(feedbackRepository.findBySessionId(session.id()).orElseThrow().contactEmail());
    }

    // === 헬퍼 ===

    /** 생성 → 어휘 답안 → 음성 종결 → /complete까지 밀어 결과가 확정된 세션을 만든다. */
    private SessionHandle completedSession() throws Exception {
        SessionHandle session = flow.createSession();
        flow.answerVocab(session);
        flow.completeVoice(session);
        flow.complete(session, "complete-" + session.id());
        return session;
    }

    /**
     * 완료된 세션의 expires_at만 과거로 되돌린다 - 24시간 경과를 흉내낸다 (§5.5).
     * 세션 행을 지우지 않는 것은 FK가 ON DELETE CASCADE라 결과까지 함께 사라지기 때문이다.
     */
    private void expireCompletedSession(SessionHandle session) {
        TestSession stored = sessionRepository.findById(session.id()).orElseThrow();
        stored.markCompleted(stored.completedAt(), Instant.now().minusSeconds(1));
        sessionRepository.save(stored);
    }

    /** 래치가 풀리는 순간 같은 세션에 후기를 보내고 상태 코드만 돌려준다 (동시 제출 검증용). */
    private Callable<Integer> submitOnSignal(CountDownLatch start, SessionHandle session, String key) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(feedback(session, key, "{\"body\":\"동시에 보낸 후기\"}"))
                    .andReturn().getResponse().getStatus();
        };
    }

    private static String url(SessionHandle session) {
        return "/v0/sessions/" + session.id() + "/feedback";
    }

    private RequestBuilder feedback(SessionHandle session, String idempotencyKey, String body) {
        return post(url(session))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Set<String> fieldNames(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        Set<String> names = new HashSet<>();
        json.propertyNames().forEach(names::add);
        return names;
    }
}
