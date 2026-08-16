package app.accentury.backend.session;

import app.accentury.backend.common.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ClientIps clientIps;

    public SessionController(SessionService sessionService, ClientIps clientIps) {
        this.sessionService = sessionService;
        this.clientIps = clientIps;
    }

    /**
     * 익명 세션을 생성한다.
     * <p>
     * 테스트를 시작할 때 가장 먼저 호출한다. 인증이 필요 없고, 결과 화면의 '재응시하기'도 같은 호출이다.
     * 바디 전체가 선택이라 통째로 생략해도 세션이 생긴다 (§3.1 - 모든 입력 필드 optional).
     * 개인 식별 정보를 받지 않으며 권역 파라미터도 없다(경남 고정).
     * <p>
     * 응답의 {@code testVersion}과 {@code scoreVersion}은 이 세션에 고정된다.
     * 테스트 도중 서버에 새 버전이 올라가도 이 세션은 시작할 때의 문항과 점수 기준을 그대로 쓴다.
     * <p>
     * 201 세션 생성됨 / 400 {@code campaignToken} 형식 위반 등 입력 검증 실패({@code VALIDATION_FAILED}) /
     * 429 IP 분당 세션 생성 제한 초과({@code RATE_LIMITED} + {@code Retry-After}, §2.5).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse create(@Valid @RequestBody(required = false) @Nullable CreateSessionRequest request,
                                  HttpServletRequest httpRequest) {
        return sessionService.create(request, clientIps.resolve(httpRequest));
    }
}
