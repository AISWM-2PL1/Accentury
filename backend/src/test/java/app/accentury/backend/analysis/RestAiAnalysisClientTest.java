package app.accentury.backend.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

/**
 * {@code POST /internal/v0/analyze} 호출의 응답 매핑 (KAN-24, API 명세서 §4.1).
 * <p>
 * AI 서버 없이 MockRestServiceServer로 검증한다 - 계약 위반 응답(형식, 점수 범위)이
 * 장애가 아니라 비재시도 실패로 접히는 것이 핵심이다.
 */
class RestAiAnalysisClientTest {

    private MockRestServiceServer server;
    private RestAiAnalysisClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestAiAnalysisClient(builder.build(), new ObjectMapper());
    }

    @Test
    void 성공_응답을_Completed로_매핑한다() {
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andExpect(method(POST))
                .andExpect(header("X-Correlation-Id", "c_test"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andRespond(withSuccess("""
                        { "status": "OK", "intonationScore": 78, "confidence": 0.86,
                          "quality": { "code": "OK" },
                          "modelVersion": "rmvpe-0.2+dtw-0.1", "scoreVersion": "sv-0.3",
                          "processingMs": 1840 }
                        """, MediaType.APPLICATION_JSON));

        AiAnalysisClient.Outcome outcome = client.analyze(request(), "c_test");

        AiAnalysisClient.Completed completed = assertInstanceOf(AiAnalysisClient.Completed.class, outcome);
        assertEquals(78, completed.intonationScore());
        assertEquals("OK", completed.qualityCode());
        assertEquals("rmvpe-0.2+dtw-0.1", completed.modelVersion());
        assertEquals("sv-0.3", completed.scoreVersion());
    }

    @Test
    void 판정_실패_422를_Rejected로_매핑한다() {
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).body("""
                        { "status": "FAILED", "quality": { "code": "AUDIO_TOO_QUIET" }, "retryable": true }
                        """).contentType(MediaType.APPLICATION_JSON));

        AiAnalysisClient.Rejected rejected =
                assertInstanceOf(AiAnalysisClient.Rejected.class, client.analyze(request(), "c_test"));
        assertEquals("AUDIO_TOO_QUIET", rejected.errorCode());
        assertTrue(rejected.retryable());
    }

    @Test
    void retryable이_생략된_판정은_코드_정의의_기본값을_따른다() {
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).body("""
                        { "status": "FAILED", "quality": { "code": "AUDIO_TOO_QUIET" } }
                        """).contentType(MediaType.APPLICATION_JSON));

        AiAnalysisClient.Rejected rejected =
                assertInstanceOf(AiAnalysisClient.Rejected.class, client.analyze(request(), "c_test"));
        // §2.4에서 AUDIO_TOO_QUIET는 재녹음으로 해결되는 코드다 - 생략을 false로 읽으면
        // 재녹음이 도움이 되는 실패가 FAILED로 굳어 문항이 막힌다 (Codex 리뷰)
        assertEquals("AUDIO_TOO_QUIET", rejected.errorCode());
        assertTrue(rejected.retryable());
    }

    @Test
    void 계약에_없는_판정_코드는_비재시도_INTERNAL_ERROR로_끊는다() {
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).body("""
                        { "status": "FAILED", "quality": { "code": "OK" }, "retryable": true }
                        """).contentType(MediaType.APPLICATION_JSON));

        AiAnalysisClient.Rejected rejected =
                assertInstanceOf(AiAnalysisClient.Rejected.class, client.analyze(request(), "c_test"));
        // "OK"는 오류 코드가 아니다 - 미검증 문자열을 DB 컬럼(40자)과 error.code로 흘리지 않는다
        assertEquals("INTERNAL_ERROR", rejected.errorCode());
        assertFalse(rejected.retryable());
    }

    @Test
    void 과부하_429는_예산에서_빠지는_일시_장애로_구분된다() {
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        AiAnalysisClient.AiUnavailableException e =
                assertThrows(AiAnalysisClient.AiUnavailableException.class,
                        () -> client.analyze(request(), "c_test"));
        // 추론 전에 거절된 셰딩이라 GPU 미소모다 - 서버 사정만으로 시도 상한(§2.5)이
        // 깎이면 안 되므로 미도달(UNREACHED)과 같은 예산 취급이어야 한다 (Codex 리뷰)
        assertEquals(AiAnalysisClient.AiUnavailableException.Kind.UNREACHED, e.kind());
    }

    @Test
    void 오류_5xx는_도달한_장애로_구분된다() {
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        AiAnalysisClient.AiUnavailableException e =
                assertThrows(AiAnalysisClient.AiUnavailableException.class,
                        () -> client.analyze(request(), "c_test"));
        // 요청이 AI까지 갔다 - 시도 예산 판정이 미도달과 달라진다 (Codex sol 리뷰 P2)
        assertEquals(AiAnalysisClient.AiUnavailableException.Kind.SERVER_ERROR, e.kind());
    }

    @Test
    void 타임아웃은_timedOut으로_구분된다() {
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andRespond(mockRequest -> {
                    throw new SocketTimeoutException("read timed out");
                });

        AiAnalysisClient.AiUnavailableException e =
                assertThrows(AiAnalysisClient.AiUnavailableException.class,
                        () -> client.analyze(request(), "c_test"));
        assertEquals(AiAnalysisClient.AiUnavailableException.Kind.TIMED_OUT, e.kind());
    }

    @Test
    void 형식이_어긋난_본문은_비재시도_INTERNAL_ERROR다() {
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andRespond(withSuccess("웃는 얼굴의 HTML 점검 페이지", MediaType.TEXT_HTML));

        AiAnalysisClient.Rejected rejected =
                assertInstanceOf(AiAnalysisClient.Rejected.class, client.analyze(request(), "c_test"));
        assertEquals("INTERNAL_ERROR", rejected.errorCode());
        assertEquals(false, rejected.retryable());
    }

    @Test
    void 범위_밖_점수는_채점_오염이라_비재시도로_끊는다() {
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andRespond(withSuccess("""
                        { "status": "OK", "intonationScore": 101,
                          "modelVersion": "rmvpe-0.2", "scoreVersion": "sv-0.3" }
                        """, MediaType.APPLICATION_JSON));

        AiAnalysisClient.Rejected rejected =
                assertInstanceOf(AiAnalysisClient.Rejected.class, client.analyze(request(), "c_test"));
        assertEquals("INTERNAL_ERROR", rejected.errorCode());
    }

    @Test
    void 점수_버전이_요청과_다르면_계약_위반으로_끊는다() {
        // 세션은 생성 시점 버전에 고정된다 (§5.4) - 구버전 AI의 점수를 받으면 채점 버전이 섞인다
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andRespond(withSuccess("""
                        { "status": "OK", "intonationScore": 78, "quality": { "code": "OK" },
                          "modelVersion": "rmvpe-0.1", "scoreVersion": "sv-0.2" }
                        """, MediaType.APPLICATION_JSON));

        AiAnalysisClient.Rejected rejected =
                assertInstanceOf(AiAnalysisClient.Rejected.class, client.analyze(request(), "c_test"));
        assertEquals("INTERNAL_ERROR", rejected.errorCode());
        assertEquals(false, rejected.retryable());
    }

    @Test
    void 판정_코드가_없는_실패는_재녹음_유도_근거가_없어_비재시도다() {
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).body("""
                        { "status": "FAILED", "retryable": true }
                        """).contentType(MediaType.APPLICATION_JSON));

        AiAnalysisClient.Rejected rejected =
                assertInstanceOf(AiAnalysisClient.Rejected.class, client.analyze(request(), "c_test"));
        assertEquals("INTERNAL_ERROR", rejected.errorCode());
        assertEquals(false, rejected.retryable());
    }

    private static AnalysisDispatcher.AnalysisRequest request() {
        return new AnalysisDispatcher.AnalysisRequest("a_client-test", "s_client", "v1",
                "gn-2026.08.1", "sv-0.3", 3000, new byte[] {82, 73, 70, 70});
    }
}
