package app.accentury.backend.session;

import app.accentury.backend.common.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v0/sessions} - 익명 테스트 세션 생성 (KAN-9, API 명세서 §3.1).
 * <p>
 * 인증 불필요 엔드포인트다 (§2.1). 재응시도 같은 호출이다.
 */
@RestController
@RequestMapping("/v0/sessions")
@Tag(name = "1. 세션", description = "익명 테스트 세션 (KAN-9)")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 바디 전체가 선택이다 - 빈 요청으로도 세션이 생긴다 (§3.1 - 모든 입력 필드 optional) */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "익명 세션 생성",
            description = """
                    테스트를 시작할 때 가장 먼저 호출한다. 인증이 필요 없고, 결과 화면의 '재응시하기'도 같은 호출이다.

                    바디는 통째로 생략해도 된다. 개인 식별 정보를 받지 않으며 권역 파라미터도 없다(경남 고정).

                    응답의 `testVersion`과 `scoreVersion`은 이 세션에 고정된다.
                    테스트 도중 서버에 새 버전이 올라가도 이 세션은 시작할 때의 문항과 점수 기준을 그대로 쓴다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "세션 생성됨",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SessionResponse.class))),
            @ApiResponse(responseCode = "400", description = "`campaignToken` 형식 위반 등 입력 검증 실패",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "VALIDATION_FAILED", value = """
                                    {
                                      "code": "VALIDATION_FAILED",
                                      "message": "영숫자와 ._- 조합 최대 64자만 허용됩니다",
                                      "retryable": false,
                                      "retryAfterMs": null,
                                      "correlationId": "c_8f2a1b3c-4d5e-6f70-8a9b-0c1d2e3f4a5b"
                                    }""")))
    })
    public SessionResponse create(@Valid @RequestBody(required = false) @Nullable CreateSessionRequest request) {
        return sessionService.create(request);
    }
}
