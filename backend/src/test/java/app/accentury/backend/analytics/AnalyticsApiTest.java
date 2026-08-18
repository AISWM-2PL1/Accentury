package app.accentury.backend.analytics;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.common.AccenturyProperties;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /admin/v0/analytics}의 실행 가능한 명세 (KAN-106 AC - 등급 누적 수와 점수 평균 조회).
 * <p>
 * 토큰을 설정한 컨텍스트다 - 설정하지 않았을 때 경로 자체가 없다는 것은
 * {@link AnalyticsEndpointDisabledApiTest}가 확인한다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "accentury.analytics.admin-token=" + AnalyticsApiTest.TOKEN)
class AnalyticsApiTest extends IntegrationTest {

    private static final String URL = "/admin/v0/analytics";
    /** 최소 길이(32자) 검증을 통과해야 컨텍스트가 뜬다 - 운영 토큰은 무작위 발급이다 */
    static final String TOKEN = "test-admin-token-0123456789abcdef";

    /** 다른 테스트의 세션이 섞이지 않게 과거 일자에 직접 심는다 */
    private static final LocalDate DAY = LocalDate.of(2026, 3, 1);
    private static final LocalDate NEXT_DAY = LocalDate.of(2026, 3, 2);

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private DailyCounterStore store;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AccenturyProperties properties;

    /**
     * seed는 운영과 같은 쓰기 경로({@link DailyCounterStore#insert})로 심는다 - 테스트 전용
     * 지름길로 심으면 실제 저장 경로가 이 명세에서 한 번도 안 돌게 된다.
     */
    @BeforeEach
    void seed() {
        transactionTemplate.executeWithoutResult(tx ->
                entityManager.createQuery("delete from DailyCounter").executeUpdate());
        // 3/1 sv-0.3: 시도 10, 완주 4 (명예주민 3 + 경남 토박이 1), 억양 합 300 / 단어 240 / 종합 280
        insert(DAY, "sv-0.3", 10, 4, 0, 0, 0, 3, 1, 300, 240, 280, 4);
        // 3/1 sv-0.4: 같은 일자 다른 점수 버전 - 등급 경계가 달라 섞으면 안 되므로 별도 행이다
        insert(DAY, "sv-0.4", 5, 1, 1, 0, 0, 0, 0, 40, 20, 33, 1);
        // 3/2 sv-0.3: 기간 조회가 일자로도 갈라지는지 보는 행
        insert(NEXT_DAY, "sv-0.3", 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    // === 인증 (토큰이 설정된 컨텍스트) ===

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void 틀린_토큰은_401이다() throws Exception {
        mockMvc.perform(get(URL).header(AnalyticsController.TOKEN_HEADER, "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));
    }

    // === 조회 (AC - 5등급 누적 수와 세 점수 평균) ===

    @Test
    void 등급_누적_수와_점수_평균을_반환한다() throws Exception {
        mockMvc.perform(query(DAY, DAY))
                .andExpect(status().isOk())
                // 내부 지표라도 중간 캐시에 남기지 않는다
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.zone").value("Asia/Seoul"))
                // 같은 일자에 두 버전을 심어 뒀다 - 아래 단언은 그중 sv-0.3 행이다
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].date").value("2026-03-01"))
                .andExpect(jsonPath("$.rows[0].testVersion").value("gn-2026.08.1"))
                .andExpect(jsonPath("$.rows[0].scoreVersion").value("sv-0.3"))
                .andExpect(jsonPath("$.rows[0].counts.sessionsStarted").value(10))
                .andExpect(jsonPath("$.rows[0].counts.sessionsCompleted").value(4))
                .andExpect(jsonPath("$.rows[0].counts.completionRate").value(0.4))
                // 5등급 전부 나온다 - 0인 등급이 빠지면 분포를 읽을 수 없다
                .andExpect(jsonPath("$.rows[0].counts.tiers.OUTSIDER").value(0))
                .andExpect(jsonPath("$.rows[0].counts.tiers.TRAVELER").value(0))
                .andExpect(jsonPath("$.rows[0].counts.tiers.WANNABE").value(0))
                .andExpect(jsonPath("$.rows[0].counts.tiers.HONORARY").value(3))
                .andExpect(jsonPath("$.rows[0].counts.tiers.NATIVE").value(1))
                // 평균 = 합 / 건수. 합도 함께 주므로 검산할 수 있다 (개별 점수 행이 없다)
                .andExpect(jsonPath("$.rows[0].counts.sums.intonation").value(300))
                .andExpect(jsonPath("$.rows[0].counts.averages.intonation").value(75.0))
                .andExpect(jsonPath("$.rows[0].counts.averages.vocabulary").value(60.0))
                .andExpect(jsonPath("$.rows[0].counts.averages.overall").value(70.0));
    }

    @Test
    void 같은_일자라도_점수_버전이_다르면_행이_갈라진다() throws Exception {
        // 하루만 조회해도 두 행이다 - 일자 차이가 아니라 버전 차이로 갈린다는 뜻이고,
        // 같은 일자 안에서는 버전 오름차순이다 (리포트 비교의 전제)
        mockMvc.perform(query(DAY, DAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].date").value("2026-03-01"))
                .andExpect(jsonPath("$.rows[0].scoreVersion").value("sv-0.3"))
                .andExpect(jsonPath("$.rows[1].date").value("2026-03-01"))
                .andExpect(jsonPath("$.rows[1].scoreVersion").value("sv-0.4"))
                // 두 행의 등급 분포가 서로 섞이지 않았다
                .andExpect(jsonPath("$.rows[0].counts.tiers.HONORARY").value(3))
                .andExpect(jsonPath("$.rows[0].counts.tiers.OUTSIDER").value(0))
                .andExpect(jsonPath("$.rows[1].counts.tiers.HONORARY").value(0))
                .andExpect(jsonPath("$.rows[1].counts.tiers.OUTSIDER").value(1));
    }

    @Test
    void 기간_합계는_일자와_버전을_모두_묶는다() throws Exception {
        mockMvc.perform(query(DAY, NEXT_DAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(3))
                // 정렬은 일자 우선, 그 다음 버전이다
                .andExpect(jsonPath("$.rows[2].date").value("2026-03-02"))
                .andExpect(jsonPath("$.totals.sessionsStarted").value(17))
                .andExpect(jsonPath("$.totals.sessionsCompleted").value(5))
                .andExpect(jsonPath("$.totals.tiers.HONORARY").value(3))
                .andExpect(jsonPath("$.totals.tiers.OUTSIDER").value(1))
                .andExpect(jsonPath("$.totals.scoredCount").value(5))
                .andExpect(jsonPath("$.totals.averages.intonation").value(68.0));
    }

    @Test
    void 카운터가_없는_기간은_빈_행과_null_평균이다() throws Exception {
        mockMvc.perform(query(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(0))
                .andExpect(jsonPath("$.totals.sessionsStarted").value(0))
                // 0으로 나누지 않는다 - 없는 평균은 0이 아니라 null이다
                .andExpect(jsonPath("$.totals.averages").doesNotExist())
                .andExpect(jsonPath("$.totals.completionRate").doesNotExist());
    }

    @Test
    void 기간을_생략하면_오늘_하루다() throws Exception {
        // 고정 시계가 없어 서버는 요청 시각의 KST 오늘을 쓴다 - 자정을 사이에 두면 여기서
        // 잡은 오늘과 어긋나 헛되이 실패하므로, 하루가 바뀌지 않은 실행만 유효로 치고
        // 그때만 다시 부른다 (2026-08-17 리뷰).
        String today;
        ResultActions call;
        do {
            today = LocalDate.now(properties.analytics().zone()).toString();
            call = mockMvc.perform(get(URL).header(AnalyticsController.TOKEN_HEADER, TOKEN));
        } while (!today.equals(LocalDate.now(properties.analytics().zone()).toString()));

        call.andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today))
                .andExpect(jsonPath("$.to").value(today));
    }

    @Test
    void to만_지정하면_그_하루다() throws Exception {
        // 여기서도 from을 오늘로 잡으면 과거 하루를 보려던 호출자가 보낸 적도 없는
        // from이 뒤라는 400을 받는다 (2026-08-17 확정 - 빠진 경계는 비대칭 기본값)
        mockMvc.perform(get(URL).header(AnalyticsController.TOKEN_HEADER, TOKEN)
                        .param("to", DAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-03-01"))
                .andExpect(jsonPath("$.to").value("2026-03-01"))
                .andExpect(jsonPath("$.rows.length()").value(2));
    }

    @Test
    void from만_지정하면_오늘까지다() throws Exception {
        // "이 날부터"라는 자연스러운 질의다 - 하루로 좁히면 쓸모가 준다.
        // 시작일을 오늘 기준으로 잡는 것은 고정 과거 일자를 쓰면 언젠가 조회 기간
        // 상한(366일)을 넘어 이 테스트가 400으로 깨지기 때문이다.
        // do-while은 KST 자정 가드다 - 기간을_생략하면_오늘_하루다와 같은 이유.
        LocalDate today;
        ResultActions call;
        do {
            today = LocalDate.now(properties.analytics().zone());
            call = mockMvc.perform(get(URL).header(AnalyticsController.TOKEN_HEADER, TOKEN)
                    .param("from", today.minusDays(3).toString()));
        } while (!today.equals(LocalDate.now(properties.analytics().zone())));

        call.andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today.minusDays(3).toString()))
                .andExpect(jsonPath("$.to").value(today.toString()));
    }

    @Test
    void 실제_응시가_조회에_그대로_나타난다() throws Exception {
        // 증가 경로와 조회 경로를 한 번에 잇는다 - 둘을 따로만 검증하면 키가 어긋나도
        // 양쪽 테스트가 모두 통과한다 (Fable 리뷰 P3). seed는 과거 일자라 오늘 행은 비어 있다.
        // do-while은 KST 자정 가드다 - 증가와 조회 사이에 하루가 바뀌면 서로 다른 날짜
        // 행을 보게 되므로, 하루가 바뀌지 않은 실행만 유효로 친다 (2026-08-17 리뷰).
        LocalDate day;
        ResultActions call;
        do {
            day = LocalDate.now(properties.analytics().zone());
            mockMvc.perform(post("/v0/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isCreated());
            call = mockMvc.perform(get(URL).header(AnalyticsController.TOKEN_HEADER, TOKEN));
        } while (!day.equals(LocalDate.now(properties.analytics().zone())));

        call.andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].testVersion").value(properties.testVersion()))
                .andExpect(jsonPath("$.rows[0].scoreVersion").value(properties.scoreVersion()))
                .andExpect(jsonPath("$.rows[0].counts.sessionsStarted").value(1))
                .andExpect(jsonPath("$.rows[0].counts.sessionsCompleted").value(0));
    }

    // === 입력 검증 ===

    @Test
    void 역전된_기간은_400이다() throws Exception {
        mockMvc.perform(query(NEXT_DAY, DAY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 상한을_넘는_기간은_400이다() throws Exception {
        mockMvc.perform(query(DAY, DAY.plusDays(properties.analytics().maxQueryDays())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 날짜_형식이_아니면_400이다() throws Exception {
        mockMvc.perform(get(URL).header(AnalyticsController.TOKEN_HEADER, TOKEN).param("from", "어제"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 토큰이_없으면_날짜_형식_오류보다_인증이_먼저다() throws Exception {
        // 인증이 첫 관문이다 (2026-08-17 리뷰) - LocalDate 파라미터로 받으면 바인딩이
        // authorize()보다 먼저 돌아 미인증 호출자가 입력 검증 피드백(400)을 받는다.
        // 컨트롤러가 날짜를 String으로 받아 인증 뒤에 파싱하는 이유를 여기서 고정한다.
        mockMvc.perform(get(URL).param("from", "어제"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));
    }

    // === 조립 ===

    private org.springframework.test.web.servlet.RequestBuilder query(LocalDate from, LocalDate to) {
        return get(URL)
                .header(AnalyticsController.TOKEN_HEADER, TOKEN)
                .param("from", from.toString())
                .param("to", to.toString());
    }

    private void insert(LocalDate date, String scoreVersion,
                        long started, long completed,
                        long outsider, long traveler, long wannabe, long honorary, long nativeTier,
                        long intonation, long vocabulary, long overall, long scored) {
        store.insert(date, "gn-2026.08.1", scoreVersion,
                new CounterDelta(started, completed, outsider, traveler, wannabe, honorary, nativeTier,
                        intonation, vocabulary, overall, scored));
    }
}
