package app.accentury.backend.feedback;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v0/sessions/{sessionId}/feedback} (KAN-211).
 * <p>
 * 인증 필요 엔드포인트다 (§2.1). 헤더와 본문의 존재 검증은 서비스가 맡아 누락 시에도 공통
 * 오류 봉투로 응답한다 - 그래서 전부 {@code required = false}다 ({@code VocabAnswerController}와 같다).
 */
@RestController
@RequestMapping("/v0/sessions/{sessionId}/feedback")
public class FeedbackController {

    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    /**
     * 결과 화면에서 보낸 이용 후기를 저장한다 (2026-09-15 결정).
     * <p>
     * 별점과 회신 이메일은 선택이고 본문만 필수다. 저장이 커밋되면 슬랙 채널로 알림이 나가는데
     * (KAN-211 2단계) 그것은 비동기이고, 응답은 알림을 기다리지 않는다 - 슬랙이 죽어도 201이다.
     *
     * <h4>Idempotency-Key</h4>
     * 필수다. 같은 키로 다시 보내면(네트워크 재전송) 중복 저장 없이 200이다 - 이때 본문을
     * 대조하지 않는다 (어휘 답안과 달리 "같은 키로 다른 답"이라는 오용이 없다, {@link FeedbackService}).
     * 세션당 후기는 하나다 - 새 키로 다시 제출하면 409로 거절한다.
     *
     * <h4>응답</h4>
     * 201 저장됨 / 200 같은 키의 재전송 /
     * 400 {@code Idempotency-Key} 누락, 본문 누락이나 500자 초과, 별점 범위 밖,
     * 이메일 형식 불량({@code VALIDATION_FAILED}) /
     * 401 토큰 누락이나 만료({@code SESSION_EXPIRED}) /
     * 403 다른 세션의 토큰({@code SESSION_FORBIDDEN}) /
     * 409 완료되지 않은 세션({@code RESULT_NOT_READY}), 새 키의 재제출
     * ({@code FEEDBACK_ALREADY_SUBMITTED}) /
     * 429 세션당 한도 초과({@code RATE_LIMITED}).
     *
     * @param sessionId      세션 생성 응답의 {@code sessionId}
     * @param authorization  {@code Bearer } + 세션 토큰
     * @param idempotencyKey 같은 후기의 재전송을 구분하는 키
     * @param request        {@code {"rating": 5, "body": "...", "contactEmail": "..."}}
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<FeedbackResponse> submit(@PathVariable String sessionId,

                                            @RequestHeader(value = HttpHeaders.AUTHORIZATION,
                                                    required = false)
                                            @Nullable String authorization,

                                            // 계약상 필수다. required = false인 것은 누락을 Spring이
                                            // 아니라 서비스가 잡아 공통 오류 봉투로 응답하기 위한 것이다.
                                            @RequestHeader(value = "Idempotency-Key", required = false)
                                            @Nullable String idempotencyKey,

                                            @RequestBody(required = false)
                                            @Nullable FeedbackRequest request) {
        FeedbackService.SubmitOutcome outcome =
                service.submit(sessionId, authorization, idempotencyKey, request);
        // 저장은 201, 같은 키의 재전송은 200이다 - 본문은 같다. 상태로만 가르는 것은
        // 재전송이 "아무 일도 일어나지 않았다"는 사실을 응답 본문에 새 필드로 싣지 않기 위해서다.
        return ResponseEntity.status(outcome.savedNew() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new FeedbackResponse(true));
    }
}
