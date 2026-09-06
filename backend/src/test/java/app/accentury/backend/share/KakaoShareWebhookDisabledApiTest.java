package app.accentury.backend.share;

import app.accentury.backend.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웹훅 경로의 기본 상태 - <b>키를 설정하지 않으면 아예 없다</b> (KAN-164, 관리자 API와 같은 계열).
 * <p>
 * 기본 테스트 프로파일에는 {@code accentury.share.kakao-admin-key}가 없으므로 이 컨텍스트가 곧
 * 키 없는 배포의 상태다 - "키만 비우면 잠긴다"가 아니라 "빈이 뜨지 않는다"까지 확인한다.
 */
@AutoConfigureMockMvc
class KakaoShareWebhookDisabledApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void 키가_설정되지_않으면_컨트롤러와_인증기_빈이_없다() {
        assertEquals(0, context.getBeanNamesForType(KakaoShareWebhookController.class).length);
        assertEquals(0, context.getBeanNamesForType(KakaoWebhookAuth.class).length);
    }

    @Test
    void 키가_설정되지_않으면_경로가_404다() throws Exception {
        // 다른 없는 경로와 구분되지 않는다 - 있는데 잠겼다는 신호조차 주지 않는다.
        mockMvc.perform(post("/v0/share/kakao/webhook")
                        .header(HttpHeaders.AUTHORIZATION, "KakaoAK whatever")
                        .header("X-Kakao-Resource-ID", "r-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
