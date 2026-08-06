package app.accentury.backend.upload;

import jakarta.servlet.http.HttpServletRequest;
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
 */
@RestController
@RequestMapping("/v0/sessions/{sessionId}/voice-items/{itemId}/recording")
public class VoiceUploadController {

    private final VoiceUploadService service;

    public VoiceUploadController(VoiceUploadService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    VoiceUploadResponse upload(@PathVariable String sessionId,
                               @PathVariable String itemId,
                               @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                               @Nullable String authorization,
                               @RequestHeader(value = "Idempotency-Key", required = false)
                               @Nullable String idempotencyKey,
                               @RequestPart(value = "audio", required = false)
                               @Nullable MultipartFile audio,
                               @RequestPart(value = "meta", required = false)
                               @Nullable String metaJson,
                               HttpServletRequest request) {
        return service.upload(sessionId, itemId, authorization, idempotencyKey,
                audio, metaJson, clientIp(request));
    }

    /**
     * 요청 제한의 기준 IP. 프록시(ALB) 뒤 배포가 기준이며 ALB는 실제 접속 IP를
     * X-Forwarded-For의 <b>마지막</b>에 덧붙인다 - 첫 값은 클라이언트가 위조할 수 있어
     * 제한 우회와 windows 맵 팽창에 쓰일 수 있으므로 마지막 값만 신뢰한다
     * (Codex sol 리뷰 P1). 원본 서버 직접 접근은 보안그룹이 막는 배포가 전제이고,
     * 신뢰 프록시 체인 검증은 KAN-28에서 다룬다.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].strip();
        }
        return request.getRemoteAddr();
    }
}
