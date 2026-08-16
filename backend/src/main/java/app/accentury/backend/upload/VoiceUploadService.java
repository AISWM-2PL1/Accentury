package app.accentury.backend.upload;

import app.accentury.backend.analysis.AnalysisDispatcher;
import app.accentury.backend.analysis.AnalysisJob;
import app.accentury.backend.analysis.AnalysisJobRepository;
import app.accentury.backend.analysis.AnalysisJobStatus;
import app.accentury.backend.analysis.PollIntervals;
import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import app.accentury.backend.common.IdempotencyKeys;
import app.accentury.backend.common.RateLimits;
import app.accentury.backend.session.SessionService;
import app.accentury.backend.session.TestSession;
import app.accentury.backend.session.TestSessionRepository;
import app.accentury.backend.testdefinition.TestDefinition;
import app.accentury.backend.testdefinition.TestDefinitionRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 음성 업로드의 검증 파이프라인과 분석 작업 생성 (KAN-23, API 명세서 §3.3).
 * <p>
 * 검증 순서: 세션 인증 -> 세션 요청 제한 -> 멱등 키 -> 문항 -> meta -> 오디오 ->
 * (잠금) 완료 가드 -> 멱등 판별 -> 시도 상한 -> 분석 가용성. 전부 통과해야 작업이 만들어지고,
 * 오디오는 {@link AnalysisDispatcher}로 넘어간 뒤 어디에도 저장되지 않는다 (FR-DP-01).
 * 파기는 두 갈래다 (KAN-27): 요청이 들고 온 수신 버퍼와 임시파일은 컨테이너가 요청 종료
 * 시점에 지우고({@link VoiceTempDirectory}), 분석으로 넘어간 사본은 디스패처가 종결 시점에
 * 0으로 덮어쓴다 - {@code dispatch()} 이후 이 서비스는 오디오를 다시 만지지 않는다.
 * IP 요청 제한은 본문이 파싱되기 전에 {@link UploadRateLimitFilter}가 먼저 끊고,
 * 세션 단위 제한은 인증 직후 여기서 건다 (§2.5 이중 제한, KAN-28).
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

    /**
     * 문항당 업로드 시도 상한 (§2.5, §5.1, 2026-08-09 확정) - GPU 비용 보호.
     * 업로드 전 로컬 재녹음은 서버에 도달하지 않으므로 세지 않는다 (§5.7).
     */
    static final int MAX_ATTEMPTS_PER_ITEM = 5;

    private final SessionService sessionService;
    private final TestDefinitionRegistry registry;
    private final AnalysisJobRepository repository;
    private final TestSessionRepository sessionRepository;
    private final AnalysisDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final PollIntervals pollIntervals;
    private final TransactionTemplate transactionTemplate;
    private final RateLimits rateLimits;

    public VoiceUploadService(SessionService sessionService, TestDefinitionRegistry registry,
                              AnalysisJobRepository repository, TestSessionRepository sessionRepository,
                              AnalysisDispatcher dispatcher, ObjectMapper objectMapper,
                              PollIntervals pollIntervals, TransactionTemplate transactionTemplate,
                              RateLimits rateLimits) {
        this.sessionService = sessionService;
        this.registry = registry;
        this.repository = repository;
        this.sessionRepository = sessionRepository;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.pollIntervals = pollIntervals;
        this.transactionTemplate = transactionTemplate;
        this.rateLimits = rateLimits;
    }

    VoiceUploadResponse upload(String sessionId, String itemId,
                               @Nullable String authorization, @Nullable String idempotencyKey,
                               @Nullable MultipartFile audio, @Nullable String metaJson) {
        TestSession session = sessionService.authenticateBearer(sessionId, authorization);
        // 세션 단위 제한 - IP 제한(필터)은 NAT 뒤 다수 사용자를 고려해 느슨하므로, 세션
        // 하나가 그 여유를 혼자 쓰지 못하게 막는 두 번째 축이다 (§2.5, KAN-28)
        rateLimits.check(RateLimits.Scope.VOICE_UPLOAD_SESSION, session.id());
        String key = IdempotencyKeys.require(idempotencyKey);
        // 반환값은 쓰지 않는다 - 없는 문항(422)과 유형 불일치(409)를 여기서 끊는 것이 목적이다
        registry.requireItem(session.testVersion(), itemId, TestDefinition.ItemType.VOICE);
        VoiceUploadMeta meta = VoiceUploadMeta.parse(objectMapper, metaJson);
        byte[] audioBytes = requireAudio(audio);

        // 이 사본은 컨테이너의 수신 버퍼와 별개다 - 요청 종료 정리가 닿지 않으므로 파기도
        // 우리 몫이다 (KAN-27, Codex sol 리뷰 P1). 소유권은 dispatch() 호출과 함께 넘어가고,
        // 그 전에 끊기는 모든 경로(형식 거절, 만료와 완료 세션, 멱등 재전송, 시도 상한)에서는
        // 여기서 지운다. 호출 이후는 전달이 성공했든 예외로 끝났든 구현의 몫이다
        boolean transferred = false;
        try {
            WavAudio wav = WavAudio.parse(audioBytes);
            if (wav.sampleRate() != SAMPLE_RATE || wav.channels() != CHANNELS
                    || wav.bitsPerSample() != BITS_PER_SAMPLE) {
                throw new ApiException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
            }
            // 길이의 정본은 클라이언트 신고값이 아니라 서버가 WAV에서 계산한 값이다.
            // 상한은 전 문항 공통 상수다 - 앱의 자동 종료와 같은 값이어야 하므로 문항별로 두지 않는다
            if (wav.durationMs() > TestDefinition.VOICE_MAX_DURATION_MS) {
                throw new ApiException(ErrorCode.AUDIO_TOO_LONG);
            }

            // 완료 가드부터 작업 저장까지 세션 행 잠금 아래 한 트랜잭션이다 (KAN-15에서 완료
            // 상태 도입, Codex sol 리뷰 P2) - 잠금이 없으면 /complete(KAN-16)가 검사와 저장
            // 사이에 끼어들어 확정된 세션이 GPU를 소모한다. 동시 업로드도 이 잠금으로
            // 직렬화되므로 (session_id, item_id, idempotency_key) 유니크 제약은 마지막
            // 안전망이고, attempt 번호와 시도 상한 판정도 경합 없이 정확하다
            long pollAfterMs = pollIntervals.pollAfterMs();
            String newJobId = "a_" + UUID.randomUUID();
            // accepts()가 반열림 복구 시험 자리를 이 작업 앞으로 잡을 수 있다 (KAN-28).
            // 그 뒤 트랜잭션이 롤백되면 이 작업은 AI에 닿지 못하는데 자리는 잡힌 채로 남아,
            // 놓아주지 않으면 시험 한도(60초) 동안 다른 업로드가 전부 503이 된다
            AtomicBoolean claimedTrial = new AtomicBoolean();
            AnalysisJob job;
            try {
                job = Objects.requireNonNull(transactionTemplate.execute(tx -> {
                    // 잠금 재조회가 빈 것은 동시 삭제(만료 정리)를 뜻하므로 만료와 같게 응답한다
                    TestSession locked = sessionRepository.lockById(session.id())
                            .orElseThrow(() -> new ApiException(ErrorCode.SESSION_EXPIRED));
                    // 만료도 잠금 아래에서 재확인한다 - 인증(스냅샷 검사)과 잠금 획득 사이에 TTL이
                    // 지나면 만료된 세션의 분석에 GPU가 소모된다. 완료 가드와 같은 이유의 재검사다
                    if (locked.isExpired(Instant.now())) {
                        throw new ApiException(ErrorCode.SESSION_EXPIRED);
                    }
                    // 완료 가드가 멱등 판별보다 먼저다 - 어휘 답안(§3.5)과 같은 규칙이라, 완료 뒤에는
                    // 같은 키의 재전송도 202가 아니라 409를 받는다
                    if (locked.isCompleted()) {
                        throw new ApiException(ErrorCode.SESSION_COMPLETED);
                    }
                    // 같은 키의 재전송은 저장된 작업을 그대로 반환한다 - 분석 중복 생성 없음 (§5.2)
                    var existing = repository.findBySessionIdAndItemIdAndIdempotencyKey(session.id(), itemId, key);
                    if (existing.isPresent()) {
                        return existing.get();
                    }
                    // AI에 도달하지 못한 전달 실패(ANALYSIS_UNAVAILABLE)만 예산에서 뺀다 - 서버 장애로
                    // 예산이 깎이면 5회 연속 장애 시 사용자 잘못 없이 문항이 영구 차단되기 때문이다.
                    // 반대로 AI가 분석까지 한 판정 실패(AUDIO_TOO_QUIET 등)는 GPU를 썼으므로 센다 (Codex sol 리뷰 P1)
                    int attempt = (int) repository.countAiConsumingAttempts(
                            session.id(), itemId, ErrorCode.ANALYSIS_UNAVAILABLE.name()) + 1;
                    // 상한 검사는 멱등 재전송 판별 뒤다 - 같은 키의 재전송은 상한과 무관하게 저장된
                    // 작업을 돌려받는다 (§5.2)
                    if (attempt > MAX_ATTEMPTS_PER_ITEM) {
                        throw new ApiException(ErrorCode.RATE_RETAKE_EXCEEDED);
                    }
                    // AI 회로가 전달을 받지 않으면 새 작업을 만들지 않는다 (KAN-28) - 오디오를
                    // 저장하지 않아(FR-DP-01) 나중에 다시 보낼 수 없으므로, 받아 두고 실패시키는
                    // 것보다 받지 않는 쪽이 정직하다. 작업을 만들지 않으니 시도 예산도 안 깎인다.
                    // 이 검사가 <b>맨 마지막</b>인 것은 의도다 - 반열림에서는 이 호출이 복구 시험
                    // 자리를 이 작업 앞으로 잡으므로(AiCircuitBreaker), 어차피 거절될 요청이 그
                    // 자리를 물고 놓아주지 않으면 나머지 업로드가 시험 한도만큼 막힌다
                    // (Codex sol 리뷰 P2)
                    if (!dispatcher.accepts(newJobId)) {
                        throw new ApiException(ErrorCode.ANALYSIS_UNAVAILABLE);
                    }
                    claimedTrial.set(true);
                    AnalysisJob created = new AnalysisJob(newJobId, session.id(), itemId, attempt,
                            key, AnalysisJobStatus.PROCESSING, Instant.now());
                    repository.save(created);
                    return created;
                }));
            } catch (RuntimeException e) {
                // 저장 실패나 롤백으로 이 작업은 AI에 닿지 못한다 - accepts()가 잡아 둔 복구
                // 시험 자리를 여기서 놓아준다 (KAN-28). 한도 만료를 기다리면 그동안 다른
                // 업로드가 전부 503이고, AI는 이미 살아 있을 수 있다
                if (claimedTrial.get()) {
                    dispatcher.abandon(newJobId);
                }
                throw e;
            }
            if (!job.id().equals(newJobId)) {
                // 잠금 안에서 발견된 같은 키의 재전송 - 새 분석 없이 저장된 작업을 돌려준다
                return VoiceUploadResponse.from(job, pollAfterMs);
            }

            // durationMs는 클라이언트 신고값(meta)이 아니라 서버가 WAV에서 계산한 값을
            // 전달한다 - 신고값이 실제와 다르면 분석이 엉뚱한 메타를 받는다 (Codex sol 리뷰 P2)
            AnalysisDispatcher.AnalysisRequest analysisRequest = new AnalysisDispatcher.AnalysisRequest(
                    job.id(), session.id(), itemId, session.testVersion(), session.scoreVersion(),
                    wav.durationMs(), audioBytes);
            // 소유권은 반환이 아니라 호출과 함께 넘어간다 - 계약(AnalysisDispatcher)이 그렇게
            // 정의되어 있고, 예외로 끝난 경우의 파기도 구현의 몫이다. 반환 뒤에 세우면
            // "제출에는 성공하고 그 뒤에 던지는" 구현(계측 데코레이터, 향후 AOP)에서 살아
            // 있는 워커의 버퍼를 아래 finally가 0으로 덮어쓴다 (Codex 리뷰)
            transferred = true;
            try {
                dispatcher.dispatch(analysisRequest);
            } catch (RuntimeException e) {
                // 전달 실패를 PROCESSING으로 두면 오디오가 없어 영영 끝나지 않는다 (FR-DP-01) -
                // 재녹음(새 키)을 유도하는 RETRYABLE_FAILED로 전이하고 503을 준다 (Codex sol 리뷰 P1).
                // 같은 키의 재전송은 이 상태를 그대로 돌려받아 새 시도로 넘어갈 수 있다.
                // 저장과 전달 사이에 프로세스가 죽어 PROCESSING으로 남는 경우는 AnalysisJobTimeout이 정리한다.
                // 버퍼는 여기서 손대지 않는다 - 소유권이 이미 넘어갔고, 파기는 계약상 구현의 몫이다
                job.markRetryableFailed(ErrorCode.ANALYSIS_UNAVAILABLE.name());
                repository.save(job);
                // 워커가 뜨지 못했으니 복구 시험 자리를 놓아줄 주체도 여기뿐이다 (KAN-28) -
                // 큐가 가득 차 제출이 거절된 경우가 대표적이고, 그대로 두면 시험 한도(60초)
                // 동안 나머지 업로드가 전부 503이 된다
                dispatcher.abandon(job.id());
                log.warn("분석 전달 실패 jobId={} itemId={}", job.id(), itemId, e);
                throw new ApiException(ErrorCode.ANALYSIS_UNAVAILABLE);
            }

            // 오디오 바이트의 소유권은 디스패처로 넘어갔다 (KAN-27) - 여기서 다시 읽지 않고,
            // 로그 포함 어디에도 남기지 않는다 (§2.6)
            log.info("음성 업로드 접수 sessionId={} itemId={} attempt={} jobId={}",
                    session.id(), itemId, job.attempt(), job.id());
            return VoiceUploadResponse.from(job, pollAfterMs);
        } finally {
            if (!transferred) {
                // 디스패처가 쓰는 것과 같은 파기다 - 한쪽만 바뀌지 않게 공용 메서드를 쓴다
                AnalysisDispatcher.AnalysisRequest.wipe(audioBytes);
            }
        }
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
