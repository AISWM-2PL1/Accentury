package app.accentury.backend.analytics;

import app.accentury.backend.IntegrationTest;
import app.accentury.backend.SessionTestFlow;
import app.accentury.backend.SessionTestFlow.SessionHandle;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobTransitions;
import app.accentury.backend.result.TestResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC - 카운터 증가 실패가 세션 생성이나 결과 반환을 막지 않는다 (KAN-106).
 * <p>
 * 통계는 부수적이고 응시는 본질이라, 집계 저장소가 통째로 죽어도 사용자는 테스트를 끝까지
 * 마칠 수 있어야 한다. 그래서 여기서는 <b>모든 쓰기가 실패하는 저장소</b>를 끼우고 전체
 * 흐름(세션 생성 → 제출 → 완료)을 그대로 돌린다. 실패 경로가 정말 예외를 던지는지도 함께
 * 확인한다 - 던지지 않는 가짜 저장소로는 이 명세가 아무것도 증명하지 못한다.
 */
@AutoConfigureMockMvc
class CounterFailureIsolationApiTest extends IntegrationTest {

    @TestConfiguration
    static class FailingStoreConfig {

        @Bean
        @Primary
        CounterStore failingCounterStore() {
            return new AlwaysFailingStore();
        }
    }

    /** 집계 DB 장애를 흉내낸다 - 증가도 생성도 전부 실패한다. */
    static final class AlwaysFailingStore implements CounterStore {

        @Override
        public boolean increment(String id, CounterDelta delta) {
            throw new IllegalStateException("집계 저장소 장애 (테스트)");
        }

        @Override
        public void insert(LocalDate statDate, String testVersion, String scoreVersion,
                           Traffic traffic, CounterDelta delta) {
            throw new IllegalStateException("집계 저장소 장애 (테스트)");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CounterStore store;

    @Autowired
    private DailyCounterRepository countersRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private AnalysisJobTransitions transitions;

    @Autowired
    private TestResultRepository resultRepository;

    private SessionTestFlow flow;

    @BeforeEach
    void setUpFlow() {
        flow = new SessionTestFlow(mockMvc, objectMapper, analysisJobRepository, transitions);
    }

    @Test
    void 끼운_저장소가_정말_실패한다() {
        // 이 확인이 없으면 아래 두 테스트가 "실패해도 괜찮다"가 아니라
        // "실패하지 않았다"를 통과시킬 수 있다.
        assertTrue(store instanceof AlwaysFailingStore);
    }

    @Test
    void 집계_저장소가_죽어도_세션은_생성된다() throws Exception {
        mockMvc.perform(post("/v0/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").exists())
                .andExpect(jsonPath("$.sessionToken").exists());
    }

    @Test
    void 집계_저장소가_죽어도_결과는_확정되고_반환된다() throws Exception {
        long rowsBefore = countersRepository.count();
        SessionHandle session = flow.createSession();
        flow.answerVocab(session);
        flow.completeVoice(session);

        mockMvc.perform(post("/v0/sessions/" + session.id() + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .header("Idempotency-Key", "isolation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        // 결과 행까지 정상 저장됐다 - 집계 실패가 완료 트랜잭션을 오염시키지 않았다는 뜻이다.
        assertTrue(resultRepository.findBySessionId(session.id()).isPresent());
        assertEquals(rowsBefore, countersRepository.count(), "실패한 증가가 행을 남기지 않는다");
    }

}
