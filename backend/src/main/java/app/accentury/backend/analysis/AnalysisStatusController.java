package app.accentury.backend.analysis;

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
 * {@code GET /v0/sessions/{sessionId}/analyses[/{jobId}]} (KAN-24, API 명세서 §3.4).
 * <p>
 * 분석 대기 화면(KAN-14)의 폴링 대상이다. 인증 필요 엔드포인트이며(§2.1),
 * 헤더 존재 검증은 서비스가 맡아 누락 시에도 공통 오류 봉투로 응답한다.
 * <p>
 * 상태는 매 조회가 최신이어야 한다 - 캐시 지시가 없으면 브라우저(웹 테스트, KAN-31)가
 * 휴리스틱으로 응답을 재사용해 완료를 가릴 수 있으므로, 두 응답 모두
 * {@code Cache-Control: no-store}다 (Codex sol 리뷰 P2).
 */
@RestController
@RequestMapping("/v0/sessions/{sessionId}/analyses")
public class AnalysisStatusController {

    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final AnalysisStatusService service;

    public AnalysisStatusController(AnalysisStatusService service) {
        this.service = service;
    }

    /**
     * 전체 음성 문항의 분석 상태를 한 번에 조회한다 - 대기 화면이 문항 수만큼 폴링하지 않게 한다.
     * <p>
     * 세션 버전의 음성 문항 5개가 seq 순서로 전부 실리고, 업로드한 적 없는 문항은
     * {@code NOT_SUBMITTED}다. <b>점수는 없다</b> - 진행 중에는 품질 상태와 오류 코드만 주고,
     * 점수는 테스트 완료 후 {@code /result}가 한 번에 공개한다.
     *
     * <h4>같은 문항을 여러 번 올렸다면</h4>
     * 가장 최근 업로드가 분석 중이면 {@code PROCESSING}, 아니면 성공한 업로드가 하나라도
     * 있으면 {@code COMPLETED}다(채점은 최신 성공 시도를 쓰므로 재녹음이 필요 없다).
     * 실패만 남은 문항이 {@code RETRYABLE_FAILED}({@code error.retryable=true})로 재녹음 대상이다.
     *
     * <h4>폴링 규칙</h4>
     * 응답의 {@code pollAfterMs}만큼 기다렸다가 다시 조회한다 - 서버가 혼잡하면 이 값을
     * 올려서 준다. 폴링은 분석 대기 화면에서만 하고, 문항 진행 중에는 하지 않는다.
     *
     * <h4>응답</h4>
     * 200 문항별 상태 목록 /
     * 401 토큰 누락이나 만료({@code SESSION_EXPIRED}) /
     * 403 다른 세션의 토큰({@code SESSION_FORBIDDEN}).
     *
     * @param sessionId     세션 생성 응답의 {@code sessionId}
     * @param authorization {@code Bearer } + 세션 토큰
     */
    @GetMapping
    ResponseEntity<AnalysisStatusResponse> statuses(
            @PathVariable String sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            @Nullable String authorization) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(service.statuses(sessionId, authorization));
    }

    /**
     * 시도(분석 작업) 1건의 상태를 조회한다 - 업로드 202 응답의 {@code analysisJobId}로 찾는다.
     * <p>
     * 일괄 조회와 같은 스키마에, 완료된 작업이면 {@code modelVersion}과 {@code scoreVersion}이
     * 더 실린다. 문항 대표 상태가 아니라 이 작업 자체의 상태다 - 같은 문항의 다른 시도와 무관하다.
     * 점수는 여기에도 없다.
     *
     * <h4>응답</h4>
     * 200 작업 상태 /
     * 401 토큰 누락이나 만료({@code SESSION_EXPIRED}) /
     * 403 다른 세션의 토큰({@code SESSION_FORBIDDEN}) /
     * 404 이 세션에 없는 작업({@code RESOURCE_NOT_FOUND}) - 다른 세션의 작업도 같은 404다.
     *
     * @param sessionId     세션 생성 응답의 {@code sessionId}
     * @param jobId         업로드 202 응답의 {@code analysisJobId} (예: {@code a_...})
     * @param authorization {@code Bearer } + 세션 토큰
     */
    @GetMapping("/{jobId}")
    ResponseEntity<AnalysisJobStatusResponse> status(
            @PathVariable String sessionId,
            @PathVariable String jobId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            @Nullable String authorization) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(service.status(sessionId, jobId, authorization));
    }
}
