package app.accentury.backend.upload;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청 제한의 기준 IP 추출 (KAN-23, API 명세서 §2.5).
 * <p>
 * 프록시(ALB) 뒤 배포가 기준이며 ALB는 실제 접속 IP를 X-Forwarded-For의 <b>마지막</b>에
 * 덧붙인다 - 첫 값은 클라이언트가 위조할 수 있어 제한 우회와 윈도우 맵 팽창에 쓰일 수
 * 있으므로 마지막 값만 신뢰한다 (Codex sol 리뷰 P1). 원본 서버 직접 접근은 보안그룹이
 * 막는 배포가 전제이고, 신뢰 프록시 체인 검증은 KAN-28에서 다룬다.
 */
final class ClientIps {

    private ClientIps() {
    }

    static String from(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].strip();
        }
        return request.getRemoteAddr();
    }
}
