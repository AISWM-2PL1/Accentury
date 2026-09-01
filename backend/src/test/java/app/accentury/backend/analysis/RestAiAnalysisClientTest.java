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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
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
        // 분석과 health가 같은 서버를 보게 한다 - 타임아웃만 다른 두 클라이언트다.
        RestClient restClient = builder.build();
        client = new RestAiAnalysisClient(restClient, restClient, new ObjectMapper(), TOKEN);
    }

    /** 내부 호출 시크릿 (KAN-36) - AI가 요청마다 대조하는 값. */
    private static final String TOKEN = "shared-secret-0123456789abcdef0123456789abcdef";

    @Test
    void 성공_응답을_Completed로_매핑한다() {
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andExpect(method(POST))
                .andExpect(header("X-Correlation-Id", "c_test"))
                // AI가 전용 호스트라 요청마다 공유 시크릿을 대조한다 (KAN-36) - 없으면 401로 끊긴다.
                .andExpect(header("X-Accentury-Internal-Token", TOKEN))
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
        // 계약대로 온 판정이다 - AI는 살아 있으므로 회로에 실패로 세면 안 된다 (KAN-28).
        assertEquals(AiAnalysisClient.Rejected.Cause.JUDGED, rejected.cause());
    }

    @Test
    void 계약_위반_응답은_판정과_다른_사유로_구분된다() {
        // 둘 다 재전송하지 않는 Rejected지만 "AI가 정상인가"가 다르다 - 이 구분이 없으면
        // 응답만 하고 고장 난 AI 앞에서 회로가 영영 닫혀 있는다 (KAN-28).
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andRespond(withStatus(HttpStatus.OK).body("""
                        { "status": "OK", "intonationScore": 999, "modelVersion": "rmvpe-0.2",
                          "scoreVersion": "sv-0.3" }
                        """).contentType(MediaType.APPLICATION_JSON));

        AiAnalysisClient.Rejected rejected =
                assertInstanceOf(AiAnalysisClient.Rejected.class, client.analyze(request(), "c_test"));

        assertEquals("INTERNAL_ERROR", rejected.errorCode());
        assertFalse(rejected.retryable(), "같은 오디오에 같은 답이 온다");
        assertEquals(AiAnalysisClient.Rejected.Cause.CONTRACT_VIOLATION, rejected.cause());
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
        // 재녹음이 도움이 되는 실패가 FAILED로 굳어 문항이 막힌다 (Codex 리뷰).
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
        // "OK"는 오류 코드가 아니다 - 미검증 문자열을 DB 컬럼(40자)과 error.code로 흘리지 않는다.
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
        // 깎이면 안 되므로 미도달(UNREACHED)과 같은 예산 취급이어야 한다 (Codex 리뷰).
        assertEquals(AiAnalysisClient.AiUnavailableException.Kind.UNREACHED, e.kind());
    }

    @Test
    void 오류_5xx는_도달한_장애로_구분된다() {
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        AiAnalysisClient.AiUnavailableException e =
                assertThrows(AiAnalysisClient.AiUnavailableException.class,
                        () -> client.analyze(request(), "c_test"));
        // 요청이 AI까지 갔다 - 시도 예산 판정이 미도달과 달라진다 (Codex sol 리뷰 P2).
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
        // 세션은 생성 시점 버전에 고정된다 (§5.4) - 구버전 AI의 점수를 받으면 채점 버전이 섞인다.
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

    // === 회로 복구 프로브 (KAN-28, §4.2) ===

    @Test
    void health가_UP이면_살아_있는_것으로_본다() {
        server.expect(requestTo("http://ai.test/internal/v0/health"))
                .andExpect(method(GET))
                .andExpect(header("X-Accentury-Internal-Token", TOKEN))
                .andRespond(withSuccess("{ \"status\": \"UP\" }", MediaType.APPLICATION_JSON));

        assertTrue(client.healthy());
    }

    @Test
    void 토큰이_없으면_헤더를_붙이지_않는다() {
        // 로컬 개발(토큰 미설정) - 배포 프로파일은 값을 요구하므로(DeploymentConfigGuard) 여기만 지나는 경로다.
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        MockRestServiceServer local = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        RestAiAnalysisClient noToken = new RestAiAnalysisClient(restClient, restClient, new ObjectMapper(), null);
        local.expect(requestTo("http://ai.test/internal/v0/health"))
                .andExpect(headerDoesNotExist("X-Accentury-Internal-Token"))
                .andRespond(withSuccess("{ \"status\": \"UP\" }", MediaType.APPLICATION_JSON));

        assertTrue(noToken.healthy());
    }

    @Test
    void 인증_거절_401과_403은_예산에서_빠지는_일시_장애로_구분된다() {
        // 토큰이 어긋난 배포는 서버 설정 문제다 - 계약 위반(비재시도 FAILED)으로 접으면 토큰 회전 중의
        // 몇 초가 사용자 문항과 시도 상한을 태운다 (리뷰 P1). 429와 같이 추론 전 거절이라 UNREACHED다 -
        // 회로는 실패로 세어 열리고, 소진 시 ANALYSIS_UNAVAILABLE로 재업로드를 열어 둔다.
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("""
                        { "status": "FAILED", "detail": "내부 호출 토큰이 없거나 다르다" }
                        """).contentType(MediaType.APPLICATION_JSON));
        AiAnalysisClient.AiUnavailableException unauthorized =
                assertThrows(AiAnalysisClient.AiUnavailableException.class,
                        () -> client.analyze(request(), "c_test"));
        assertEquals(AiAnalysisClient.AiUnavailableException.Kind.UNREACHED, unauthorized.kind());

        server.reset();
        server.expect(requestTo("http://ai.test/internal/v0/analyze"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        AiAnalysisClient.AiUnavailableException forbidden =
                assertThrows(AiAnalysisClient.AiUnavailableException.class,
                        () -> client.analyze(request(), "c_test"));
        assertEquals(AiAnalysisClient.AiUnavailableException.Kind.UNREACHED, forbidden.kind());
    }

    @Test
    void health가_UP이_아니면_아직_복구가_아니다() {
        // 프로세스는 떴지만 모델이 아직 안 올라온 상태(KAN-22가 채운다)를 UP으로 읽으면,
        // 회로를 열어 준 직후 사용자 요청이 다시 실패한다.
        server.expect(requestTo("http://ai.test/internal/v0/health"))
                .andRespond(withSuccess("{ \"status\": \"WARMING_UP\" }", MediaType.APPLICATION_JSON));

        assertFalse(client.healthy());
    }

    @Test
    void health가_5xx거나_본문이_계약과_다르면_실패로_본다() {
        server.expect(requestTo("http://ai.test/internal/v0/health"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertFalse(client.healthy());

        server.reset();
        server.expect(requestTo("http://ai.test/internal/v0/health"))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        assertFalse(client.healthy());
    }

    @Test
    void health는_연결_실패에도_예외를_던지지_않는다() {
        // 프로브 실패는 "아직 아니다"가 전부다 - 스케줄 잡으로 예외가 새면 다음 주기가 멎는다.
        server.expect(requestTo("http://ai.test/internal/v0/health"))
                .andRespond(request -> {
                    throw new java.net.SocketTimeoutException("연결 지연 시뮬레이션");
                });

        assertFalse(client.healthy());
    }

    private static AnalysisDispatcher.AnalysisRequest request() {
        return new AnalysisDispatcher.AnalysisRequest("a_client-test", "s_client", "v1",
                "gn-2026.08.1", "sv-0.3", 3000, new byte[] {82, 73, 70, 70});
    }
}
