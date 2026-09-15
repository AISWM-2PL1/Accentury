package app.accentury.backend.feedback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 슬랙 Incoming Webhook 호출의 요청 모양 (KAN-211 2단계).
 * <p>
 * 슬랙을 {@link MockRestServiceServer}로 흉내 낸다 - 실제 웹훅은 어느 테스트에서도 부르지 않는다
 * ({@code RestAiAnalysisClientTest}와 같은 방식). 여기서 보는 것은 슬랙 문서가 요구하는 요청
 * 모양({@code POST}, JSON, {@code text} 한 필드)과, 실패가 예외로 올라오는 것 둘이다.
 */
class RestSlackWebhookClientTest {

    /** 슬랙 URL 형식만 흉내 낸 가짜다 - 실제 채널과 무관하고 이 테스트는 네트워크에 나가지 않는다. */
    private static final String WEBHOOK = "https://hooks.slack.com/services/T000/B000/test";

    private MockRestServiceServer server;
    private RestSlackWebhookClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestSlackWebhookClient(builder.build(), WEBHOOK, new ObjectMapper());
    }

    @Test
    void 본문은_text_한_필드의_JSON이다() {
        server.expect(requestTo(WEBHOOK))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"text\":\"[local] 새 이용 후기\"}"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        client.post("[local] 새 이용 후기");

        server.verify();
    }

    @Test
    void 따옴표와_줄바꿈이_든_메시지도_깨지지_않는다() {
        // 본문 JSON을 손으로 만들면 여기서 틀린다 - 후기 본문은 사용자가 쓴 자유 서술이다.
        String text = "머리 줄\n> \"따옴표\"와 역슬래시 \\ 가 든 후기";
        server.expect(requestTo(WEBHOOK))
                .andExpect(content().json(new ObjectMapper().writeValueAsString(
                        java.util.Map.of("text", text))))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        client.post(text);

        server.verify();
    }

    @Test
    void 슬랙이_500이면_예외로_올라온다() {
        // 삼키는 것은 호출자(FeedbackSlackNotifier)의 몫이다 - 클라이언트는 실패를 숨기지 않는다.
        server.expect(requestTo(WEBHOOK))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(RuntimeException.class, () -> client.post("아무 메시지"));
    }
}
