package app.accentury.backend.common;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;

/**
 * 요청 제한의 기준 IP 결정 (API 명세서 §2.5, KAN-23 도입, KAN-28 신뢰 프록시 검증).
 * <p>
 * {@code X-Forwarded-For}는 클라이언트가 마음대로 쓸 수 있는 헤더다. 검증 없이 읽으면
 * 요청마다 다른 값을 넣어 IP 제한을 통째로 우회하고 윈도우 맵까지 부풀릴 수 있다.
 * 그래서 <b>직접 접속한 상대({@code remoteAddr})가 신뢰 프록시일 때만</b> 헤더를 읽는다
 * ({@code accentury.trusted-proxies}). 신뢰 목록이 비어 있으면(기본값) 헤더를 아예 무시하므로,
 * 프록시 없이 뜬 서버는 위조에 영향받지 않는다.
 * <p>
 * 헤더를 읽을 때는 <b>오른쪽부터</b> 신뢰 프록시를 걷어내고 처음 만나는 값이 실제
 * 접속자다 - 프록시는 자기가 본 상대를 오른쪽에 덧붙이므로 왼쪽으로 갈수록 위조 가능
 * 구간이다. 형식이 이상한 값을 만나면 거기서 멈추고 직접 접속 IP로 되돌아간다 -
 * 판정 불가를 "제한 없음"으로 해석하지 않는다.
 */
@Component
public class ClientIps {

    private static final Logger log = LoggerFactory.getLogger(ClientIps.class);

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final List<Cidr> trustedProxies;

    public ClientIps(AccenturyProperties properties) {
        this.trustedProxies = properties.trustedProxies().stream().map(Cidr::parse).toList();
        if (trustedProxies.isEmpty()) {
            // 프록시 없이 뜬 서버에는 이것이 맞는 기본값이라 기동을 세우지는 않는다. 다만
            // 로드밸런서 뒤라면 전원이 그 IP 하나를 공유해 서로의 한도를 깎으므로(KAN-28 §2.5)
            // 배포 로그에 반드시 남겨야 한다 - 이 설정의 잘못은 예외도 오류도 없이 "왜 남들
            // 때문에 429가 나지"로만 드러나서, 경고가 없으면 부하 테스트에서야 발견된다
            log.warn("accentury.trusted-proxies가 비어 있다 - X-Forwarded-For를 무시하고 접속 IP만"
                    + " 요청 제한 키로 쓴다. 프록시나 로드밸런서 뒤에 배포했다면 그 대역을 반드시 지정해야 한다");
        } else {
            log.info("요청 제한 기준 IP - 신뢰 프록시 대역 {}개 뒤에서만 X-Forwarded-For를 읽는다",
                    trustedProxies.size());
        }
    }

    /** 이 요청의 제한 기준 IP. 판정할 수 없으면 직접 접속 IP를 그대로 쓴다 */
    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || !isTrusted(peer)) {
            // 신뢰 프록시 뒤가 아니다 - 헤더는 상대가 직접 쓴 값이므로 읽지 않는다
            return peer;
        }
        String header = request.getHeader(FORWARDED_FOR);
        if (header == null || header.isBlank()) {
            return peer;
        }
        String[] hops = header.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = normalize(hops[i]);
            if (hop == null) {
                // 형식 불명 - 이 값도 그 왼쪽도 믿을 수 없다
                return peer;
            }
            if (!isTrusted(hop)) {
                return hop;
            }
        }
        // 전부 신뢰 프록시다 - 체인 안에서 시작된 요청이므로 직접 접속 IP가 최선의 키다
        return peer;
    }

    private boolean isTrusted(String ip) {
        InetAddress address = literal(ip);
        if (address == null) {
            return false;
        }
        return trustedProxies.stream().anyMatch(cidr -> cidr.matches(address));
    }

    /** 공백과 IPv6 대괄호, 뒤에 붙은 포트를 걷어낸 IP 리터럴. 리터럴이 아니면 null */
    private static @Nullable String normalize(String hop) {
        String value = hop.strip();
        if (value.startsWith("[")) {
            // [2001:db8::1]:443 - 대괄호 안이 주소다
            int end = value.indexOf(']');
            value = end > 0 ? value.substring(1, end) : "";
        } else if (value.indexOf(':') != value.lastIndexOf(':')) {
            // 콜론이 둘 이상이면 대괄호 없는 IPv6다 - 포트가 붙을 수 없는 형태다
            return literal(value) != null ? value : null;
        } else if (value.indexOf(':') > 0) {
            // 1.2.3.4:5678 - 콜론이 하나면 IPv4 + 포트다
            value = value.substring(0, value.indexOf(':'));
        }
        return literal(value) != null ? value : null;
    }

    /** 이름 해석 없이 IP 리터럴만 받는다 - 요청 경로에서 DNS를 타면 안 된다 */
    private static @Nullable InetAddress literal(String value) {
        try {
            return InetAddress.ofLiteral(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 신뢰 대역 하나 - {@code 10.0.0.0/8}, {@code 2001:db8::/32}, 또는 단일 IP.
     * 접두 길이를 생략하면 그 주소 하나만 신뢰한다.
     */
    private record Cidr(byte[] prefix, int bits) {

        static Cidr parse(String value) {
            String text = value.strip();
            int slash = text.lastIndexOf('/');
            String addressPart = slash < 0 ? text : text.substring(0, slash);
            InetAddress address = literal(addressPart);
            if (address == null) {
                // 설정 오류를 조용히 "신뢰 안 함"으로 흘리면 배포에서 전원이 한 키를 공유한다 -
                // 기동 시점에 세운다
                throw new IllegalArgumentException(
                        "accentury.trusted-proxies 값이 IP나 CIDR이 아니다: " + value);
            }
            byte[] bytes = address.getAddress();
            int maxBits = bytes.length * 8;
            int bits = maxBits;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(text.substring(slash + 1).strip());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "accentury.trusted-proxies의 접두 길이가 숫자가 아니다: " + value, e);
                }
                if (bits < 0 || bits > maxBits) {
                    throw new IllegalArgumentException(
                            "accentury.trusted-proxies의 접두 길이가 범위를 벗어났다: " + value);
                }
            }
            return new Cidr(bytes, bits);
        }

        boolean matches(InetAddress candidate) {
            byte[] bytes = candidate.getAddress();
            if (bytes.length != prefix.length) {
                // IPv4와 IPv6은 섞어서 비교하지 않는다 - 필요하면 두 대역을 각각 설정한다
                return false;
            }
            int fullBytes = bits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (bytes[i] != prefix[i]) {
                    return false;
                }
            }
            int remaining = bits % 8;
            if (remaining == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remaining);
            return (bytes[fullBytes] & mask) == (prefix[fullBytes] & mask);
        }
    }
}
