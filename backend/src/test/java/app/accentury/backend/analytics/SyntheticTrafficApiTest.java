package app.accentury.backend.analytics;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.AdminAuth;
import app.accentury.backend.session.TestSession;
import app.accentury.backend.session.TestSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 합성 트래픽 분리의 실행 가능한 명세 (KAN-138).
 * <p>
 * 전 구간 E2E 스모크는 prod에서도 돌아야 하는데, 그대로 두면 세션 생성과 완주가 익명 집계
 * (KAN-106)를 영구히 흔든다 - 일자 합계라 나중에 빼낼 수도 없다 (Codex sol 리뷰 P2).
 * 여기서 못박는 것은 네 가지다.
 * <ol>
 *   <li>표시한 세션의 집계는 실사용자와 <b>다른 행</b>에 쌓인다.</li>
 *   <li>표시에는 <b>인증이 필요하다</b> - 아무나 자기 응시를 통계에서 뺄 수 없다.</li>
 *   <li>리포트의 <b>기본은 실사용자</b>다 - 아무것도 적지 않은 사람이 합성 섞인 숫자를 받지 않는다.</li>
 *   <li>세션 행이 종류를 들고 있다 - 완주 카운터가 응시와 같은 통으로 가는 근거다.</li>
 * </ol>
 * 완주까지 실제로 같은 통에 쌓이는지는 {@code scripts/e2e_smoke.py}가 도는 스택에서 확인한다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "accentury.admin.token=" + SyntheticTrafficApiTest.TOKEN)
class SyntheticTrafficApiTest extends IntegrationTest {

    /** 최소 길이(32자) 검증을 통과해야 컨텍스트가 뜬다. */
    static final String TOKEN = "test-admin-token-0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DailyCounterRepository counters;

    @Autowired
    private TestSessionRepository sessions;

    @Autowired
    private AccenturyProperties properties;

    // === 1. 다른 행에 쌓인다 ===

    @Test
    void 표시한_세션의_응시는_합성_행으로_간다() throws Exception {
        long realBefore = startedToday(Traffic.REAL);
        long syntheticBefore = startedToday(Traffic.SYNTHETIC);

        createSession(TOKEN);

        assertEquals(realBefore, startedToday(Traffic.REAL),
                "표시한 요청이 실사용자 행을 건드리면 분리한 뜻이 없다");
        assertEquals(syntheticBefore + 1, startedToday(Traffic.SYNTHETIC));
    }

    @Test
    void 표시하지_않은_세션은_실사용자_행으로_간다() throws Exception {
        long realBefore = startedToday(Traffic.REAL);
        long syntheticBefore = startedToday(Traffic.SYNTHETIC);

        createSession(null);

        assertEquals(realBefore + 1, startedToday(Traffic.REAL));
        assertEquals(syntheticBefore, startedToday(Traffic.SYNTHETIC),
                "표시가 없으면 실사용자다 - 안전한 기본값이다");
    }

    // === 2. 표시에는 인증이 필요하다 ===

    @Test
    void 틀린_토큰으로_표시하면_401이고_세션도_만들어지지_않는다() throws Exception {
        long sessionsBefore = sessions.count();
        long startedBefore = startedToday(Traffic.REAL) + startedToday(Traffic.SYNTHETIC);

        mockMvc.perform(post("/v0/sessions").header(AdminAuth.TOKEN_HEADER, "wrong-token-but-long-enough-000000"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));

        assertEquals(sessionsBefore, sessions.count(), "표시가 틀렸으면 세션도 만들지 않는다");
        assertEquals(startedBefore, startedToday(Traffic.REAL) + startedToday(Traffic.SYNTHETIC),
                "거절된 요청은 어느 쪽으로도 세지 않는다");
    }

    @Test
    void 빈_토큰_헤더는_없는_것으로_치지_않고_401이다() throws Exception {
        // 시크릿이 비어 있는 파이프라인은 헤더를 빼는 게 아니라 빈 값으로 펼쳐 보낸다 -
        // 그것을 실사용자로 읽으면 막으려던 오염이 정확히 그 상황에서 들어온다 (Codex sol 리뷰 P2).
        long sessionsBefore = sessions.count();

        for (String blank : new String[] {"", "   "}) {
            mockMvc.perform(post("/v0/sessions").header(AdminAuth.TOKEN_HEADER, blank))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));
        }

        assertEquals(sessionsBefore, sessions.count(), "표시에 실패했으면 세션도 만들지 않는다");
    }

    @Test
    void 헤더가_아예_없을_때만_실사용자다() throws Exception {
        // 위 테스트의 반대쪽 - 표시를 시도하지 않은 평범한 요청은 그대로 통과해야 한다.
        long realBefore = startedToday(Traffic.REAL);

        mockMvc.perform(post("/v0/sessions")).andExpect(status().isCreated());

        assertEquals(realBefore + 1, startedToday(Traffic.REAL));
    }

    // === 3. 리포트의 기본은 실사용자 ===

    @Test
    void 조회_기본은_실사용자만이고_ALL로만_합성이_보인다() throws Exception {
        createSession(null);
        createSession(TOKEN);
        LocalDate today = LocalDate.now(properties.analytics().zone());

        // 기본 - 실사용자 줄만
        mockMvc.perform(admin(today).param("traffic", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.traffic == 'SYNTHETIC')]").isEmpty())
                .andExpect(jsonPath("$.rows[?(@.traffic == 'REAL')]").isNotEmpty());

        // 파라미터 자체를 생략해도 같다
        mockMvc.perform(admin(today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.traffic == 'SYNTHETIC')]").isEmpty());

        // 합성만
        mockMvc.perform(admin(today).param("traffic", "SYNTHETIC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.traffic == 'REAL')]").isEmpty())
                .andExpect(jsonPath("$.rows[?(@.traffic == 'SYNTHETIC')]").isNotEmpty());

        // 둘 다
        mockMvc.perform(admin(today).param("traffic", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.traffic == 'REAL')]").isNotEmpty())
                .andExpect(jsonPath("$.rows[?(@.traffic == 'SYNTHETIC')]").isNotEmpty());
    }

    @Test
    void 모르는_traffic_값은_기본값으로_접지_않고_400이다() throws Exception {
        // synthetc(오타)가 실사용자 지표로 접히면 읽는 사람은 자기가 무엇을 보는지 모른다.
        mockMvc.perform(admin(LocalDate.now(properties.analytics().zone())).param("traffic", "synthetc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 인증이_traffic_검증보다_먼저다() throws Exception {
        // 미인증 호출자에게 입력 검증 피드백이 새면 안 된다 - 일자 파라미터와 같은 규칙이다.
        mockMvc.perform(get("/admin/v0/analytics").param("traffic", "synthetc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));
    }

    // === 4. 세션 행이 종류를 들고 있다 ===

    @Test
    void 세션_행이_트래픽_종류를_기억한다() throws Exception {
        // 완주 카운터(CompletionService)가 이 값을 읽어 응시와 같은 통에 넣는다 -
        // 완주 시점에 다시 판정하면 헤더 없는 /complete가 전부 실사용자로 세어진다.
        String syntheticId = createSession(TOKEN);
        String realId = createSession(null);

        assertEquals(Traffic.SYNTHETIC, session(syntheticId).traffic());
        assertEquals(Traffic.REAL, session(realId).traffic());
    }

    // === 조립 ===

    private String createSession(String adminToken) throws Exception {
        var request = post("/v0/sessions");
        if (adminToken != null) {
            request = request.header(AdminAuth.TOKEN_HEADER, adminToken);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int start = body.indexOf("\"sessionId\":\"") + 13;
        return body.substring(start, body.indexOf('"', start));
    }

    private TestSession session(String id) {
        return sessions.findById(id).orElseThrow();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder admin(LocalDate day) {
        return get("/admin/v0/analytics")
                .header(AdminAuth.TOKEN_HEADER, TOKEN)
                .param("from", day.toString())
                .param("to", day.toString());
    }

    /** 같은 DB를 다른 테스트와 함께 쓰므로 절대값이 아니라 증가분을 본다. */
    private long startedToday(Traffic traffic) {
        String id = DailyCounter.idOf(LocalDate.now(properties.analytics().zone()),
                activeTestVersion(), activeScoreVersion(), traffic);
        return counters.findById(id).map(DailyCounter::sessionsStarted).orElse(0L);
    }
}
