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
     * 요청 제한의 기준 IP. 프록시(ALB) 뒤 배포가 기준이라 X-Forwarded-For 첫 값을
     * 우선한다 - 신뢰 프록시 검증과 직접 노출 시 스푸핑 대응은 KAN-28에서 다룬다.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
