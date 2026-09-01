package app.accentury.backend.analysis;

import app.accentury.backend.common.CorrelationIdFilter;
import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * {@code POST /internal/v0/analyze}의 RestClient 구현 (KAN-24, API 명세서 §4.1).
 * <p>
 * 클라이언트 업로드 WAV를 그대로 multipart로 패스스루한다. 이 호출이 오디오 바이트의
 * 마지막 사용처다 - 반환과 함께 참조가 끝나 수거된다 (FR-DP-01). AI의 계약 위반
 * (형식이 다른 본문, 범위 밖 점수)은 재전송하지 않는 {@link AiAnalysisClient.Rejected}로
 * 종결한다 - 같은 요청을 다시 보내도 같은 응답이 올 것이기 때문이다. 다만 그 거절에는
 * {@code CONTRACT_VIOLATION} 사유를 달아 회로 차단기가 <b>실패로</b> 세게 한다 (KAN-28) -
 * 응답은 하면서 계약을 어기는 AI는 정상이 아니고, 성공으로 세면 회로가 영영 닫혀 있는다.
 */
class RestAiAnalysisClient implements AiAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(RestAiAnalysisClient.class);

    static final String ANALYZE_PATH = "/internal/v0/analyze";

    static final String HEALTH_PATH = "/internal/v0/health";

    /**
     * AI 서버와 나눠 갖는 내부 호출 시크릿을 싣는 헤더 (KAN-36). AI가 전용 호스트로 갈라져 보안 그룹만으로는
     * "backend만 부른다"를 보장할 수 없어, AI가 요청마다 이 값을 대조한다 ({@code ai/app/auth.py}). 이름은
     * 양쪽 코드에 상수로만 있고 설정이 아니다. health에도 붙인다 - AI는 health를 예외로 두지만 붙여서 손해가 없다.
     */
    static final String INTERNAL_TOKEN_HEADER = "X-Accentury-Internal-Token";

    private final RestClient restClient;

    /**
     * health 프로브 전용 클라이언트 - 분석보다 짧은 타임아웃({@code ai-health-timeout})으로
     * 조립된다 (KAN-28). 추론 없이 즉답하는 엔드포인트인데 분석과 같은 10초를 기다리면,
     * 프로브를 도는 스케줄러 스레드가 그만큼 묶여 같은 풀의 다른 잡이 밀린다.
     */
    private final RestClient healthRestClient;

    private final ObjectMapper objectMapper;

    /** 내부 호출 시크릿 - 미설정(null)이면 헤더를 붙이지 않는다 (로컬 개발, {@code accentury.analysis.ai-token}). */
    private final @Nullable String internalToken;

    RestAiAnalysisClient(RestClient restClient, RestClient healthRestClient, ObjectMapper objectMapper,
                         @Nullable String internalToken) {
        this.restClient = restClient;
        this.healthRestClient = healthRestClient;
        this.objectMapper = objectMapper;
        this.internalToken = internalToken != null && !internalToken.isBlank() ? internalToken : null;
    }

    @Override
    public Outcome analyze(AnalysisDispatcher.AnalysisRequest request, String correlationId) {
        // MultipartBodyBuilder는 리액티브 스트림 클래스를 끌어와(클래스패스에 없음) 쓰지 않는다 -
        // FormHttpMessageConverter가 처리하는 HttpEntity 파트 맵으로 충분하다.
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("audio", part(new NamedBytes(request.audio()), MediaType.parseMediaType("audio/wav")));
        body.add("meta", part(objectMapper.writeValueAsString(new AnalyzeMeta(
                        correlationId, request.itemId(), request.testVersion(),
                        request.scoreVersion(), request.durationMs())),
                MediaType.APPLICATION_JSON));

        try {
            return restClient.post()
                    .uri(ANALYZE_PATH)
                    .header(CorrelationIdFilter.HEADER, correlationId)
                    .headers(this::attachInternalToken)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .exchange((httpRequest, httpResponse) ->
                            map(request, httpResponse.getStatusCode().value(), httpResponse.getBody()));
        } catch (ResourceAccessException e) {
            throw new AiUnavailableException("AI 호출 실패: " + e.getMessage(),
                    isTimeout(e) ? AiUnavailableException.Kind.TIMED_OUT
                            : AiUnavailableException.Kind.UNREACHED, e);
        }
    }

    /**
     * 워밍업 상태 조회 (§4.2) - 회로 복구 판정에만 쓴다 (KAN-28).
     * <p>
     * 200 + {@code status: "UP"}만 살아 있는 것으로 본다. 프로세스는 떠 있지만 모델이
     * 아직 안 올라온 상태(KAN-22가 채울 워밍업)를 UP으로 읽으면, 회로를 닫자마자
     * 사용자 요청이 다시 실패한다.
     */
    @Override
    public boolean healthy() {
        try {
            return Boolean.TRUE.equals(healthRestClient.get()
                    .uri(HEALTH_PATH)
                    .headers(this::attachInternalToken)
                    .exchange((httpRequest, httpResponse) -> {
                        if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                            return false;
                        }
                        HealthResponse parsed =
                                objectMapper.readValue(httpResponse.getBody(), HealthResponse.class);
                        return parsed != null && "UP".equals(parsed.status());
                    }));
        } catch (RuntimeException e) {
            // 연결 실패, 타임아웃, 계약과 다른 본문 - 전부 "아직 아니다"로 접는다.
            // 프로브는 실패해도 회로를 열린 채로 두는 것이 전부라 던질 이유가 없다.
            log.debug("AI health 프로브 실패: {}", e.toString());
            return false;
        }
    }

    private void attachInternalToken(HttpHeaders headers) {
        if (internalToken != null) {
            headers.set(INTERNAL_TOKEN_HEADER, internalToken);
        }
    }

    private static HttpEntity<Object> part(Object content, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        return new HttpEntity<>(content, headers);
    }

    private Outcome map(AnalysisDispatcher.AnalysisRequest request, int statusCode,
                        InputStream responseBody) {
        if (statusCode >= 500) {
            // 요청이 AI까지 갔다 - 미도달(UNREACHED)과 구분해야 시도 예산이 정확하다.
            throw new AiUnavailableException("AI 5xx 응답: " + statusCode,
                    AiUnavailableException.Kind.SERVER_ERROR, null);
        }
        if (statusCode == HttpStatus.TOO_MANY_REQUESTS.value()) {
            // 과부하 셰딩 - 도달은 했지만 추론 전에 거절됐다. GPU 미소모이므로 미도달과 같은
            // 예산 취급이어야 서버 사정만으로 시도 상한(§2.5)이 깎이지 않는다 (Codex 리뷰).
            throw new AiUnavailableException("AI 과부하 응답: 429",
                    AiUnavailableException.Kind.UNREACHED, null);
        }
        if (statusCode == HttpStatus.UNAUTHORIZED.value() || statusCode == HttpStatus.FORBIDDEN.value()) {
            // 내부 호출 토큰 거절 (KAN-36). 추론 전에 거절된 배포 설정 문제라 429와 같은 예산 취급이다 -
            // 사용자 시도 상한(§2.5)을 깎지 않고, 재전송 예산이 다하면 ANALYSIS_UNAVAILABLE로 종결해 재업로드를
            // 열어 둔다. 계약 위반으로 접으면 비재시도 FAILED가 되어 토큰 회전 중의 어긋남 몇 초가 사용자
            // 문항을 태우고, health는 인증 예외라 반열림 시험마다 사용자 요청을 하나씩 더 태운다 (리뷰 P1).
            // 회로에는 실패로 세이므로 연속되면 열리고 ai-circuit-open 경보로 드러난다.
            throw new AiUnavailableException("AI 인증 거절: " + statusCode,
                    AiUnavailableException.Kind.UNREACHED, null);
        }

        AnalyzeResponse parsed;
        try {
            parsed = objectMapper.readValue(responseBody, AnalyzeResponse.class);
        } catch (JacksonException e) {
            log.error("AI 응답 본문을 해석할 수 없다 status={}", statusCode, e);
            return Rejected.contractViolation();
        }

        if (statusCode == HttpStatus.OK.value() && parsed != null && "OK".equals(parsed.status())) {
            return completed(request, parsed);
        }
        if (statusCode == HttpStatus.UNPROCESSABLE_CONTENT.value()
                && parsed != null && "FAILED".equals(parsed.status())) {
            String rawCode = parsed.quality() != null ? parsed.quality().code() : null;
            ErrorCode code = knownErrorCode(rawCode);
            if (code == null) {
                // 판정 코드가 없거나 계약(§2.4)에 없는 코드다 - 재녹음으로 해결된다는 근거가
                // 없어 비재시도이고, 미검증 문자열을 DB 컬럼(40자)과 클라이언트로 흘리지
                // 않는다 (Codex 리뷰 - "OK"나 모르는 코드가 error.code로 나가면 안 된다).
                log.error("AI 판정 코드가 계약에 없다 code={}", rawCode);
                return Rejected.contractViolation();
            }
            // AI가 retryable을 생략하면 코드 정의(§2.4)의 기본값을 따른다 - 생략을 false로
            // 읽으면 AUDIO_TOO_QUIET가 재녹음 불가로 굳어 문항이 막힌다 (Codex 리뷰).
            boolean retryable = parsed.retryable() != null ? parsed.retryable()
                    : code.retryable();
            return Rejected.judged(code.name(), retryable && code != ErrorCode.INTERNAL_ERROR);
        }

        log.error("AI 응답이 계약(§4.1)과 다르다 status={} body.status={}",
                statusCode, parsed != null ? parsed.status() : null);
        return Rejected.contractViolation();
    }

    private Outcome completed(AnalysisDispatcher.AnalysisRequest request, AnalyzeResponse parsed) {
        // 점수는 채점 입력이다 - 범위 밖 값을 저장하면 집계(KAN-21)가 오염되므로 여기서 끊는다.
        if (parsed.intonationScore() == null
                || parsed.intonationScore() < 0 || parsed.intonationScore() > 100
                || parsed.modelVersion() == null || parsed.scoreVersion() == null) {
            // 점수 원값은 로그에도 남기지 않는다 - 점수 공개는 /result 한 곳이다 (Codex sol 리뷰 P2).
            log.error("AI 성공 응답에 필수 필드가 없거나 점수가 0~100 밖이다 scoreMissing={} modelVersion={}",
                    parsed.intonationScore() == null, parsed.modelVersion());
            return Rejected.contractViolation();
        }
        // 세션은 생성 시점 점수 버전에 고정된다 (§5.4) - 구버전 AI가 다른 버전으로 채점한
        // 점수를 받으면 한 세션에 두 채점 버전이 섞인다. 계약 위반으로 끊는다 (Codex sol 리뷰 P1).
        if (!request.scoreVersion().equals(parsed.scoreVersion())) {
            log.error("AI가 다른 점수 버전으로 응답했다 expected={} actual={}",
                    request.scoreVersion(), parsed.scoreVersion());
            return Rejected.contractViolation();
        }
        String qualityCode = parsed.quality() != null && parsed.quality().code() != null
                ? parsed.quality().code() : "OK";
        return new Completed(parsed.intonationScore(), qualityCode,
                parsed.modelVersion(), parsed.scoreVersion());
    }

    /** §2.4에 정의된 코드만 판정으로 인정한다 - 모르는 코드는 null */
    private static @Nullable ErrorCode knownErrorCode(@Nullable String code) {
        if (code == null) {
            return null;
        }
        try {
            return ErrorCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isTimeout(ResourceAccessException e) {
        // JDK HttpClient는 HttpTimeoutException, 고전 커넥터는 SocketTimeoutException을 던진다.
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** §4.1 요청 meta 파트 - 순서와 이름은 명세 예시 그대로다. */
    record AnalyzeMeta(String correlationId, String itemId, String testVersion,
                       String scoreVersion, long durationMs) {
    }

    /** §4.1 응답 - 필요한 필드만 읽는다. segments, confidence, processingMs는 BE가 쓰지 않는다. */
    record AnalyzeResponse(@Nullable String status, @Nullable Integer intonationScore,
                           @Nullable Quality quality, @Nullable Boolean retryable,
                           @Nullable String modelVersion, @Nullable String scoreVersion) {

        record Quality(@Nullable String code) {
        }
    }

    /** §4.2 health 응답 - 회로 복구 판정에 쓰는 필드만 읽는다 (모델 버전은 KAN-22). */
    record HealthResponse(@Nullable String status) {
    }

    /** multipart 파트에 파일명을 주기 위한 래퍼 - 없으면 일부 서버 프레임워크가 파일 파트로 안 받는다. */
    private static final class NamedBytes extends ByteArrayResource {

        private NamedBytes(byte[] bytes) {
            super(bytes);
        }

        @Override
        public String getFilename() {
            return "audio.wav";
        }
    }
}
