package app.accentury.backend.testdefinition;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.common.AdminAuth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /admin/v0/active-version}과 {@code GET /admin/v0/test-definitions}의 실행 가능한
 * 명세 (KAN-26, API 명세서 §6).
 * <p>
 * 발행본은 마이그레이션이 넣는다 - 운영과 같은 경로다. 활성 버전은 {@code gn-2026.08.1}이고,
 * 전환할 상대는 테스트 프로파일에만 있는 {@code gn-2026.07.0}이다
 * ({@code db/testdata/V900__second_test_definition.sql}).
 * <p>
 * <b>활성 버전을 바꾼 테스트는 반드시 되돌린다</b> ({@link #restoreBaseline}) - 활성 포인터는
 * 클래스 사이 초기화 대상이 아니고({@code DatabaseWipeExtension.KEEP}) 레지스트리의 메모리
 * 상태도 컨텍스트와 함께 살아남아, 안 되돌리면 다음 클래스가 바뀐 활성 버전을 물려받는다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "accentury.admin.token=" + AdminActiveVersionApiTest.TOKEN)
class AdminActiveVersionApiTest extends IntegrationTest {

    static final String TOKEN = "test-admin-token-0123456789abcdef";

    private static final String ACTIVE_VERSION_URL = "/admin/v0/active-version";
    private static final String DEFINITIONS_URL = "/admin/v0/test-definitions";

    /** 마이그레이션이 최초 활성으로 지정한 버전 - 되돌릴 자리다. */
    private static final String BASELINE = "gn-2026.08.1";

    /** 테스트 프로파일에만 있는 구버전 발행본 - 전환과 롤백의 상대다. */
    private static final String OLDER = "gn-2026.07.0";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActiveVersionService activeVersions;

    @Autowired
    private ActiveVersionAuditRepository audits;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void restoreBaseline() {
        activeVersions.activate(BASELINE, "테스트 정리");
    }

    // === AC - 활성 버전 변경과 롤백 ===

    @Test
    void 활성_버전을_바꾸면_새_세션이_그_버전에_고정된다() throws Exception {
        assertEquals(BASELINE, activeTestVersion(), "전제: baseline이 활성이다");

        mockMvc.perform(activate(OLDER, "구버전으로 시험 전환"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeVersion").value(OLDER))
                .andExpect(jsonPath("$.previousVersion").value(BASELINE))
                .andExpect(jsonPath("$.changed").value(true))
                // 운영 상태 응답이라 중간 캐시에 남기지 않는다 (§3.4와 같은 방침).
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        mockMvc.perform(post("/v0/sessions"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.testVersion").value(OLDER));
    }

    /**
     * KAN-167 AC - 다른 인스턴스가 건 활성 전환을 이 인스턴스의 새 세션이 그대로 고정한다.
     * <p>
     * "다른 인스턴스"는 이 프로세스의 서비스와 레지스트리를 거치지 않고 DB 포인터 행만 바꾸는
     * 것으로 재현한다 - 다른 태스크의 전환이 이 태스크에 남기는 흔적이 정확히 그것뿐이기
     * 때문이다(감사 행은 세션 생성이 읽지 않는다). 레지스트리가 활성 버전을 메모리에 다시
     * 들게 되면 이 테스트가 옛 버전을 받아 깨진다. 캐시를 두지 않았으므로 전파 지연은 0이다.
     */
    @Test
    void 다른_인스턴스가_전환한_활성_버전을_새_세션이_바로_고정한다() throws Exception {
        assertEquals(BASELINE, activeTestVersion(), "전제: baseline이 활성이다");

        jdbc.update("update active_test_version set test_version = ?, previous_test_version = ?,"
                + " activated_at = now() where id = 'CURRENT'", OLDER, BASELINE);

        mockMvc.perform(post("/v0/sessions"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.testVersion").value(OLDER));
        // 이 인스턴스의 관리자 조회도 같은 행을 읽으므로 같은 답을 준다.
        assertEquals(OLDER, activeVersions.current().testVersion());
    }

    /** AC - 활성 버전 변경이 진행 중 세션에 영향을 주지 않는다. */
    @Test
    void 전환_전에_만들어진_세션은_자기_버전을_그대로_쓴다() throws Exception {
        String before = mockMvc.perform(post("/v0/sessions"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String pinned = objectMapper.readTree(before).get("testVersion").asString();
        assertEquals(BASELINE, pinned);

        mockMvc.perform(activate(OLDER, null)).andExpect(status().isOk());

        // 고정된 버전의 정의가 계속 제공된다 - 응시 중이던 사람은 자기 문항으로 끝까지 간다.
        mockMvc.perform(get("/v0/tests/" + pinned))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testVersion").value(pinned))
                .andExpect(jsonPath("$.items.length()").value(10));
    }

    /** AC - 이전 활성 버전으로 롤백할 수 있다. */
    @Test
    void 롤백은_직전_활성_버전으로_되돌린다() throws Exception {
        mockMvc.perform(activate(OLDER, "전환")).andExpect(status().isOk());

        mockMvc.perform(rollback("되돌린다"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeVersion").value(BASELINE))
                .andExpect(jsonPath("$.previousVersion").value(OLDER))
                .andExpect(jsonPath("$.changed").value(true));

        assertEquals(BASELINE, activeTestVersion());
    }

    @Test
    void 롤백을_두_번_하면_두_버전을_오간다() throws Exception {
        // 되돌린 순간 방금 떠나온 버전이 새 목적지가 된다 - 임의 시점으로 가려면 버전을 명시한다.
        mockMvc.perform(activate(OLDER, null)).andExpect(status().isOk());
        mockMvc.perform(rollback(null))
                .andExpect(jsonPath("$.activeVersion").value(BASELINE));
        mockMvc.perform(rollback(null))
                .andExpect(jsonPath("$.activeVersion").value(OLDER));
    }

    @Test
    void 되돌아갈_이전_버전이_없으면_409다() throws Exception {
        // 마이그레이션 직후 상태 재현 - 포인터의 previous를 비운다. 이 상태의 롤백은 시간이
        // 지나도 풀리지 않으므로 retryable=false다.
        //
        // 반드시 되돌려 놓는다. 이 컬럼은 활성 전환으로만 다시 채워지는데 @AfterEach의
        // 되돌리기는 이미 baseline이 활성이라 멱등 분기로 빠져 아무것도 안 쓴다. 그대로 두면
        // 클래스 밖으로 null이 새어 나가고(active_test_version은 초기화 대상이 아니다),
        // 활성 전환 없이 롤백부터 하는 미래의 테스트가 실행 순서에 의존하게 된다.
        String saved = currentPreviousVersion();
        clearPreviousVersion();
        try {
            mockMvc.perform(rollback(null))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ADMIN_ROLLBACK_UNAVAILABLE"))
                    .andExpect(jsonPath("$.retryable").value(false));
        } finally {
            restorePreviousVersion(saved);
        }
    }

    // === 멱등 ===

    @Test
    void 이미_활성인_버전을_다시_올리면_아무것도_바뀌지_않는다() throws Exception {
        long before = auditCount();

        mockMvc.perform(activate(BASELINE, "재시도"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeVersion").value(BASELINE))
                .andExpect(jsonPath("$.changed").value(false));

        assertEquals(before, auditCount(), "재시도가 이력을 더럽히면 안 된다");
    }

    @Test
    void 재시도가_롤백_목적지를_자기_자신으로_덮지_않는다() throws Exception {
        // 덮어쓰면 그다음 롤백이 제자리걸음이 되어 되돌릴 길이 사라진다.
        mockMvc.perform(activate(OLDER, null)).andExpect(status().isOk());
        mockMvc.perform(activate(OLDER, "같은 요청 재전송"))
                .andExpect(jsonPath("$.previousVersion").value(BASELINE));

        mockMvc.perform(rollback(null))
                .andExpect(jsonPath("$.activeVersion").value(BASELINE));
    }

    // === AC - 발행·롤백 이력이 감사 로그에 남는다 ===

    @Test
    void 전환과_롤백이_이력에_최신순으로_남는다() throws Exception {
        mockMvc.perform(activate(OLDER, "전환 사유")).andExpect(status().isOk());
        mockMvc.perform(rollback("롤백 사유")).andExpect(status().isOk());

        mockMvc.perform(get(DEFINITIONS_URL).header(AdminAuth.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[0].action").value("ROLLBACK"))
                .andExpect(jsonPath("$.history[0].previousVersion").value(OLDER))
                .andExpect(jsonPath("$.history[0].newVersion").value(BASELINE))
                .andExpect(jsonPath("$.history[0].reason").value("롤백 사유"))
                .andExpect(jsonPath("$.history[0].recordedAt").exists())
                .andExpect(jsonPath("$.history[1].action").value("ACTIVATE"))
                .andExpect(jsonPath("$.history[1].previousVersion").value(BASELINE))
                .andExpect(jsonPath("$.history[1].newVersion").value(OLDER))
                .andExpect(jsonPath("$.history[1].reason").value("전환 사유"));
    }

    // === §6 - 버전 목록 ===

    @Test
    void 목록은_발행본과_활성_표시를_함께_준다() throws Exception {
        // 롤백 목적지를 눈으로 확인하고 나서 되돌릴 수 있어야 한다 - 목록이 그 자리를 알려준다.
        mockMvc.perform(activate(OLDER, null)).andExpect(status().isOk());
        mockMvc.perform(rollback(null)).andExpect(status().isOk());

        mockMvc.perform(get(DEFINITIONS_URL).header(AdminAuth.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeVersion").value(BASELINE))
                .andExpect(jsonPath("$.previousVersion").value(OLDER))
                .andExpect(jsonPath("$.definitions.length()").value(2))
                // 발행 시각 오름차순 - 구버전이 먼저다.
                .andExpect(jsonPath("$.definitions[0].testVersion").value(OLDER))
                .andExpect(jsonPath("$.definitions[0].dialect").value("GYEONGNAM"))
                .andExpect(jsonPath("$.definitions[0].scoreVersion").value("sv-0.3"))
                .andExpect(jsonPath("$.definitions[0].active").value(false))
                .andExpect(jsonPath("$.definitions[1].testVersion").value(BASELINE))
                .andExpect(jsonPath("$.definitions[1].active").value(true))
                // 13KB짜리 본문은 목록에 싣지 않는다 - 문항은 공개 엔드포인트(§3.2)에서 본다.
                .andExpect(jsonPath("$.definitions[0].body").doesNotExist())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    // === 인증 (§6) ===

    @Test
    void 토큰이_없거나_틀리면_401이고_상태는_그대로다() throws Exception {
        mockMvc.perform(put(ACTIVE_VERSION_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(OLDER)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));

        mockMvc.perform(put(ACTIVE_VERSION_URL)
                        .header(AdminAuth.TOKEN_HEADER, "wrong-token-0123456789abcdef0123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(OLDER)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(DEFINITIONS_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));

        assertEquals(BASELINE, activeTestVersion(), "미인증 요청이 상태를 바꾸면 안 된다");
    }

    @Test
    void 인증이_의미_검증보다_먼저다() throws Exception {
        // 미인증 호출자에게 입력 검증 피드백이 새면 안 된다 (KAN-106의 일자 파싱과 같은 규칙).
        // 경계가 어디까지인지는 컨트롤러 javadoc에 적어 두었다 - 본문 파싱 실패처럼 프레임워크가
        // 컨트롤러 앞에서 끊는 경로는 토큰 없이도 400을 받는다.
        mockMvc.perform(put(ACTIVE_VERSION_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"ROLLBACK","testVersion":"gn-2026.07.0"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));
    }

    // === 요청 형식 (§2.3) ===

    @Test
    void 발행되지_않은_버전은_404다() throws Exception {
        mockMvc.perform(activate("gn-9999.99.9", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 형식이_어긋난_요청은_400이다() throws Exception {
        // action 누락
        mockMvc.perform(request("""
                        {"testVersion":"gn-2026.07.0"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // ACTIVATE인데 대상 없음
        mockMvc.perform(request("""
                        {"action":"ACTIVATE"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // ROLLBACK인데 대상이 실려 옴 - 서버가 정하는 목적지와 어긋나면 의도를 알 수 없다.
        mockMvc.perform(request("""
                        {"action":"ROLLBACK","testVersion":"gn-2026.07.0"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 사유가 저장 컬럼(varchar 200)을 넘음 - 막지 않으면 INSERT가 500으로 터진다.
        mockMvc.perform(activate(OLDER, "가".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertEquals(BASELINE, activeTestVersion(), "형식 오류가 상태를 바꾸면 안 된다");
    }

    // === 픽스처 ===

    private MockHttpServletRequestBuilder activate(String testVersion, String reason) {
        return request(body(testVersion, reason));
    }

    private MockHttpServletRequestBuilder rollback(String reason) {
        return request(reason == null
                ? """
                {"action":"ROLLBACK"}"""
                : objectMapper.writeValueAsString(
                        new ActiveVersionRequest(ActiveVersionAudit.Action.ROLLBACK, null, reason)));
    }

    private MockHttpServletRequestBuilder request(String body) {
        return put(ACTIVE_VERSION_URL)
                .header(AdminAuth.TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String body(String testVersion) {
        return body(testVersion, null);
    }

    private String body(String testVersion, String reason) {
        return objectMapper.writeValueAsString(
                new ActiveVersionRequest(ActiveVersionAudit.Action.ACTIVATE, testVersion, reason));
    }

    private long auditCount() {
        return audits.count();
    }

    /** 최초 발행 직후 상태 재현 - 마이그레이션이 넣은 그대로의 포인터다. */
    private void clearPreviousVersion() {
        restorePreviousVersion(null);
    }

    private String currentPreviousVersion() {
        return jdbc.queryForObject(
                "select previous_test_version from active_test_version where id = 'CURRENT'",
                String.class);
    }

    private void restorePreviousVersion(String previousVersion) {
        jdbc.update("update active_test_version set previous_test_version = ? where id = 'CURRENT'",
                previousVersion);
    }
}
