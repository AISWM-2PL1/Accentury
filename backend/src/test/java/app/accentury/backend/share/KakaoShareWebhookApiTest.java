package app.accentury.backend.share;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.AdminAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v0/share/kakao/webhook}의 실행 가능한 명세 (KAN-164 AC 네 항목).
 * <p>
 * 카카오 서버를 흉내 낸다 - 문서(kakaotalk-share/callback)의 헤더와 본문 그대로다. 키를 설정한
 * 컨텍스트이고, 설정하지 않았을 때 경로 자체가 없다는 것은 {@link KakaoShareWebhookDisabledApiTest}가
 * 확인한다. 같은 DB를 다른 테스트가 함께 쓰므로 카운터는 절대값이 아니라 증가분을 본다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "accentury.share.kakao-admin-key=" + KakaoShareWebhookApiTest.KEY,
        "accentury.admin.token=" + KakaoShareWebhookApiTest.ADMIN_TOKEN})
class KakaoShareWebhookApiTest extends IntegrationTest {

    /** 카카오 Admin 키 형식(32자 16진수)을 흉내 낸 값 - 길이 검사는 없지만 실물과 같은 모양으로 둔다. */
    static final String KEY = "0123456789abcdef0123456789abcdef";
    static final String ADMIN_TOKEN = "test-admin-token-0123456789abcdef";

    private static final String URL = "/v0/share/kakao/webhook";
    private static final String RESOURCE_ID_HEADER = "X-Kakao-Resource-ID";
    private static final String CAMPAIGN = "kko_share";
    /** 카카오 문서의 POST 본문 예시에 앱이 실은 사용자 정의 키를 더한 것 - 채팅방 정보는 읽지 않는다. */
    private static final String BODY = """
            {"CHAT_TYPE": "DirectChat", "HASH_CHAT_ID": "hash-of-a-chat-room", "TEMPLATE_ID": 10000,
             "campaign": "kko_share"}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShareDailyCounterRepository counters;

    @Autowired
    private ShareWebhookReceiptRepository receipts;

    @Autowired
    private AccenturyProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    // === AC 1 - 카톡에서 카드를 전송하면 서버 카운터가 1 증가한다 ===

    @Test
    void 카카오가_알려_온_전송은_카운터를_1_올린다() throws Exception {
        withinOneDay(() -> {
            long before = sent(CAMPAIGN);
            String resourceId = newResourceId();

            mockMvc.perform(webhook(resourceId).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY))
                    .andExpect(status().isOk());

            assertEquals(before + 1, sent(CAMPAIGN));
            assertTrue(receipts.findById(resourceId).isPresent(), "받은 리소스 ID가 기록되어야 다음 중복을 거른다");
        });
    }

    @Test
    void 관리자_집계_조회에_전송_수가_실린다() throws Exception {
        withinOneDay(() -> {
            mockMvc.perform(webhook(newResourceId()).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY))
                    .andExpect(status().isOk());
            String today = LocalDate.now(properties.analytics().zone()).toString();

            mockMvc.perform(get("/admin/v0/analytics").header(AdminAuth.TOKEN_HEADER, ADMIN_TOKEN)
                            .param("from", today).param("to", today))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.shares.rows[*].campaign").value(hasItem(CAMPAIGN)))
                    .andExpect(jsonPath("$.shares.totalSent").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        });
    }

    // === AC 2 - 서명이 틀린 요청은 거부되고 카운터가 증가하지 않는다 ===

    @Test
    void 키가_틀리면_401이고_세지_않는다() throws Exception {
        long before = sent(CAMPAIGN);
        long receiptsBefore = receipts.count();

        // 다른 키
        mockMvc.perform(webhook(newResourceId()).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + "f".repeat(32)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SHARE_WEBHOOK_UNAUTHORIZED"))
                .andExpect(jsonPath("$.retryable").value(false));
        // 헤더 없음
        mockMvc.perform(webhook(newResourceId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SHARE_WEBHOOK_UNAUTHORIZED"));
        // 다른 스킴에 맞는 키 - 스킴까지 카카오의 것이어야 한다.
        mockMvc.perform(webhook(newResourceId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY))
                .andExpect(status().isUnauthorized());
        // 스킴만 있고 키가 없음
        mockMvc.perform(webhook(newResourceId()).header(HttpHeaders.AUTHORIZATION, "KakaoAK "))
                .andExpect(status().isUnauthorized());

        assertEquals(before, sent(CAMPAIGN), "거부된 요청은 세지 않는다");
        assertEquals(receiptsBefore, receipts.count(), "거부된 요청은 리소스 ID도 남기지 않는다 - 남기면 진짜 콜백이 중복으로 접힌다");
    }

    // === AC 3 - 같은 콜백이 두 번 와도 1만 증가한다 ===

    @Test
    void 같은_리소스_ID의_콜백은_한_번만_센다() throws Exception {
        withinOneDay(() -> {
            long before = sent(CAMPAIGN);
            String resourceId = newResourceId();

            mockMvc.perform(webhook(resourceId).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY))
                    .andExpect(status().isOk());
            // 두 번째도 200이다 - 카카오에게 중복은 "잘 받았다"와 같은 답이어야 재전송이 멈춘다.
            mockMvc.perform(webhook(resourceId).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY))
                    .andExpect(status().isOk());

            assertEquals(before + 1, sent(CAMPAIGN));
        });
    }

    @Test
    void 리소스_ID가_없으면_400이고_세지_않는다() throws Exception {
        long before = sent(CAMPAIGN);

        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(webhook("x".repeat(129)).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertEquals(before, sent(CAMPAIGN), "중복을 가릴 수 없는 콜백은 받지 않는다");
    }

    // === AC 4 - 저장된 행에 세션 id, 토큰, 점수가 없다 ===

    @Test
    void 저장되는_컬럼은_일자_캠페인_건수와_리소스_ID_수신_시각뿐이다() {
        // 스키마 자체를 본다 - 행의 내용이 아니라 담을 자리가 없어야 "저장하지 않는다"가 성립한다.
        assertEquals(Set.of("id", "stat_date", "campaign", "sent"), columns("share_daily_counter"));
        assertEquals(Set.of("resource_id", "received_at"), columns("share_webhook_receipt"));
    }

    @Test
    void 캠페인이_없거나_형식이_틀리면_unknown으로_센다() throws Exception {
        withinOneDay(() -> {
        long before = sent("unknown");
        long campaignBefore = sent(CAMPAIGN);

        // 사용자 정의 키가 없는 본문 - 카운트를 잃는 것보다 캠페인이 흐린 편이 낫다.
        mockMvc.perform(webhook(newResourceId()).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY)
                        .content("""
                                {"CHAT_TYPE": "MemoChat", "HASH_CHAT_ID": "h"}"""))
                .andExpect(status().isOk());
        // 형식을 어긴 값 - 자유 문자열을 그대로 두면 앱 버그나 위조가 DB에 임의 문자열을 남긴다.
        mockMvc.perform(webhook(newResourceId()).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY)
                        .content("""
                                {"campaign": "st_session-token|score=97"}"""))
                .andExpect(status().isOk());
        // JSON이 아닌 본문
        mockMvc.perform(webhook(newResourceId()).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY)
                        .contentType(MediaType.TEXT_PLAIN).content("not json"))
                .andExpect(status().isOk());
        // 본문 없음
        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY)
                        .header(RESOURCE_ID_HEADER, newResourceId()))
                .andExpect(status().isOk());

        assertEquals(before + 4, sent("unknown"));
        assertEquals(campaignBefore, sent(CAMPAIGN), "형식을 어긴 값이 정상 캠페인에 합산되면 안 된다");
        assertTrue(counters.findById(ShareDailyCounter.idOf(today(), "st_session-token|score=97")).isEmpty(),
                "어긴 값 그대로의 행이 생기면 안 된다");
        });
    }

    @Test
    void 폼_인코딩_본문도_캠페인을_읽는다() throws Exception {
        // 카카오 콜백이 폼 인코딩으로 온다는 보고가 있어 JSON과 함께 받는다 (Codex sol 리뷰 P2).
        withinOneDay(() -> {
            long before = sent(CAMPAIGN);

            mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY)
                            .header(RESOURCE_ID_HEADER, newResourceId())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("CHAT_TYPE=MemoChat&HASH_CHAT_ID=h%2Fx&campaign=kko_share&TEMPLATE_ID=10000"))
                    .andExpect(status().isOk());

            assertEquals(before + 1, sent(CAMPAIGN));
        });
    }

    @Test
    void 폼_본문의_앞_조각이_깨져도_뒤의_캠페인을_읽는다() throws Exception {
        // 첫 조각의 디코딩 실패가 스캔 전체를 접으면 멀쩡한 campaign이 unknown으로 샌다 (PR #88 리뷰).
        withinOneDay(() -> {
            long before = sent(CAMPAIGN);

            mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY)
                            .header(RESOURCE_ID_HEADER, newResourceId())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("%zz=1&campaign=kko_share"))
                    .andExpect(status().isOk());

            assertEquals(before + 1, sent(CAMPAIGN));
        });
    }

    @Test
    void JSON_본문의_문자열_값_안에_든_폼_조각은_캠페인이_아니다() throws Exception {
        withinOneDay(() -> {
            // 폼 폴백은 JSON 파싱 실패에만 건다 (Claude 리뷰). campaign이 문자열이 아닌 유효한 JSON의 다른 값에
            // 폼 조각이 들어 있어도 존재하지 않는 캠페인 행이 생기면 안 된다.
            mockMvc.perform(webhook(newResourceId()).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY)
                            .content("""
                                    {"campaign": 1, "HASH_CHAT_ID": "a&campaign=zzz&b"}"""))
                    .andExpect(status().isOk());

            assertTrue(counters.findById(ShareDailyCounter.idOf(today(), "zzz")).isEmpty(),
                    "JSON 문자열 값 안의 폼 조각이 캠페인으로 잡히면 안 된다");
        });
    }

    @Test
    void 본문_상한을_넘기면_400이고_세지_않는다() throws Exception {
        withinOneDay(() -> {
            // 카카오의 콜백은 수백 바이트다 - 그보다 훨씬 큰 본문은 인증을 통과했어도 다 읽어 주지 않는다 (리뷰 P1).
            long before = sent("unknown");
            String huge = "{\"pad\": \"" + "x".repeat(KakaoShareWebhookController.MAX_BODY_BYTES) + "\"}";

            mockMvc.perform(webhook(newResourceId()).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY).content(huge))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            // 키가 틀린 큰 본문은 본문을 보기 전에 401이다 - 인증이 첫 관문이라는 뜻이다.
            mockMvc.perform(webhook(newResourceId()).header(HttpHeaders.AUTHORIZATION, "KakaoAK wrong").content(huge))
                    .andExpect(status().isUnauthorized());

            assertEquals(before, sent("unknown"));
        });
    }

    @Test
    void GET은_받지_않는다() throws Exception {
        // 카카오 콘솔에는 POST로 등록한다 - GET은 사용자 정의 값이 URL 쿼리로 와 엣지 로그에 남는다.
        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, "KakaoAK " + KEY)
                        .header(RESOURCE_ID_HEADER, newResourceId()))
                .andExpect(status().isMethodNotAllowed());
    }

    /**
     * 일자 카운터를 읽고 쓰는 본문을 KST 하루가 바뀌지 않은 실행에서만 유효로 친다 - 자정을 사이에 두면 before는
     * 어제 행, after는 오늘 행이라 헛되이 실패한다 (AnalyticsApiTest와 같은 가드, Claude 리뷰).
     */
    private void withinOneDay(Body body) throws Exception {
        LocalDate day;
        do {
            day = today();
            body.run();
        } while (!day.equals(today()));
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    private static MockHttpServletRequestBuilder webhook(String resourceId) {
        return post(URL)
                .header(RESOURCE_ID_HEADER, resourceId)
                .header(HttpHeaders.USER_AGENT, "KakaoOpenAPI/1.0")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
    }

    private static String newResourceId() {
        return "kakao-" + UUID.randomUUID();
    }

    private LocalDate today() {
        return LocalDate.now(properties.analytics().zone());
    }

    private long sent(String campaign) {
        return counters.findById(ShareDailyCounter.idOf(today(), campaign))
                .map(ShareDailyCounter::sent)
                .orElse(0L);
    }

    private Set<String> columns(String table) {
        List<String> names = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = ?", String.class, table);
        return new TreeSet<>(names);
    }
}
