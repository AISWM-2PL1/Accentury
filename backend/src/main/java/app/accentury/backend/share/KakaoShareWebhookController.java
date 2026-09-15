package app.accentury.backend.share;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * {@code POST /v0/share/kakao/webhook} - 카카오톡 공유 웹훅 수신 (KAN-164, API 명세서 §3.8).
 * <p>
 * 카카오가 공유 메시지 전달에 성공하면 콘솔에 등록한 이 URL로 콜백을 보낸다. 우리가 하는 일은
 * 셋이다 - Admin 키로 카카오임을 확인하고({@link KakaoWebhookAuth}), 리소스 ID로 중복을 거르고,
 * 일자와 캠페인별 카운터를 1 올린다 ({@link ShareCounters}). 3초 안에 2xx를 돌려줘야 한다는
 * 카카오의 요구는 DB 두 문장이면 넉넉하다.
 * <p>
 * 인증이 첫 관문이다 - <b>본문을 읽기 전에</b> 검사한다. {@code @RequestBody}로 받으면 Spring이 핸들러에
 * 들어오기 전에 본문을 통째로 버퍼링하므로 키 없는 호출자도 힙과 요청 스레드를 쓰게 된다 (Codex sol
 * 리뷰 P1). 그래서 본문은 {@link HttpServletRequest}에서 인증 뒤에 직접, 상한({@link #MAX_BODY_BYTES})까지만
 * 읽는다. 키 없는 호출자는 본문 형식 피드백도 받지 않는다 (관리자 API의 일자 파싱과 같은 순서,
 * 2026-08-17 리뷰). 요청 제한 축은 두지 않는다 - 미인증 요청은 DB에 닿지 않고, 키는 카카오와 우리만
 * 아는 무작위 값이다 (§6.1의 같은 판단).
 * <p>
 * <b>본문은 관대하게 읽는다.</b> 카카오 문서(kakaotalk-share/callback)의 POST 예시는 JSON이지만 폼
 * 인코딩({@code application/x-www-form-urlencoded})으로 온다는 보고도 있어 둘 다 받는다 (Codex sol 리뷰
 * P2) - JSON으로 읽히지 않으면 폼으로 한 번 더 푼다. 우리에게 필요한 값은 앱이 {@code serverCallbackArgs}로
 * 실은 {@code campaign} 하나뿐이고, 그것마저 없으면 {@code unknown}으로 센다 - 카운트를 잃는 것보다
 * 캠페인 이름이 흐린 편이 낫다. 채팅방 정보(CHAT_TYPE, HASH_CHAT_ID)는
 * 읽지도 저장하지도 않는다. 캠페인 값은 형식({@link #CAMPAIGN})을 지킬 때만 그대로 저장한다 -
 * 자유 문자열을 그대로 두면 앱 버그나 위조가 DB에 임의 문자열을 남기는 통로가 된다 (AC "저장된
 * 행에 세션 id, 토큰, 점수가 없다").
 * <p>
 * GET은 받지 않는다 (405). 카카오 콘솔에서 메서드를 POST로 등록한다 - GET은 사용자 정의 값이 URL
 * 쿼리로 오고, 그 URL이 엣지(CloudFront, WAF) 로그에 남는다.
 */
@RestController
@RequestMapping(KakaoShareWebhookController.PATH)
@ConditionalOnProperty(prefix = "accentury.share", name = "kakao-admin-key")
class KakaoShareWebhookController {

    static final String PATH = "/v0/share/kakao/webhook";

    /** 카카오가 웹훅마다 붙이는 고유 ID - 멱등 키다. 없으면 중복을 가릴 수 없으므로 받지 않는다. */
    static final String RESOURCE_ID_HEADER = "X-Kakao-Resource-ID";

    /** 앱이 {@code serverCallbackArgs}에 넣는 키 - Android {@code ResultSharer}, iOS {@code ShareCard}와 같은 이름이다. */
    static final String CAMPAIGN_KEY = "campaign";

    /** 캠페인이 없거나 형식이 틀린 콜백이 들어가는 통. */
    static final String UNKNOWN_CAMPAIGN = "unknown";

    /** 저장을 허용하는 캠페인 형식 - 컬럼 길이(32)와 키 구분자({@code |}) 배제가 여기서 정해진다. */
    private static final Pattern CAMPAIGN = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /** 리소스 ID 컬럼 길이 - 카카오의 ID 형식은 문서에 없어 넉넉히 잡고, 넘으면 받지 않는다. */
    private static final int MAX_RESOURCE_ID_LENGTH = 128;

    /**
     * 읽어 들일 본문 상한. 카카오의 콜백은 채팅방 정보와 사용자 정의 키 몇 개라 수백 바이트이고, 그보다
     * 훨씬 큰 본문은 카카오가 아니다 - 인증을 통과한 호출이라도 여기서 끊어 힙을 지킨다 (Codex sol 리뷰 P1).
     */
    static final int MAX_BODY_BYTES = 16 * 1024;

    private static final Logger log = LoggerFactory.getLogger(KakaoShareWebhookController.class);

    private final KakaoWebhookAuth auth;
    private final ShareCounters counters;
    private final ObjectMapper objectMapper;

    KakaoShareWebhookController(KakaoWebhookAuth auth, ShareCounters counters, ObjectMapper objectMapper) {
        this.auth = auth;
        this.counters = counters;
        this.objectMapper = objectMapper;
    }

    /**
     * 200 받았다 (새로 셌든 중복이라 안 셌든 - 카카오에게 둘은 같은 "잘 받았다"다. 문서는 2xx라고
     * 하지만 200만 성공으로 보고 나머지를 재전송한다는 보고가 있어 204가 아니라 200이다, Codex 리뷰) /
     * 400 리소스 ID 누락이나 길이 초과, 본문 상한 초과 ({@code VALIDATION_FAILED}) /
     * 401 {@code Authorization} 누락이나 불일치 ({@code SHARE_WEBHOOK_UNAUTHORIZED}).
     */
    @PostMapping
    ResponseEntity<Void> receive(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) @Nullable String authorization,
            @RequestHeader(value = RESOURCE_ID_HEADER, required = false) @Nullable String resourceId,
            HttpServletRequest request) {
        auth.authorize(authorization);
        String id = requireResourceId(resourceId);
        String campaign = campaignOf(readBody(request));
        boolean counted = counters.recordSent(id, campaign, Instant.now());
        // 리소스 ID는 카카오가 만든 불투명 값이라 남긴다 - 중복 콜백을 추적하는 유일한 단서다.
        log.info("카카오 공유 웹훅 수신 campaign={} counted={} resourceId={}", campaign, counted, id);
        return ResponseEntity.ok().build();
    }

    private static String requireResourceId(@Nullable String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, RESOURCE_ID_HEADER + " 헤더가 필요합니다.");
        }
        String value = resourceId.strip();
        if (value.length() > MAX_RESOURCE_ID_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    RESOURCE_ID_HEADER + " 헤더가 너무 깁니다 (최대 " + MAX_RESOURCE_ID_LENGTH + "자).");
        }
        return value;
    }

    /**
     * 인증 뒤에 본문을 상한까지만 읽는다. 상한을 넘기면 나머지를 읽지 않고 끊는다 - 그 크기의 본문은
     * 카카오가 보낸 것이 아니고, 다 읽어 주는 것 자체가 비용이다. 문자 집합은 요청이 밝힌 것이 없으면 UTF-8이다.
     */
    private static String readBody(HttpServletRequest request) {
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "본문이 너무 큽니다 (최대 " + MAX_BODY_BYTES + "바이트).");
            }
            String encoding = request.getCharacterEncoding();
            return new String(bytes, encoding != null ? encoding : StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            // 인증을 통과한 호출자가 본문을 다 보내지 않고 끊었다 (Content-Length보다 짧은 전송 등). 서버
            // 잘못이 아니므로 500과 ERROR 스택트레이스가 아니라 400으로 끝낸다 (Claude 리뷰)
            throw new ApiException(ErrorCode.REQUEST_REJECTED, "본문을 끝까지 받지 못했습니다.");
        }
    }

    /**
     * 본문의 {@code campaign} - JSON으로 읽히면 그 안에서만 찾고, JSON이 아닐 때만 폼 인코딩으로 푼다.
     * 어느 쪽에도 없거나 형식이 틀리면 {@code unknown}이다.
     * <p>
     * 폼 폴백은 <b>파싱 실패</b>에만 건다 (Claude 리뷰). "JSON인데 campaign이 없다"까지 폼으로 넘기면 JSON 문자열
     * 값 안의 {@code &campaign=zzz&}를 폼 파서가 집어 존재하지 않는 캠페인 행을 만든다.
     */
    private String campaignOf(String body) {
        if (body.isBlank()) {
            return UNKNOWN_CAMPAIGN;
        }
        String campaign;
        try {
            JsonNode field = objectMapper.readTree(body).path(CAMPAIGN_KEY);
            campaign = field.isString() ? field.asString() : null;
        } catch (JacksonException notJson) {
            campaign = formCampaign(body);
        }
        if (campaign == null || !CAMPAIGN.matcher(campaign).matches()) {
            return UNKNOWN_CAMPAIGN;
        }
        return campaign;
    }

    /**
     * {@code a=b&campaign=kko_share} 형태의 {@code campaign} - 없으면 null. 값 하나를 찾는 것이 전부라 파서를
     * 들이지 않는다. 같은 키가 여러 번이면 첫 값이다. 디코딩이 깨진 조각은 그 조각만 건너뛴다 - 앞 조각이
     * 깨졌다고 뒤의 멀쩡한 {@code campaign}까지 버리면 {@code unknown}으로 샌다 (PR #88 리뷰).
     */
    private static @Nullable String formCampaign(String body) {
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                if (CAMPAIGN_KEY.equals(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8))) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException malformed) {
                continue;
            }
        }
        return null;
    }
}
