package app.accentury.backend.upload;

import app.accentury.backend.common.ErrorResponse;
import app.accentury.backend.common.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "3. 음성 업로드", description = "음성 문항 녹음 업로드와 분석 요청 (KAN-23)")
public class VoiceUploadController {

    private final VoiceUploadService service;

    public VoiceUploadController(VoiceUploadService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SecurityRequirement(name = OpenApiConfig.SESSION_TOKEN)
    @Operation(
            summary = "음성 문항 녹음 업로드",
            description = """
                    음성 문항 하나의 녹음을 올린다. 앱에서 '다음' 버튼을 누른 시점에 호출된다.

                    분석은 **비동기**다. 서버는 AI 분석을 걸어두고 즉시 202를 돌려주므로, 사용자는 기다리지 않고
                    다음 문항으로 넘어간다. 응답에 점수나 품질 판정은 없다.

                    ### 앱의 재녹음과 서버의 시도는 다르다

                    앱에서는 무제한으로 다시 녹음할 수 있지만 그건 전부 로컬이라 서버가 모른다.
                    '다음'을 눌러 실제로 올라온 것만 시도 1회로 센다. **문항당 시도는 5회까지**이고,
                    6회차는 429 `RATE_RETAKE_EXCEEDED`다. 이건 시간이 지나도 안 풀리므로 `retryable`이 `false`다.

                    ### Idempotency-Key

                    필수다. 같은 키로 다시 보내면 새 분석을 만들지 않고 처음 만든 작업을 그대로 돌려준다.
                    시도 상한 검사보다 멱등 판별이 먼저라, 같은 키의 재전송은 상한과 무관하게 통과한다.
                    네트워크가 끊겨 재전송할 때는 **같은 키**를, 사용자가 다시 녹음해 올릴 때는 **새 키**를 쓴다.

                    ### 오디오 규격

                    WAV 16kHz mono 16-bit, 최대 1MB, 10초 이내다. 길이 상한은 전 음성 문항 공통이며
                    정의 응답의 `maxDurationMs`와 같은 값이다.
                    길이의 정본은 `meta.durationMs`가 아니라 서버가 WAV 헤더에서 직접 계산한 값이다.

                    분석이 끝나면 오디오는 서버에 남지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "분석 접수됨. `pollAfterMs` 뒤에 상태를 조회한다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = VoiceUploadResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "`Idempotency-Key` 누락, `meta` 누락이나 형식 오류, `audio` 파트 누락",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "VALIDATION_FAILED", value = """
                                    {
                                      "code": "VALIDATION_FAILED",
                                      "message": "meta.clientQuality는 rms, peak, silenceRatio, clipped 4개 필드가 모두 필요합니다.",
                                      "retryable": false,
                                      "retryAfterMs": null,
                                      "correlationId": "c_8f2a1b3c-4d5e-6f70-8a9b-0c1d2e3f4a5b"
                                    }"""))),
            @ApiResponse(responseCode = "401", description = "토큰 누락이나 만료 (`SESSION_EXPIRED`)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "다른 세션의 토큰 (`SESSION_FORBIDDEN`)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "음성 문항이 아님. 어휘 문항은 업로드 대상이 아니다 (`ITEM_WRONG_TYPE`)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "413", description = "1MB 초과 (`AUDIO_TOO_LARGE`)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "415", description = "WAV 16kHz mono 16-bit가 아님 (`AUDIO_FORMAT_UNSUPPORTED`)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422",
                    description = "이 버전에 없는 문항 (`ITEM_NOT_IN_VERSION`) 또는 문항 상한을 넘는 길이 (`AUDIO_TOO_LONG`)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429",
                    description = "IP 분당 요청 제한 (`RATE_LIMITED`, 재시도 가능) 또는 문항당 시도 5회 초과 (`RATE_RETAKE_EXCEEDED`, 재시도 불가)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "RATE_RETAKE_EXCEEDED", value = """
                                    {
                                      "code": "RATE_RETAKE_EXCEEDED",
                                      "message": "이 문항의 업로드 횟수 상한을 넘었습니다. (최대 5회)",
                                      "retryable": false,
                                      "retryAfterMs": null,
                                      "correlationId": "c_8f2a1b3c-4d5e-6f70-8a9b-0c1d2e3f4a5b"
                                    }"""))),
            @ApiResponse(responseCode = "503", description = "분석 서버 전달 실패 (`ANALYSIS_UNAVAILABLE`). 새 키로 다시 올린다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    VoiceUploadResponse upload(@PathVariable
                               @Parameter(description = "세션 생성 응답의 `sessionId`", example = "s_3f9a2c1e-8b7d-4a60-9e21-5c4b7a8d0f13")
                               String sessionId,

                               @PathVariable
                               @Parameter(description = "테스트 정의의 음성 문항 `itemId`", example = "v1")
                               String itemId,

                               // Swagger UI는 Authorize 버튼으로 이 헤더를 채운다 - 파라미터로도 노출하면 입력란이 둘이 된다
                               @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                               @Parameter(hidden = true)
                               @Nullable String authorization,

                               // 계약상 필수다. required = false인 것은 누락을 Spring이 아니라
                               // 서비스가 잡아 공통 오류 봉투로 응답하기 위한 것이다
                               @RequestHeader(value = "Idempotency-Key", required = false)
                               @Parameter(required = true, description = """
                                       같은 녹음의 재전송을 구분하는 키. 재전송이면 같은 값, 새 녹음이면 새 값을 쓴다.""",
                                       example = "idem-v1-attempt-1")
                               @Nullable String idempotencyKey,

                               @RequestPart(value = "audio", required = false)
                               @Parameter(description = "녹음 파일. WAV 16kHz mono 16-bit, 최대 1MB.")
                               @Nullable MultipartFile audio,

                               @RequestPart(value = "meta", required = false)
                               @Parameter(description = """
                                       녹음 메타데이터 JSON. `durationMs`와 `clientQuality`의 4개 필드가 모두 필수다.""",
                                       schema = @Schema(type = "string", example = """
                                               {"durationMs":3420,"clientQuality":{"rms":0.12,"peak":0.81,\
                                               "silenceRatio":0.08,"clipped":false}}"""))
                               @Nullable String metaJson) {
        return service.upload(sessionId, itemId, authorization, idempotencyKey, audio, metaJson);
    }
}
