package app.accentury.backend.upload;

import app.accentury.backend.analysis.AnalysisDispatcher;
import app.accentury.backend.analysis.AnalysisJob;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobStatus;
import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import app.accentury.backend.session.SessionService;
import app.accentury.backend.session.TestSession;
import app.accentury.backend.testdefinition.TestDefinition;
import app.accentury.backend.testdefinition.TestDefinitionRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 음성 업로드의 검증 파이프라인과 분석 작업 생성 (KAN-23, API 명세서 §3.3).
 * <p>
 * 검증 순서: 요청 제한 -> 세션 인증 -> 문항 -> meta -> 오디오. 전부 통과해야
 * 작업이 만들어지고, 오디오는 {@link AnalysisDispatcher}로 넘어간 뒤 이 요청
 * 스코프와 함께 소멸한다 - 어디에도 저장하지 않는다 (FR-DP-01).
 */
@Service
public class VoiceUploadService {

    private static final Logger log = LoggerFactory.getLogger(VoiceUploadService.class);

    /** §3.3 - 오디오 파트 상한 1MB. 컨테이너의 multipart 상한(초과 시 413)과 별개의 정본 검증이다 */
    static final long MAX_AUDIO_BYTES = 1_048_576;

    /** §3.3 - WAV 16kHz Mono 16-bit PCM만 받는다 */
    static final int SAMPLE_RATE = 16_000;
    static final int CHANNELS = 1;
    static final int BITS_PER_SAMPLE = 16;

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    private final UploadRateLimiter rateLimiter;
    private final SessionService sessionService;
    private final TestDefinitionRegistry registry;
    private final AnalysisJobRepository repository;
    private final AnalysisDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final AccenturyProperties properties;

    public VoiceUploadService(UploadRateLimiter rateLimiter, SessionService sessionService,
                              TestDefinitionRegistry registry, AnalysisJobRepository repository,
                              AnalysisDispatcher dispatcher, ObjectMapper objectMapper,
                              AccenturyProperties properties) {
        this.rateLimiter = rateLimiter;
        this.sessionService = sessionService;
        this.registry = registry;
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    VoiceUploadResponse upload(String sessionId, String itemId,
                               @Nullable String authorization, @Nullable String idempotencyKey,
                               @Nullable MultipartFile audio, @Nullable String metaJson,
                               String clientIp) {
        rateLimiter.check(clientIp);
        TestSession session = sessionService.authenticateBearer(sessionId, authorization);
        String key = requireIdempotencyKey(idempotencyKey);
        TestDefinition.Item item =
                registry.requireItem(session.testVersion(), itemId, TestDefinition.ItemType.VOICE);
        VoiceUploadMeta meta = VoiceUploadMeta.parse(objectMapper, metaJson);
        byte[] audioBytes = requireAudio(audio);

        WavAudio wav = WavAudio.parse(audioBytes);
        if (wav.sampleRate() != SAMPLE_RATE || wav.channels() != CHANNELS
                || wav.bitsPerSample() != BITS_PER_SAMPLE) {
            throw new ApiException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
        }
        // 길이의 정본은 서버가 계산한 값이다. 상한은 문항 정의(KAN-10)가 정하고,
        // VOICE 문항의 maxDurationMs는 발행 검증이 보장한다
        int maxDurationMs = Objects.requireNonNull(item.maxDurationMs());
        if (wav.durationMs() > maxDurationMs) {
            throw new ApiException(ErrorCode.AUDIO_TOO_LONG);
        }

        // 같은 키의 재전송은 저장된 작업을 그대로 반환한다 - 분석 중복 생성 없음 (§5.2)
        long pollAfterMs = properties.analysis().pollAfterMs();
        var existing = repository.findBySessionIdAndItemIdAndIdempotencyKey(session.id(), itemId, key);
        if (existing.isPresent()) {
            return VoiceUploadResponse.from(existing.get(), pollAfterMs);
        }

        // 동시 업로드가 같은 번호를 받을 수 있지만 attempt는 표시용이라 무해하다 -
        // 채점 대상 선정(§5.1)은 createdAt 기준이고, 중복 분석 방지는 유니크 제약이 맡는다
        int attempt = (int) repository.countBySessionIdAndItemId(session.id(), itemId) + 1;
        AnalysisJob job = new AnalysisJob("a_" + UUID.randomUUID(), session.id(), itemId, attempt,
                key, AnalysisJobStatus.PROCESSING, Instant.now());
        try {
            repository.save(job);
        } catch (DataIntegrityViolationException e) {
            // 같은 키가 동시에 들어온 경합 - 먼저 저장된 쪽을 반환한다 (§5.2)
            return repository.findBySessionIdAndItemIdAndIdempotencyKey(session.id(), itemId, key)
                    .map(winner -> VoiceUploadResponse.from(winner, pollAfterMs))
                    .orElseThrow(() -> e);
        }

        dispatcher.dispatch(new AnalysisDispatcher.AnalysisRequest(
                job.id(), session.id(), itemId, session.testVersion(), session.scoreVersion(),
                meta.requiredDurationMs(), audioBytes));

        // 오디오 바이트는 여기서 참조가 끝난다 - 로그 포함 어디에도 남기지 않는다 (§2.6)
        log.info("음성 업로드 접수 sessionId={} itemId={} attempt={} jobId={}",
                session.id(), itemId, attempt, job.id());
        return VoiceUploadResponse.from(job, pollAfterMs);
    }

    private static String requireIdempotencyKey(@Nullable String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key 헤더가 필요합니다.");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key가 너무 깁니다. (최대 " + MAX_IDEMPOTENCY_KEY_LENGTH + "자)");
        }
        return idempotencyKey;
    }

    private static byte[] requireAudio(@Nullable MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "audio 파트가 필요합니다.");
        }
        if (audio.getSize() > MAX_AUDIO_BYTES) {
            throw new ApiException(ErrorCode.AUDIO_TOO_LARGE);
        }
        try {
            return audio.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "audio 파트를 읽을 수 없습니다.");
        }
    }
}
