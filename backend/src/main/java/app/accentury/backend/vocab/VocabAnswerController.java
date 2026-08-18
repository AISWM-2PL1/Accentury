package app.accentury.backend.vocab;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v0/sessions/{sessionId}/vocab-items/{itemId}/answer} (KAN-15, API 명세서 §3.5).
 * <p>
 * 인증 필요 엔드포인트다 (§2.1). 헤더와 본문의 존재 검증은 서비스가 맡아
 * 누락 시에도 공통 오류 봉투로 응답한다 - 그래서 전부 {@code required = false}다.
 */
@RestController
@RequestMapping("/v0/sessions/{sessionId}/vocab-items/{itemId}/answer")
public class VocabAnswerController {

    private final VocabAnswerService service;

    public VocabAnswerController(VocabAnswerService service) {
        this.service = service;
    }

    /**
     * 어휘 문항 하나의 답안을 저장한다. 선택지를 고르고 '다음'을 누른 시점에 호출된다 (§5.7).
     * <p>
     * <b>정오 여부는 응답에 없다</b> - AI를 거치지 않고 서버가 정답표와 대조해 저장만 하고,
     * 점수는 {@code /result}(KAN-25)에서 한 번에 공개된다. 응답의 진행도는 전체 10문항
     * 기준이다 - 어휘는 답안이 저장된 문항, 음성은 업로드가 1건이라도 있었던 문항을 센다.
     *
     * <h4>Idempotency-Key</h4>
     * 필수다. 같은 키로 같은 답을 다시 보내면(네트워크 재전송) 중복 저장 없이 같은 결과를
     * 돌려준다. 문항당 답안은 하나다 - 새 키로 다시 제출하면 409로 거절한다.
     * 음성 업로드(§3.3)와 달리 재제출 경로 자체가 없다.
     *
     * <h4>응답</h4>
     * 200 저장됨 또는 같은 키의 재전송 /
     * 400 {@code Idempotency-Key} 누락, {@code choiceId} 누락, 같은 키로 다른 답
     * ({@code VALIDATION_FAILED}) /
     * 401 토큰 누락이나 만료({@code SESSION_EXPIRED}) /
     * 403 다른 세션의 토큰({@code SESSION_FORBIDDEN}) /
     * 409 완료된 세션({@code SESSION_COMPLETED}), 어휘 문항이 아님({@code ITEM_WRONG_TYPE}),
     * 새 키의 재제출({@code ITEM_ALREADY_ANSWERED}) /
     * 422 이 버전에 없는 문항 또는 이 문항의 선택지가 아닌 답({@code ITEM_NOT_IN_VERSION}).
     *
     * @param sessionId      세션 생성 응답의 {@code sessionId}
     * @param itemId         테스트 정의의 어휘 문항 {@code itemId} (예: {@code w1})
     * @param authorization  {@code Bearer } + 세션 토큰
     * @param idempotencyKey 같은 답안의 재전송을 구분하는 키
     * @param request        {@code {"choiceId": "w1a"}}
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    VocabAnswerResponse submit(@PathVariable String sessionId,

                               @PathVariable String itemId,

                               @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                               @Nullable String authorization,

                               // 계약상 필수다. required = false인 것은 누락을 Spring이 아니라
                               // 서비스가 잡아 공통 오류 봉투로 응답하기 위한 것이다.
                               @RequestHeader(value = "Idempotency-Key", required = false)
                               @Nullable String idempotencyKey,

                               @RequestBody(required = false)
                               @Nullable VocabAnswerRequest request) {
        return service.submit(sessionId, itemId, authorization, idempotencyKey, request);
    }
}
