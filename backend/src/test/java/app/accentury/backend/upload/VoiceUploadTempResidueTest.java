package app.accentury.backend.upload;

import app.accentury.backend.DatabaseWipeExtension;
import app.accentury.backend.PostgresTestcontainer;
import app.accentury.backend.analysis.AnalysisDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 모든 종료 경로에서 원본 음성이 디스크에 남지 않는다 (KAN-27 AC-1).
 * <p>
 * MockMvc는 multipart를 직접 조립해 넘기므로 컨테이너의 파싱과 임시파일 처리를 건드리지
 * 못한다 - 이 검증만은 실제 Tomcat을 띄우고 진짜 multipart 본문을 보낸다. 그래서
 * {@code IntegrationTest}(MOCK 환경)를 상속하지 않고 웹 환경을 직접 지정한다.
 * <p>
 * 정상 경로에서는 임시파일이 애초에 만들어지지 않는 것이 정본이고
 * ({@link VoiceTempDirectory}의 메모리 전용 불변식), 이 테스트는 그 결과를 종료 경로마다
 * 확인한다 - 설정이 뒤집히면 여기서 잔존이 잡힌다.
 * <p>
 * 임시 디렉터리는 이 클래스 전용이다 (Codex 리뷰). {@code assertNoResidue()}가 "디렉터리가
 * 통째로 비어 있다"를 주장하는데, 프로파일 기본값을 쓰면 {@code VoiceTempSweeperTest}가
 * 같은 디렉터리에 잔존 파일을 만들어 두고 검증한다 - 그쪽 정리가 한 번 어긋나거나 포크가
 * 나뉘어 동시에 돌면, 업로드와 무관한 이유로 여기 전 테스트가 깨진다.
 * {@code spring.servlet.multipart.location}은 main에서 이 값을 참조하므로 함께 따라온다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "accentury.upload.temp-dir=${java.io.tmpdir}/accentury-voice-tmp-residue-test")
@ActiveProfiles("test")
// IntegrationTest를 상속하지 못하므로 (웹 환경 직접 지정) 테스트 DB 컨테이너와 클래스 단위
// 격리를 직접 결선한다 (KAN-123 - IntegrationTest javadoc의 안내와 같은 두 개 한 벌).
@Import(PostgresTestcontainer.class)
@ExtendWith(DatabaseWipeExtension.class)
class VoiceUploadTempResidueTest {

    @TestConfiguration
    static class ToggleableDispatcherConfig {

        @Bean
        @Primary
        ToggleableDispatcher toggleableDispatcher() {
            return new ToggleableDispatcher();
        }
    }

    /** 전달 실패 종료 경로를 만들기 위한 스위치 - 기본은 성공이다. */
    static class ToggleableDispatcher implements AnalysisDispatcher {

        volatile boolean failing;

        @Override
        public void dispatch(AnalysisRequest request) {
            request.wipeAudio();
            if (failing) {
                throw new IllegalStateException("AI 연결 실패 시뮬레이션");
            }
        }
    }

    private static final String VALID_META = """
            {"durationMs": 3000,
             "clientQuality": {"rms": 0.11, "peak": 0.83, "silenceRatio": 0.12, "clipped": false}}""";

    private static final String BOUNDARY = "----accentury-temp-residue";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VoiceTempDirectory tempDirectory;

    @Autowired
    private ToggleableDispatcher dispatcher;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void 전달은_기본적으로_성공한다() {
        dispatcher.failing = false;
    }

    @Test
    void 정상_업로드_후에_임시파일이_남지_않는다() throws Exception {
        SessionHandle session = createSession();

        HttpResponse<String> response = upload(session, "residue-ok",
                WavFixtures.standardWav(3000), VALID_META);

        assertEquals(202, response.statusCode());
        assertNoResidue();
    }

    @Test
    void 형식_거절_후에도_임시파일이_남지_않는다() throws Exception {
        SessionHandle session = createSession();

        HttpResponse<String> response = upload(session, "residue-format",
                WavFixtures.wav(8_000, 1, 16, 3000), VALID_META);

        assertEquals(415, response.statusCode());
        assertNoResidue();
    }

    @Test
    void 크기_초과로_파싱이_끊겨도_임시파일이_남지_않는다() throws Exception {
        // 컨테이너가 본문을 읽다가 상한에서 끊는 경로 - 애플리케이션 코드가 아예 실행되지
        // 않으므로, 여기서 남는다면 정리가 컨테이너 밖 어딘가에 의존하고 있다는 뜻이다.
        SessionHandle session = createSession();

        HttpResponse<String> response = upload(session, "residue-too-large",
                WavFixtures.standardWav(40_000), VALID_META);

        assertEquals(413, response.statusCode());
        assertNoResidue();
    }

    @Test
    void 검증_실패_후에도_임시파일이_남지_않는다() throws Exception {
        SessionHandle session = createSession();

        HttpResponse<String> response = upload(session, "residue-meta",
                WavFixtures.standardWav(3000), "{\"durationMs\": 3000}");

        assertEquals(400, response.statusCode());
        assertNoResidue();
    }

    @Test
    void 전달_실패_후에도_임시파일이_남지_않는다() throws Exception {
        SessionHandle session = createSession();
        dispatcher.failing = true;

        HttpResponse<String> response = upload(session, "residue-dispatch-fail",
                WavFixtures.standardWav(3000), VALID_META);

        assertEquals(503, response.statusCode());
        assertNoResidue();
    }

    @Test
    void 인증_실패로_일찍_끊겨도_임시파일이_남지_않는다() throws Exception {
        SessionHandle session = new SessionHandle(createSession().id(), "st_no-such-token");

        HttpResponse<String> response = upload(session, "residue-unauthorized",
                WavFixtures.standardWav(3000), VALID_META);

        assertEquals(401, response.statusCode());
        assertNoResidue();
    }

    // === 헬퍼 ===

    private void assertNoResidue() throws IOException {
        assertTrue(Files.isDirectory(tempDirectory.directory()),
                "전용 임시 디렉터리가 기동 시 준비돼 있어야 한다");
        try (Stream<Path> entries = Files.list(tempDirectory.directory())) {
            List<Path> residue = entries.toList();
            // 파일명은 로그에 남기지 않는 값이지만, 실패 메시지는 개발자만 보는 로컬 산출물이라
            // 개수만으로는 원인을 못 좁힌다 - 이름을 붙여 어떤 파트가 남았는지 알 수 있게 한다.
            assertTrue(residue.isEmpty(), "임시파일 잔존: " + residue);
        }
    }

    private record SessionHandle(String id, String token) {
    }

    private SessionHandle createSession() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/v0/sessions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());
        JsonNode json = objectMapper.readTree(response.body());
        return new SessionHandle(json.get("sessionId").asString(),
                json.get("sessionToken").asString());
    }

    private HttpResponse<String> upload(SessionHandle session, String idempotencyKey,
                                        byte[] audio, String meta) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/v0/sessions/" + session.id()
                        + "/voice-items/v1/recording"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer " + session.token())
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(audio, meta)))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    /** 실제 multipart 본문 - MockMvc가 건너뛰는 컨테이너 파싱을 그대로 태우기 위한 것이다. */
    private static byte[] multipartBody(byte[] audio, String meta) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(ascii("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"audio\"; filename=\"recording.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n"));
        body.write(audio);
        body.write(ascii("\r\n--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"meta\"\r\n"
                + "Content-Type: application/json\r\n\r\n"));
        body.write(meta.getBytes(StandardCharsets.UTF_8));
        body.write(ascii("\r\n--" + BOUNDARY + "--\r\n"));
        return body.toByteArray();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
