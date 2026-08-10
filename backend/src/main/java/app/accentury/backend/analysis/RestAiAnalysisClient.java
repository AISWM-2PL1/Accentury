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
 * (형식이 다른 본문, 범위 밖 점수)은 장애가 아니라 비재시도 {@link AiAnalysisClient.Rejected}로
 * 종결한다 - 같은 요청을 다시 보내도 같은 응답이 올 것이기 때문이다.
 */
class RestAiAnalysisClient implements AiAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(RestAiAnalysisClient.class);

    static final String ANALYZE_PATH = "/internal/v0/analyze";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    RestAiAnalysisClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Outcome analyze(AnalysisDispatcher.AnalysisRequest request, String correlationId) {
        // MultipartBodyBuilder는 리액티브 스트림 클래스를 끌어와(클래스패스에 없음) 쓰지 않는다 -
        // FormHttpMessageConverter가 처리하는 HttpEntity 파트 맵으로 충분하다
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

    private static HttpEntity<Object> part(Object content, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        return new HttpEntity<>(content, headers);
    }

    private Outcome map(AnalysisDispatcher.AnalysisRequest request, int statusCode,
                        InputStream responseBody) {
        if (statusCode >= 500) {
            // 요청이 AI까지 갔다 - 미도달(UNREACHED)과 구분해야 시도 예산이 정확하다
            throw new AiUnavailableException("AI 5xx 응답: " + statusCode,
                    AiUnavailableException.Kind.SERVER_ERROR, null);
        }

        AnalyzeResponse parsed;
        try {
            parsed = objectMapper.readValue(responseBody, AnalyzeResponse.class);
        } catch (JacksonException e) {
            log.error("AI 응답 본문을 해석할 수 없다 status={}", statusCode, e);
            return new Rejected(ErrorCode.INTERNAL_ERROR.name(), false);
        }

        if (statusCode == HttpStatus.OK.value() && parsed != null && "OK".equals(parsed.status())) {
            return completed(request, parsed);
        }
        if (statusCode == HttpStatus.UNPROCESSABLE_CONTENT.value()
                && parsed != null && "FAILED".equals(parsed.status())) {
            // 판정 코드가 없으면 원인 불명 - 재녹음으로 해결된다는 근거가 없으므로 비재시도다
            String code = parsed.quality() != null && parsed.quality().code() != null
                    ? parsed.quality().code() : ErrorCode.INTERNAL_ERROR.name();
            boolean retryable = Boolean.TRUE.equals(parsed.retryable())
                    && !ErrorCode.INTERNAL_ERROR.name().equals(code);
            return new Rejected(code, retryable);
        }

        log.error("AI 응답이 계약(§4.1)과 다르다 status={} body.status={}",
                statusCode, parsed != null ? parsed.status() : null);
        return new Rejected(ErrorCode.INTERNAL_ERROR.name(), false);
    }

    private Outcome completed(AnalysisDispatcher.AnalysisRequest request, AnalyzeResponse parsed) {
        // 점수는 채점 입력이다 - 범위 밖 값을 저장하면 집계(KAN-21)가 오염되므로 여기서 끊는다
        if (parsed.intonationScore() == null
                || parsed.intonationScore() < 0 || parsed.intonationScore() > 100
                || parsed.modelVersion() == null || parsed.scoreVersion() == null) {
            // 점수 원값은 로그에도 남기지 않는다 - 점수 공개는 /result 한 곳이다 (Codex sol 리뷰 P2)
            log.error("AI 성공 응답에 필수 필드가 없거나 점수가 0~100 밖이다 scoreMissing={} modelVersion={}",
                    parsed.intonationScore() == null, parsed.modelVersion());
            return new Rejected(ErrorCode.INTERNAL_ERROR.name(), false);
        }
        // 세션은 생성 시점 점수 버전에 고정된다 (§5.4) - 구버전 AI가 다른 버전으로 채점한
        // 점수를 받으면 한 세션에 두 채점 버전이 섞인다. 계약 위반으로 끊는다 (Codex sol 리뷰 P1)
        if (!request.scoreVersion().equals(parsed.scoreVersion())) {
            log.error("AI가 다른 점수 버전으로 응답했다 expected={} actual={}",
                    request.scoreVersion(), parsed.scoreVersion());
            return new Rejected(ErrorCode.INTERNAL_ERROR.name(), false);
        }
        String qualityCode = parsed.quality() != null && parsed.quality().code() != null
                ? parsed.quality().code() : "OK";
        return new Completed(parsed.intonationScore(), qualityCode,
                parsed.modelVersion(), parsed.scoreVersion());
    }

    private static boolean isTimeout(ResourceAccessException e) {
        // JDK HttpClient는 HttpTimeoutException, 고전 커넥터는 SocketTimeoutException을 던진다
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** §4.1 요청 meta 파트 - 순서와 이름은 명세 예시 그대로다 */
    record AnalyzeMeta(String correlationId, String itemId, String testVersion,
                       String scoreVersion, long durationMs) {
    }

    /** §4.1 응답 - 필요한 필드만 읽는다. segments, confidence, processingMs는 BE가 쓰지 않는다 */
    record AnalyzeResponse(@Nullable String status, @Nullable Integer intonationScore,
                           @Nullable Quality quality, @Nullable Boolean retryable,
                           @Nullable String modelVersion, @Nullable String scoreVersion) {

        record Quality(@Nullable String code) {
        }
    }

    /** multipart 파트에 파일명을 주기 위한 래퍼 - 없으면 일부 서버 프레임워크가 파일 파트로 안 받는다 */
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
