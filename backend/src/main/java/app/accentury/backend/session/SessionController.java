package app.accentury.backend.session;

import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
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
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 바디 전체가 선택이다 - 빈 요청으로도 세션이 생긴다 (§3.1 - 모든 입력 필드 optional) */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse create(@Valid @RequestBody(required = false) @Nullable CreateSessionRequest request) {
        return sessionService.create(request);
    }
}
