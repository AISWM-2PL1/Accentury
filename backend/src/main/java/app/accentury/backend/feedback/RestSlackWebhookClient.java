package app.accentury.backend.feedback;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * 슬랙 Incoming Webhook 호출 (KAN-211 2단계).
 * <p>
 * 슬랙 SDK를 넣지 않는다. 요청이 {@code POST <웹훅 URL>}에 JSON 본문
 * {@code {"text": "..."}} 하나뿐이라(https://api.slack.com/messaging/webhooks) 의존성 하나를
 * 늘려 얻을 것이 없다. 응답 본문도 읽지 않는다 - 성공은 {@code ok} 한 단어이고, 실패는 상태
 * 코드로 충분하다.
 * <p>
 * 타임아웃은 연결 3초, 읽기 3초다. 알림은 부수 기능이라 오래 매달릴 이유가 없고, 이 호출은
 * 후기 저장 트랜잭션이 커밋된 뒤 전용 실행기에서 도는 것이라({@link FeedbackSlackNotifier})
 * 늦어져도 사용자 응답과는 무관하다 - 그래도 짧게 끊어야 실행기의 워커 하나가 슬랙 장애에
 * 오래 묶이지 않는다.
 */
class RestSlackWebhookClient implements SlackWebhookClient {

    /** 슬랙에 붙는 데까지 - 공용 인터넷 한 홉이다. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** 응답을 받기까지 - 슬랙은 즉답하므로 연결과 같은 값으로 둔다. */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final RestClient restClient;
    private final String webhookUrl;
    private final ObjectMapper objectMapper;

    RestSlackWebhookClient(RestClient restClient, String webhookUrl, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.webhookUrl = webhookUrl;
        this.objectMapper = objectMapper;
    }

    /**
     * 이 클라이언트 전용 {@link RestClient}.
     * <p>
     * Boot의 {@code RestClient.Builder} 자동 구성은 webmvc 스타터에 없어 정적 빌더로 조립한다
     * (AI 호출 쪽 {@code AnalysisDispatchConfig}와 같은 이유). 기준 URL을 두지 않는 것은 웹훅
     * URL이 설정값 한 덩어리로 오기 때문이다 - 호스트와 경로를 갈라 두면 둘이 어긋날 자리만 생긴다.
     */
    static RestClient restClient() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 본문 JSON은 {@link ObjectMapper}가 만든다 - 후기 본문이 그대로 실리므로 따옴표와 줄바꿈
     * 이스케이프를 손으로 하면 언젠가 틀린다. 2xx가 아니면 {@code RestClient}의 기본 상태 처리가
     * 예외를 던지고, 호출자가 그것을 잡아 로그만 남긴다.
     */
    @Override
    public void post(String text) {
        restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(Map.of("text", text)))
                .retrieve()
                .toBodilessEntity();
    }
}
