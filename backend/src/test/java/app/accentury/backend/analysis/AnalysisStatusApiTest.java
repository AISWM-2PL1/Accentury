package app.accentury.backend.analysis;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.common.AccenturyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /v0/sessions/{sid}/analyses[/{jobId}]}의 실행 가능한 명세 (KAN-24, API 명세서 §3.4).
 * <p>
 * 시도(작업) 행은 업로드 API를 거치지 않고 repository로 직접 심고, 종결 전이는
 * 실제 전이 경로인 {@link AnalysisJobTransitions}로 일으킨다.
 */
@AutoConfigureMockMvc
class AnalysisStatusApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnalysisJobRepository repository;

    @Autowired
    private AnalysisJobTransitions transitions;

    @Autowired
    private AnalysisBacklog backlog;

    @Autowired
    private AccenturyProperties properties;

    // === 일괄 조회 (§3.4) ===

    @Test
    void 시도가_없으면_전_음성_문항이_NOT_SUBMITTED다() throws Exception {
        SessionHandle session = createSession();

        var expectations = mockMvc.perform(statuses(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pollAfterMs").value(800))
                // 어휘 문항은 실리지 않는다 - 음성 5문항 전부, seq 순서
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[0].itemId").value("v1"))
                .andExpect(jsonPath("$.items[4].itemId").value("v5"));
        for (int i = 0; i < 5; i++) {
            expectations.andExpect(jsonPath("$.items[" + i + "].status").value("NOT_SUBMITTED"));
        }
    }

    @Test
    void 분석_중_문항은_PROCESSING이고_부가_정보가_없다() throws Exception {
        SessionHandle session = createSession();
        saveJob(session, "v1", 1, Instant.now());

        mockMvc.perform(statuses(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.items[0].quality").doesNotExist())
                .andExpect(jsonPath("$.items[0].error").doesNotExist());
    }

    @Test
    void 완료_문항은_quality를_보이되_점수는_어디에도_없다() throws Exception {
        SessionHandle session = createSession();
        AnalysisJob job = saveJob(session, "v1", 1, Instant.now());
        transitions.complete(job.id(), 78, "OK", "rmvpe-0.2+dtw-0.1", "sv-0.3");

        MvcResult result = mockMvc.perform(statuses(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].quality").value("OK"))
                .andReturn();

        // 문항 중간 점수 미노출 (KAN-12, KAN-24 AC) - 필드 이름 자체가 응답에 없어야 한다.
        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("intonationScore"), "상태 응답에 점수가 실렸다: " + body);
        assertFalse(body.contains("\"score"), "상태 응답에 점수 필드가 실렸다: " + body);
    }

    @Test
    void 실패_문항은_오류_코드와_retryable을_보인다() throws Exception {
        SessionHandle session = createSession();
        AnalysisJob job = saveJob(session, "v2", 1, Instant.now());
        transitions.fail(job.id(), AnalysisJobStatus.RETRYABLE_FAILED, "AUDIO_TOO_QUIET");

        mockMvc.perform(statuses(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].status").value("RETRYABLE_FAILED"))
                .andExpect(jsonPath("$.items[1].error.code").value("AUDIO_TOO_QUIET"))
                .andExpect(jsonPath("$.items[1].error.retryable").value(true));
    }

    // === 대표 상태 규칙 (2026-08-10 확정 - /complete의 "최신 성공 시도 1건"과 일치) ===

    @Test
    void 성공_시도가_있으면_이후_실패에도_COMPLETED다() throws Exception {
        SessionHandle session = createSession();
        Instant base = Instant.now().minusSeconds(60);
        AnalysisJob first = saveJob(session, "v1", 1, base);
        AnalysisJob second = saveJob(session, "v1", 2, base.plusSeconds(10));
        transitions.complete(first.id(), 80, "OK", "rmvpe-0.2", "sv-0.3");
        transitions.fail(second.id(), AnalysisJobStatus.RETRYABLE_FAILED, "AUDIO_TOO_QUIET");

        // 채점 대상(1차 성공)이 살아 있으므로 재녹음을 유도하지 않는다 (§3.6, §5.1).
        mockMvc.perform(statuses(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].error").doesNotExist());
    }

    @Test
    void 새_시도가_분석_중이면_이전_성공이_있어도_PROCESSING이다() throws Exception {
        SessionHandle session = createSession();
        Instant base = Instant.now().minusSeconds(60);
        AnalysisJob first = saveJob(session, "v1", 1, base);
        saveJob(session, "v1", 2, base.plusSeconds(10));
        transitions.complete(first.id(), 80, "OK", "rmvpe-0.2", "sv-0.3");

        // 새 결과가 채점 대상을 갈아치울 수 있으므로 대기 화면은 기다려야 한다.
        mockMvc.perform(statuses(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("PROCESSING"));
    }

    @Test
    void 성공보다_새로운_시도가_분석_중이면_최신_실패에도_PROCESSING이다() throws Exception {
        // [성공, 분석 중, 실패] - 분석 중인 2차가 성공하면 채점 대상(최신 성공)이 1차에서
        // 2차로 바뀐다. 여기서 COMPLETED를 보고하면 /complete가 옛 점수로 결과를 영구
        // 확정할 수 있으므로 기다려야 한다 (Codex sol 리뷰 P1).
        SessionHandle session = createSession();
        Instant base = Instant.now().minusSeconds(60);
        AnalysisJob first = saveJob(session, "v1", 1, base);
        saveJob(session, "v1", 2, base.plusSeconds(10));
        AnalysisJob third = saveJob(session, "v1", 3, base.plusSeconds(20));
        transitions.complete(first.id(), 80, "OK", "rmvpe-0.2", "sv-0.3");
        transitions.fail(third.id(), AnalysisJobStatus.RETRYABLE_FAILED, "AUDIO_TOO_QUIET");

        mockMvc.perform(statuses(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.items[0].error").doesNotExist());
    }

    @Test
    void 성공이_없고_이전_시도가_아직_분석_중이면_최신_실패라도_PROCESSING이다() throws Exception {
        // 겹친 업로드에서 새 시도가 먼저 실패한 경우 - 아직 도는 이전 시도가 성공하면
        // 채점 대상이 되므로, 실패를 보고해 폴링을 멈추게 하면 안 된다 (Codex sol 리뷰 P2).
        SessionHandle session = createSession();
        Instant base = Instant.now().minusSeconds(60);
        saveJob(session, "v1", 1, base);
        AnalysisJob second = saveJob(session, "v1", 2, base.plusSeconds(10));
        transitions.fail(second.id(), AnalysisJobStatus.RETRYABLE_FAILED, "AUDIO_TOO_QUIET");

        mockMvc.perform(statuses(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.items[0].error").doesNotExist());
    }

    @Test
    void 상태_응답은_캐시되지_않는다() throws Exception {
        // 폴링 응답이 캐시에서 재사용되면 완료가 가려진다 (Codex sol 리뷰 P2).
        SessionHandle session = createSession();
        AnalysisJob job = saveJob(session, "v1", 1, Instant.now());

        mockMvc.perform(statuses(session))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mockMvc.perform(get(url(session) + "/" + job.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    // === 혼잡 시 폴링 간격 (§5.3 규칙 1, KAN-24 AC) ===

    @Test
    void 혼잡하면_모든_상태_응답의_pollAfterMs가_상향된다() throws Exception {
        // 판정 규칙 자체는 PollIntervalsTest가 검증한다 - 여기서는 백로그부터 API 응답까지의
        // 배선을 확인한다. 이 배선이 끊기면 서버가 간격을 올려도 클라이언트에 전달되지 않아
        // 혼잡 시 폴링 증폭(§5.3 - 요청 20배)을 막을 수 없다.
        SessionHandle session = createSession();
        AnalysisJob job = saveJob(session, "v1", 1, Instant.now());
        // 임계치와 간격은 설정이 정본이다 - 값을 복사하면 설정 변경 시 엉뚱한 이유로 깨진다.
        // 공유 빈을 직접 올리므로 이 테스트는 다른 테스트와 병렬 실행하면 안 된다 (finally 복원).
        int threshold = properties.analysis().congestionThreshold();
        int congested = (int) properties.analysis().congestedPollAfterMs();
        int base = (int) properties.analysis().pollAfterMs();
        for (int i = 0; i < threshold; i++) {
            backlog.started();
        }
        try {
            mockMvc.perform(statuses(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pollAfterMs").value(congested));
            mockMvc.perform(get(url(session) + "/" + job.id())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pollAfterMs").value(congested));
        } finally {
            for (int i = 0; i < threshold; i++) {
                backlog.finished();
            }
        }

        // 밀림이 풀리면 즉시 기준 간격으로 돌아온다.
        mockMvc.perform(statuses(session))
                .andExpect(jsonPath("$.pollAfterMs").value(base));
    }

    // === 단건 조회 (§3.4 - "동일 스키마 + modelVersion, scoreVersion") ===

    @Test
    void 완료_작업_단건은_모델과_점수_버전을_포함하고_점수는_없다() throws Exception {
        SessionHandle session = createSession();
        AnalysisJob job = saveJob(session, "v1", 1, Instant.now());
        transitions.complete(job.id(), 78, "OK", "rmvpe-0.2+dtw-0.1", "sv-0.3");

        MvcResult result = mockMvc.perform(get(url(session) + "/" + job.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value("v1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.quality").value("OK"))
                .andExpect(jsonPath("$.modelVersion").value("rmvpe-0.2+dtw-0.1"))
                .andExpect(jsonPath("$.scoreVersion").value("sv-0.3"))
                .andExpect(jsonPath("$.pollAfterMs").value(800))
                .andReturn();

        assertFalse(result.getResponse().getContentAsString().contains("intonationScore"));
    }

    @Test
    void 단건은_문항_대표가_아니라_그_작업의_상태다() throws Exception {
        SessionHandle session = createSession();
        Instant base = Instant.now().minusSeconds(60);
        AnalysisJob first = saveJob(session, "v1", 1, base);
        AnalysisJob second = saveJob(session, "v1", 2, base.plusSeconds(10));
        transitions.complete(first.id(), 80, "OK", "rmvpe-0.2", "sv-0.3");
        transitions.fail(second.id(), AnalysisJobStatus.RETRYABLE_FAILED, "AUDIO_TOO_QUIET");

        mockMvc.perform(get(url(session) + "/" + second.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETRYABLE_FAILED"))
                .andExpect(jsonPath("$.error.code").value("AUDIO_TOO_QUIET"))
                .andExpect(jsonPath("$.modelVersion").doesNotExist());
    }

    @Test
    void 다른_세션의_작업과_없는_작업은_같은_404다() throws Exception {
        SessionHandle owner = createSession();
        SessionHandle intruder = createSession();
        AnalysisJob job = saveJob(owner, "v1", 1, Instant.now());

        mockMvc.perform(get(url(intruder) + "/" + job.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get(url(owner) + "/a_no-such-job")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // === 인증 (§2.1) ===

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        SessionHandle session = createSession();

        mockMvc.perform(get(url(session)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void 다른_세션의_토큰이면_403이다() throws Exception {
        SessionHandle mine = createSession();
        SessionHandle other = createSession();

        mockMvc.perform(get(url(mine))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SESSION_FORBIDDEN"));
    }

    // === 헬퍼 ===

    private record SessionHandle(String id, String token) {
    }

    private SessionHandle createSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/v0/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new SessionHandle(json.get("sessionId").asString(), json.get("sessionToken").asString());
    }

    private String url(SessionHandle session) {
        return "/v0/sessions/" + session.id() + "/analyses";
    }

    private org.springframework.test.web.servlet.RequestBuilder statuses(SessionHandle session) {
        return get(url(session)).header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token());
    }

    private AnalysisJob saveJob(SessionHandle session, String itemId, int attempt, Instant createdAt) {
        // ID 컬럼 상한(40자)에 맞춘 실제 형식 그대로 - a_ + UUID (§3.3)
        return repository.save(new AnalysisJob("a_" + java.util.UUID.randomUUID(),
                session.id(), itemId, attempt, "idem-" + itemId + "-" + attempt,
                AnalysisJobStatus.PROCESSING, createdAt));
    }
}
