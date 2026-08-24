package app.accentury.backend.analytics;

import app.accentury.backend.common.AdminAuth;
import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 세션 생성 요청이 합성 트래픽인지 판정한다 (KAN-138).
 * <p>
 * 표시는 {@code POST /v0/sessions}의 {@link AdminAuth#TOKEN_HEADER} 헤더 하나다. 인증을
 * 요구하는 이유는 분명하다 - 헤더만으로 빼 준다면 누구나 자기 응시를 통계에서 제외할 수
 * 있고, 그러면 통계 자체가 신뢰를 잃는다. 검증 수단은 관리자 API(§6)와 같은 공유 시크릿을
 * 그대로 쓴다. 스모크를 돌리는 것은 운영자 행위이고, 비밀을 하나 더 만들 이유가 없다.
 * <p>
 * <b>표시를 시도했는데 검증할 수 없으면 통과시키지 않는다.</b> 토큰이 설정되지 않은 서버에서
 * 헤더만 보고 조용히 {@link Traffic#REAL}로 흘리면, 파이프라인이 시크릿을 빠뜨린 날 오염이
 * 그대로 들어가고 아무도 모른다 - 스모크는 "제외됐겠지" 하고 통과한다. 그래서 401이다.
 * <p>
 * 같은 이유로 <b>빈 헤더도 없는 것으로 치지 않는다</b> (Codex sol 리뷰 P2). 시크릿이 비어 있는
 * 파이프라인은 헤더를 빼는 것이 아니라 빈 값으로 펼쳐 보낸다 - 그것을 실사용자로 읽으면
 * 막으려던 오염이 정확히 그 상황에서 들어온다. 헤더가 아예 없을 때만 실사용자다.
 */
@Component
public class SyntheticTraffic {

    /** 관리자 토큰 미설정 환경에서는 이 빈이 없다 ({@code @ConditionalOnProperty}). */
    private final @Nullable AdminAuth adminAuth;

    SyntheticTraffic(Optional<AdminAuth> adminAuth) {
        this.adminAuth = adminAuth.orElse(null);
    }

    /**
     * 헤더를 보고 이 세션의 트래픽 종류를 정한다.
     *
     * @param adminToken {@code X-Admin-Token} 헤더 - <b>아예 없을 때만</b> 실사용자다.
     *                   빈 값은 표시하려다 실패한 것으로 보고 401이다.
     * @throws ApiException 401 {@code ADMIN_UNAUTHORIZED} - 토큰이 틀렸거나, 이 서버가
     *                      관리자 토큰을 설정하지 않아 표시를 검증할 수 없을 때
     */
    public Traffic resolve(@Nullable String adminToken) {
        // isBlank()를 여기 두지 않는다 - 빈 값은 "표시하지 않음"이 아니라 "표시하려다 실패함"이다.
        if (adminToken == null) {
            return Traffic.REAL;
        }
        if (adminAuth == null) {
            throw new ApiException(ErrorCode.ADMIN_UNAUTHORIZED);
        }
        adminAuth.authorize(adminToken);
        return Traffic.SYNTHETIC;
    }
}
