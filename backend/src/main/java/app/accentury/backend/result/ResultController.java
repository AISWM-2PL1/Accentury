package app.accentury.backend.result;

import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v0/sessions/{sessionId}/result} (KAN-25, API 명세서 §3.7).
 * <p>
 * 인증 필요 엔드포인트다 (§2.1). 헤더 존재 검증은 서비스가 맡아 누락 시에도
 * 공통 오류 봉투로 응답한다 - 그래서 {@code required = false}다.
 */
@RestController
@RequestMapping("/v0/sessions/{sessionId}/result")
public class ResultController {

    private final ResultService service;

    public ResultController(ResultService service) {
        this.service = service;
    }

    /**
     * 확정된 최종 결과를 반환한다. {@code /complete}가 READY를 준 뒤 결과 화면(KAN-29)이
     * 호출하고, 이후 재조회(새로고침, 앱 복귀)도 같은 응답을 다시 받는다 (AC - 반복 조회
     * 동일 결과). 점수와 등급, 코멘트, 공유 자산 전부 서버 값이다 - 클라이언트 재계산 금지 (§3.7).
     * <p>
     * 점수와 등급이 실리는 개인 결과라 캐시 금지다 - 재응시 즉시 폐기(§3.1)와 만료(410)
     * 전환이 캐시에 가려져도 안 된다.
     *
     * <h4>응답</h4>
     * 200 READY(§3.7 - scores/tier/comment/share/testVersion/scoreVersion/expiresAt) /
     * 401 토큰 누락이나 만료({@code SESSION_EXPIRED}) /
     * 403 다른 세션의 토큰({@code SESSION_FORBIDDEN}) /
     * 409 아직 준비 안 됨({@code RESULT_NOT_READY} + {@code pendingItems}) /
     * 409 전부 실패한 문항 존재 - 재녹음 필요({@code RESULT_RETAKE_REQUIRED} + {@code retakeItems}) /
     * 410 보관 기간(24시간) 만료({@code RESULT_EXPIRED} - 다시 테스트 안내) /
     * 422 미제출 문항 존재({@code RESULT_INCOMPLETE} + {@code missingItems}).
     *
     * @param sessionId     세션 생성 응답의 {@code sessionId}
     * @param authorization {@code Bearer } + 세션 토큰
     */
    @GetMapping
    ResponseEntity<ResultResponse> result(
            @PathVariable String sessionId,

            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            @Nullable String authorization) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.result(sessionId, authorization));
    }
}
