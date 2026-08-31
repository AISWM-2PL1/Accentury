package app.accentury.backend.analysis;

import app.accentury.backend.BackendApplication;
import app.accentury.backend.PostgresTestcontainer;
import app.accentury.backend.TestSessions;
import app.accentury.backend.session.TestSessionRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SIGTERM 시나리오 (KAN-166 AC) - 컨텍스트 close가 SIGTERM의 종료 훅이 하는 일 그 자체다.
 * <p>
 * 캐시되는 테스트 컨텍스트를 닫을 수는 없으므로 별도 컨텍스트를 직접 띄우고 닫는다. AI는
 * JDK 내장 HTTP 서버가 흉내 내며 첫 분석에 1.5초를 쓴다 - 종료 신호 시점에 "실행 중" 1건과
 * "대기" 2건이 있는 상태를 만들고, close가 돌아온 뒤 DB와 로그 순서를 본다.
 */
class GracefulShutdownBootTest {

    @Test
    void 컨텍스트_close가_readiness_하강_요청_차단_워커_배수_순서로_진행_중_분석을_마친다() throws Exception {
        FakeAi ai = FakeAi.start();
        ListAppender<ILoggingEvent> drainLog = new ListAppender<>();
        drainLog.start();
        // 기동 뒤에 붙인다 - Boot가 기동 중 Logback 컨텍스트를 리셋해 그 전에 붙인 appender는 떨어진다.
        Logger logger = null;
        ConfigurableApplicationContext context = null;
        AnalysisDispatcher.AnalysisRequest[] requests = new AnalysisDispatcher.AnalysisRequest[3];
        AnalysisJob[] jobs = new AnalysisJob[3];
        try {
            // PostgresTestcontainer를 이 컨텍스트의 빈으로 넣지 않는다 - Boot의 Testcontainers 수명주기
            // 후처리기가 컨텍스트 close에 컨테이너까지 세워, 같은 JVM의 캐시된 컨텍스트들이 전부
            // "Connection refused"로 무너진다 (첫 실행에서 실증). 공용 컨테이너의 접속 정보만 빌린다.
            // 명령행 인자는 application-test.yml의 가드 URL(.invalid)보다 우선한다.
            context = new SpringApplicationBuilder(BackendApplication.class)
                    .profiles("test")
                    .run("--server.port=0",
                            "--spring.datasource.url=" + jdbcUrl(),
                            "--spring.datasource.username=" + PostgresTestcontainer.username(),
                            "--spring.datasource.password=" + PostgresTestcontainer.password(),
                            "--accentury.analysis.ai-base-url=" + ai.baseUrl(),
                            // 워커 1 - 첫 작업이 AI에 묶인 동안 나머지 둘은 큐에 남는다.
                            "--accentury.analysis.dispatch-concurrency=1",
                            "--accentury.analysis.shutdown-budget=20s",
                            "--spring.lifecycle.timeout-per-shutdown-phase=5s");
            AnalysisJobRepository repository = context.getBean(AnalysisJobRepository.class);
            AnalysisDispatcher dispatcher = context.getBean(AnalysisDispatcher.class);
            TestSessions.ensure(context.getBean(TestSessionRepository.class), "s_boot_shutdown");
            for (int i = 0; i < 3; i++) {
                jobs[i] = repository.save(new AnalysisJob("a_" + UUID.randomUUID(), "s_boot_shutdown",
                        "v" + (i + 1), 1, "idem-" + UUID.randomUUID(), AnalysisJobStatus.PROCESSING,
                        Instant.now()));
                requests[i] = new AnalysisDispatcher.AnalysisRequest(jobs[i].id(), "s_boot_shutdown",
                        jobs[i].itemId(), "gn-2026.08.1", "sv-0.3", 3000, new byte[] {1, 2, 3});
                dispatcher.dispatch(requests[i]);
            }
            assertTrue(ai.arrived.await(10, TimeUnit.SECONDS), "첫 분석이 AI에 도달해야 한다");
            logger = (Logger) LoggerFactory.getLogger(AnalysisDrainLifecycle.class);
            logger.addAppender(drainLog);

            // SIGTERM - Boot의 종료 훅이 부르는 것과 같은 호출이다. 배수가 끝나야 돌아온다.
            context.close();

            // 닫힌 컨텍스트의 리포지토리는 쓸 수 없다 - 결과가 DB에 남았는지는 별도 연결로 읽는다.
            try (Connection connection = DriverManager.getConnection(jdbcUrl(),
                    PostgresTestcontainer.username(), PostgresTestcontainer.password())) {
                Row first = row(connection, jobs[0].id());
                assertEquals("COMPLETED", first.status());
                assertEquals(75, first.intonationScore());
                for (int i = 1; i < 3; i++) {
                    Row queued = row(connection, jobs[i].id());
                    assertEquals("RETRYABLE_FAILED", queued.status(), "대기 작업 " + i);
                    assertEquals("ANALYSIS_UNAVAILABLE", queued.errorCode());
                }
            }
            for (AnalysisDispatcher.AnalysisRequest request : requests) {
                assertArrayEquals(new byte[] {0, 0, 0}, request.audio());
            }
            assertEquals(1, ai.analyzeCalls.get(), "대기 작업은 AI를 부르지 않는다");

            List<String> messages = drainLog.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertEquals(4, messages.size(), "종료 순서 로그 4줄: " + messages);
            assertTrue(messages.get(0).startsWith("종료 1/4 readiness REFUSING_TRAFFIC"), messages.get(0));
            assertTrue(messages.get(1).startsWith("종료 2/4 웹 서버 정지 - 대기 2건 즉시 실패, 실행 중 1건"), messages.get(1));
            assertTrue(messages.get(2).startsWith("종료 3/4 워커 배수 완료 - 실행 중 1건 중 예산 안 종결 1건, 예산 초과 실패 0건"),
                    messages.get(2));
            assertTrue(messages.get(3).startsWith("종료 4/4 분석 전달 풀 종료 - 남은 진행 중 0건"), messages.get(3));
        } finally {
            if (logger != null) {
                logger.detachAppender(drainLog);
            }
            if (context != null && context.isActive()) {
                context.close();
            }
            ai.stop();
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + PostgresTestcontainer.host() + ":" + PostgresTestcontainer.port()
                + "/" + PostgresTestcontainer.database();
    }

    private static Row row(Connection connection, String jobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select status, error_code, intonation_score from analysis_job where id = ?")) {
            statement.setString(1, jobId);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next(), "작업 행이 있어야 한다 " + jobId);
                int score = rs.getInt("intonation_score");
                // wasNull()은 직전에 읽은 컬럼 기준이다 - 다른 컬럼을 읽기 전에 판정한다.
                Integer intonationScore = rs.wasNull() ? null : score;
                return new Row(rs.getString("status"), rs.getString("error_code"), intonationScore);
            }
        }
    }

    private record Row(String status, String errorCode, Integer intonationScore) {
    }

    /** §4.1과 §4.2를 흉내 내는 AI - 분석은 1.5초 뒤에 답한다. */
    static final class FakeAi {

        final CountDownLatch arrived = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger analyzeCalls = new java.util.concurrent.atomic.AtomicInteger();
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService handlers;

        private FakeAi(HttpServer server, java.util.concurrent.ExecutorService handlers) {
            this.server = server;
            this.handlers = handlers;
        }

        static FakeAi start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // 분석 핸들러가 1.5초를 잡고 있는 동안 health 프로브가 같은 스레드에 막히지 않게 둘 이상 쓴다.
            java.util.concurrent.ExecutorService handlers = java.util.concurrent.Executors.newFixedThreadPool(2);
            FakeAi ai = new FakeAi(server, handlers);
            server.createContext(RestAiAnalysisClient.HEALTH_PATH, exchange ->
                    respond(exchange, 200, "{\"status\":\"UP\"}"));
            server.createContext(RestAiAnalysisClient.ANALYZE_PATH, exchange -> {
                ai.analyzeCalls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                ai.arrived.countDown();
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, "{\"status\":\"OK\",\"intonationScore\":75,\"quality\":{\"code\":\"OK\"},"
                        + "\"modelVersion\":\"fake-0.1\",\"scoreVersion\":\"sv-0.3\"}");
            });
            server.setExecutor(handlers);
            server.start();
            return ai;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
            // HttpServer.stop()은 직접 넘긴 실행기를 닫지 않는다 - 비데몬 스레드가 남아 러너가 안 끝난다 (Codex sol 리뷰 P2).
            handlers.shutdownNow();
        }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String json)
                throws IOException {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
