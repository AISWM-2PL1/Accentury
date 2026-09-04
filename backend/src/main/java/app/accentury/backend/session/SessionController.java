package app.accentury.backend.session;

import app.accentury.backend.common.AdminAuth;
import app.accentury.backend.common.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v0/sessions} - 익명 테스트 세션 생성 (KAN-9, API 명세서 §3.1).
 * <p>
 * 인증 불필요 엔드포인트다 (§2.1). 재응시도 같은 호출이다 - 이전 세션의 토큰을
 * {@code Authorization} 헤더로 함께 보내면 그 세션과 결과를 즉시 폐기한다 (KAN-107).
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
     * 테스트를 시작할 때 가장 먼저 호출한다. 인증이 필요 없고, 결과 화면의 '다시 테스트하기'도 같은 호출이다.
     * 바디 전체가 선택이라 통째로 생략해도 세션이 생긴다 (§3.1 - 모든 입력 필드 optional).
     * 개인 식별 정보를 받지 않으며 권역 파라미터도 없다(경남 고정).
     * <p>
     * 검증용 스모크(KAN-138)는 {@code X-Admin-Token} 헤더를 함께 보내 이 세션을 합성 트래픽으로
     * 표시한다 - 그 세션의 응시와 완주는 익명 집계(KAN-106)에서 실사용자와 다른 통에 쌓인다.
     * 표시는 여기 한 번뿐이고 이후 요청은 헤더가 필요 없다. 일반 클라이언트는 이 헤더를 보내지
     * 않으며, 틀린 토큰으로 보내면 401 {@code ADMIN_UNAUTHORIZED}다.
     * <p>
     * 재응시라면 이전 세션의 토큰을 {@code Authorization: Bearer}로 함께 보낸다 (KAN-107, §3.1) -
     * 이전 세션과 하위 데이터가 즉시 폐기된 뒤 새 세션이 발급된다. 만료됐거나 존재하지 않는
     * 토큰은 조용히 무시되고 응답이 최초 응시와 구분되지 않는다 - 401도 404도 주지 않는다.
     * <p>
     * 응답의 {@code testVersion}과 {@code scoreVersion}은 이 세션에 고정된다.
     * 테스트 도중 서버에 새 버전이 올라가도 이 세션은 시작할 때의 문항과 점수 기준을 그대로 쓴다.
     * <p>
     * 201 세션 생성됨 / 400 {@code campaignToken} 형식 위반 등 입력 검증 실패({@code VALIDATION_FAILED}) /
     * 401 {@code X-Admin-Token}이 틀렸거나 이 서버가 관리자 토큰을 설정하지 않음
     * ({@code ADMIN_UNAUTHORIZED}, KAN-138) /
     * 429 IP 분당 세션 생성 제한 초과({@code RATE_LIMITED} + {@code Retry-After}, §2.5).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse create(@Valid @RequestBody(required = false) @Nullable CreateSessionRequest request,
                                  @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                  @Nullable String authorization,

                                  // 검증용 스모크의 합성 트래픽 표시 (KAN-138) - 일반 클라이언트는 보내지 않는다.
                                  @RequestHeader(value = AdminAuth.TOKEN_HEADER, required = false)
                                  @Nullable String adminToken,

                                  HttpServletRequest httpRequest) {
        return sessionService.create(request, clientIps.resolve(httpRequest), authorization, adminToken);
    }
}
