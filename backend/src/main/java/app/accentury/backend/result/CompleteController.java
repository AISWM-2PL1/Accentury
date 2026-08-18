package app.accentury.backend.result;

import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v0/sessions/{sessionId}/complete} (KAN-16, API 명세서 §3.6).
 * <p>
 * 인증 필요 엔드포인트다 (§2.1). 헤더 존재 검증은 서비스가 맡아 누락 시에도
 * 공통 오류 봉투로 응답한다 - 그래서 전부 {@code required = false}다.
 */
@RestController
@RequestMapping("/v0/sessions/{sessionId}/complete")
public class CompleteController {

    private final CompletionService service;

    public CompleteController(CompletionService service) {
        this.service = service;
    }

    /**
     * 완주를 검증하고 결과를 확정한다. 마지막 문항 제출 후 대기 화면에서 호출된다 (§5.7).
     * <p>
     * 필수 문항은 음성 5 + 어휘 5 전부다 - 건너뛰기가 없다 (§5.6). 음성 문항은 최신 성공
     * 시도 1건이 있으면 완료로 간주한다 (§5.1). 모든 문항이 갖춰진 최초 호출 1회만 집계
     * (KAN-21)와 결과 저장이 일어나고, 이후 재시도는 READY만 다시 받는다 - 점수와 등급은
     * {@code /result}(KAN-25)가 공개하는 유일한 곳이라 이 응답에는 없다.
     * <p>
     * 상태 응답이므로 캐시 금지다 (§3.4와 같은 이유 - 캐시가 폴링 응답을 재사용하면
     * pollAfterMs를 지켜도 완료가 가려진다).
     *
     * <h4>Idempotency-Key</h4>
     * 필수다 (§2.2 - 비용 발생 POST: 업로드/답안/완료). 완료는 자연 멱등이라 어떤 키로
     * 재시도해도 결과가 중복 생성되지 않는다.
     *
     * <h4>응답</h4>
     * 200 READY(결과 확정) 또는 PROCESSING(분석 대기 - {@code pendingItems}, {@code pollAfterMs}) /
     * 400 {@code Idempotency-Key} 누락({@code VALIDATION_FAILED}) /
     * 401 토큰 누락이나 만료({@code SESSION_EXPIRED}) /
     * 403 다른 세션의 토큰({@code SESSION_FORBIDDEN}) /
     * 409 전부 실패한 문항 존재 - 재녹음 필요({@code RESULT_RETAKE_REQUIRED} + {@code retakeItems}) /
     * 422 미제출 문항 존재({@code RESULT_INCOMPLETE} + {@code missingItems}) /
     * 429 세션당 요청 한도 초과({@code RATE_LIMITED} + {@code Retry-After}).
     *
     * @param sessionId      세션 생성 응답의 {@code sessionId}
     * @param authorization  {@code Bearer } + 세션 토큰
     * @param idempotencyKey 같은 완료 요청의 재전송을 구분하는 키
     */
    @PostMapping
    ResponseEntity<CompleteResponse> complete(
            @PathVariable String sessionId,

            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            @Nullable String authorization,

            // 계약상 필수다. required = false인 것은 누락을 Spring이 아니라
            // 서비스가 잡아 공통 오류 봉투로 응답하기 위한 것이다.
            @RequestHeader(value = "Idempotency-Key", required = false)
            @Nullable String idempotencyKey) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.complete(sessionId, authorization, idempotencyKey));
    }
}
