package app.accentury.backend.training;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S3 왕복 없이 객체 규약(키, Content-Type, 메타 필드)과 실패 삼킴을 본다 (KAN-201). */
class S3TrainingSampleStoreTest {

    private static final Instant SAVED_AT = Instant.parse("2026-09-11T06:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void WAV와_JSON이_같은_키_접두에_나란히_놓인다() throws IOException {
        RecordingS3 s3 = new RecordingS3();
        store(s3).save(completed());

        assertEquals(2, s3.puts.size());
        Put wav = s3.puts.get(0);
        Put json = s3.puts.get(1);
        assertEquals("training-bucket", wav.request().bucket());
        assertEquals("GYEONGNAM/gn-2026.09.2/s_1/v3/a_9.wav", wav.request().key());
        assertEquals("audio/wav", wav.request().contentType());
        assertArrayEquals(new byte[] {82, 73, 70, 70}, wav.body(), "업로드 받은 바이트 그대로다");
        assertEquals("GYEONGNAM/gn-2026.09.2/s_1/v3/a_9.json", json.request().key());
        assertEquals("application/json", json.request().contentType());
        assertEquals(1.0, registry.get("accentury.training.samples").tag("result", "saved").counter().count());
    }

    @Test
    void 성공_샘플의_메타는_원점수와_AI_버전을_싣고_오류_코드는_없다() throws IOException {
        RecordingS3 s3 = new RecordingS3();
        store(s3).save(completed());

        JsonNode meta = objectMapper.readTree(s3.puts.get(1).body());
        assertEquals("a_9", meta.get("analysisJobId").asString());
        assertEquals("s_1", meta.get("sessionId").asString());
        assertEquals("v3", meta.get("itemId").asString());
        assertEquals("GYEONGNAM", meta.get("region").asString());
        assertEquals("1|3", meta.get("scriptKey").asString());
        assertEquals("gn-2026.09.2", meta.get("testVersion").asString());
        assertEquals("sv-0.4", meta.get("scoreVersion").asString());
        assertEquals(2450, meta.get("durationMs").asLong());
        assertEquals("COMPLETED", meta.get("outcome").asString());
        assertEquals(78, meta.get("intonationScore").asInt());
        assertEquals("OK", meta.get("qualityCode").asString());
        assertEquals("rmvpe-0.2", meta.get("modelVersion").asString());
        assertEquals("sv-ai-0.1", meta.get("aiScoreVersion").asString());
        assertFalse(meta.has("errorCode"));
        assertEquals("c_abc", meta.get("correlationId").asString());
        assertEquals("2026-09-11T06:00:00Z", meta.get("savedAt").asString());
    }

    @Test
    void 판정_실패_샘플의_메타는_오류_코드만_있고_점수_자리는_없다() throws IOException {
        RecordingS3 s3 = new RecordingS3();
        store(s3).save(new TrainingSample("a_9", "s_1", "v3", "UNKNOWN", null, "gn-2026.09.2", "sv-0.4",
                2450, TrainingSample.Outcome.RETRYABLE_FAILED, null, null, null, null, "AUDIO_TOO_QUIET",
                "c_abc", new byte[] {82, 73, 70, 70}));

        JsonNode meta = objectMapper.readTree(s3.puts.get(1).body());
        assertEquals("RETRYABLE_FAILED", meta.get("outcome").asString());
        assertEquals("AUDIO_TOO_QUIET", meta.get("errorCode").asString());
        assertFalse(meta.has("intonationScore"));
        assertFalse(meta.has("qualityCode"));
        assertFalse(meta.has("modelVersion"));
        assertFalse(meta.has("aiScoreVersion"));
        assertFalse(meta.has("scriptKey"), "더미 정의의 문항은 대본 키가 없다");
        assertTrue(s3.puts.get(0).request().key().startsWith("UNKNOWN/"));
    }

    @Test
    void S3_실패는_삼키고_카운터만_올린다() {
        S3Client failing = new RecordingS3() {
            @Override
            public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
                throw S3Exception.builder().message("AccessDenied").statusCode(403).build();
            }
        };

        assertDoesNotThrow(() -> store(failing).save(completed()));

        assertEquals(1.0, registry.get("accentury.training.samples").tag("result", "failed").counter().count());
        assertEquals(0.0, registry.get("accentury.training.samples").tag("result", "saved").counter().count());
    }

    private S3TrainingSampleStore store(S3Client s3) {
        return new S3TrainingSampleStore(s3, "training-bucket", objectMapper,
                Clock.fixed(SAVED_AT, ZoneOffset.UTC), registry);
    }

    private static TrainingSample completed() {
        return new TrainingSample("a_9", "s_1", "v3", "GYEONGNAM", "1|3", "gn-2026.09.2", "sv-0.4",
                2450, TrainingSample.Outcome.COMPLETED, 78, "OK", "rmvpe-0.2", "sv-ai-0.1", null,
                "c_abc", new byte[] {82, 73, 70, 70});
    }

    private record Put(PutObjectRequest request, byte[] body) {
    }

    /** putObject 호출을 기록하는 가짜 클라이언트 - 본문은 스트림을 끝까지 읽어 둔다. */
    private static class RecordingS3 implements S3Client {
        final List<Put> puts = new ArrayList<>();

        @Override
        public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
            try (InputStream in = body.contentStreamProvider().newStream()) {
                puts.add(new Put(request, in.readAllBytes()));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return PutObjectResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return SERVICE_NAME;
        }

        @Override
        public void close() {
        }
    }
}
