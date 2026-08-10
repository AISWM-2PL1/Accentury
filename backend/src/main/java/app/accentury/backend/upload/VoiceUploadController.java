package app.accentury.backend.upload;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code POST /v0/sessions/{sessionId}/voice-items/{itemId}/recording} (KAN-23, API 명세서 §3.3).
 * <p>
 * 인증 필요 엔드포인트다 (§2.1). 헤더와 파트의 존재 검증은 서비스가 맡아
 * 누락 시에도 공통 오류 봉투로 응답한다 - 그래서 전부 {@code required = false}다.
 * IP 요청 제한은 multipart 해석 전에 {@link UploadRateLimitFilter}가 먼저 집행한다.
 */
@RestController
@RequestMapping("/v0/sessions/{sessionId}/voice-items/{itemId}/recording")
public class VoiceUploadController {

    private final VoiceUploadService service;

    public VoiceUploadController(VoiceUploadService service) {
        this.service = service;
    }

    /**
     * 음성 문항 하나의 녹음을 올린다. 앱에서 '다음' 버튼을 누른 시점에 호출된다.
     * <p>
     * 분석은 <b>비동기</b>다. 서버는 AI 분석을 걸어두고 즉시 202를 돌려주므로, 사용자는 기다리지 않고
     * 다음 문항으로 넘어간다. 응답에 점수나 품질 판정은 없다.
     *
     * <h4>앱의 재녹음과 서버의 시도는 다르다</h4>
     * 앱에서는 무제한으로 다시 녹음할 수 있지만 그건 전부 로컬이라 서버가 모른다.
     * '다음'을 눌러 실제로 올라온 것만 시도 1회로 센다. <b>문항당 시도는 5회까지</b>이고,
     * 6회차는 429 {@code RATE_RETAKE_EXCEEDED}다. 이건 시간이 지나도 안 풀리므로
     * {@code retryable}이 {@code false}다.
     *
     * <h4>Idempotency-Key</h4>
     * 필수다. 같은 키로 다시 보내면 새 분석을 만들지 않고 처음 만든 작업을 그대로 돌려준다.
     * 시도 상한 검사보다 멱등 판별이 먼저라, 같은 키의 재전송은 상한과 무관하게 통과한다.
     * 네트워크가 끊겨 재전송할 때는 <b>같은 키</b>를, 사용자가 다시 녹음해 올릴 때는 <b>새 키</b>를 쓴다.
     *
     * <h4>오디오 규격</h4>
     * WAV 16kHz mono 16-bit, 최대 1MB, 10초 이내다. 길이 상한은 전 음성 문항 공통이며
     * 정의 응답의 {@code maxDurationMs}와 같은 값이다.
     * 길이의 정본은 {@code meta.durationMs}가 아니라 서버가 WAV 헤더에서 직접 계산한 값이다.
     * 분석이 끝나면 오디오는 서버에 남지 않는다.
     *
     * <h4>응답</h4>
     * 202 분석 접수됨({@code pollAfterMs} 뒤에 상태를 조회한다) /
     * 400 {@code Idempotency-Key} 누락, {@code meta} 누락이나 형식 오류, {@code audio} 파트 누락
     * ({@code VALIDATION_FAILED}) /
     * 401 토큰 누락이나 만료({@code SESSION_EXPIRED}) /
     * 403 다른 세션의 토큰({@code SESSION_FORBIDDEN}) /
     * 409 음성 문항이 아님, 어휘 문항은 업로드 대상이 아니다({@code ITEM_WRONG_TYPE}) /
     * 413 1MB 초과({@code AUDIO_TOO_LARGE}) /
     * 415 WAV 16kHz mono 16-bit가 아님({@code AUDIO_FORMAT_UNSUPPORTED}) /
     * 422 이 버전에 없는 문항({@code ITEM_NOT_IN_VERSION}) 또는 문항 상한을 넘는 길이({@code AUDIO_TOO_LONG}) /
     * 429 IP 분당 요청 제한({@code RATE_LIMITED}, 재시도 가능) 또는 문항당 시도 5회 초과
     * ({@code RATE_RETAKE_EXCEEDED}, 재시도 불가) /
     * 503 분석 서버 전달 실패({@code ANALYSIS_UNAVAILABLE}, 새 키로 다시 올린다).
     *
     * @param sessionId      세션 생성 응답의 {@code sessionId}
     * @param itemId         테스트 정의의 음성 문항 {@code itemId} (예: {@code v1})
     * @param authorization  {@code Bearer } + 세션 토큰
     * @param idempotencyKey 같은 녹음의 재전송을 구분하는 키. 재전송이면 같은 값, 새 녹음이면 새 값을 쓴다
     * @param audio          녹음 파일. WAV 16kHz mono 16-bit, 최대 1MB
     * @param metaJson       녹음 메타데이터 JSON - {@code durationMs}와 {@code clientQuality}의 4개 필드가 모두 필수다.
     *                       예: {@code {"durationMs":3420,"clientQuality":{"rms":0.12,"peak":0.81,"silenceRatio":0.08,"clipped":false}}}
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    VoiceUploadResponse upload(@PathVariable String sessionId,

                               @PathVariable String itemId,

                               @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                               @Nullable String authorization,

                               // 계약상 필수다. required = false인 것은 누락을 Spring이 아니라
                               // 서비스가 잡아 공통 오류 봉투로 응답하기 위한 것이다
                               @RequestHeader(value = "Idempotency-Key", required = false)
                               @Nullable String idempotencyKey,

                               @RequestPart(value = "audio", required = false)
                               @Nullable MultipartFile audio,

                               @RequestPart(value = "meta", required = false)
                               @Nullable String metaJson) {
        return service.upload(sessionId, itemId, authorization, idempotencyKey, audio, metaJson);
    }
}
