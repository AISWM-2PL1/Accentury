package app.accentury.backend.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 문서가 실제 구현을 그대로 반영하는지 검증한다.
 * <p>
 * 문서가 손으로 쓴 파일이 아니라 코드에서 생성되므로 어긋날 일이 없다는 게 이 방식의 전제인데,
 * 애너테이션이 빠지거나 잘못 붙으면 그 전제가 조용히 깨진다. 그걸 여기서 잡는다.
 * <p>
 * 문서 <b>내용</b>(설명 문구)까지 단언하지는 않는다. 그건 고칠 때마다 테스트가 깨져서 아무도 안 고치게 된다.
 * 대신 <b>구조</b>(엔드포인트가 다 있는지, 보안 스킴이 붙었는지, multipart가 파일로 렌더되는지)만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void 문서가_OpenAPI_3_1로_생성된다() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(startsWith("3.1")))
                .andExpect(jsonPath("$.info.title").value("Accentury App-Backend API"));
    }

    @Test
    void 구현된_엔드포인트가_모두_문서에_있다() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/v0/sessions'].post").exists())
                .andExpect(jsonPath("$.paths['/v0/tests/{testVersion}'].get").exists())
                .andExpect(jsonPath(
                        "$.paths['/v0/sessions/{sessionId}/voice-items/{itemId}/recording'].post").exists());
    }

    @Test
    void 업로드는_세션_토큰_보안_스킴을_요구한다() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.sessionToken.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/v0/sessions/{sessionId}/voice-items/{itemId}/recording']"
                        + ".post.security[0].sessionToken").exists());
    }

    /** Swagger UI에서 파일 선택기가 뜨려면 binary여야 한다 - 문자열로 나오면 리뷰어가 업로드를 못 한다 */
    @Test
    void 업로드_audio_파트가_바이너리로_문서화된다() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/v0/sessions/{sessionId}/voice-items/{itemId}/recording']"
                        + ".post.requestBody.content['multipart/form-data'].schema.properties.audio.format")
                        .value("binary"));
    }

    /**
     * meta는 손으로 채워야 하는 JSON이라 예시가 없으면 리뷰어가 무엇을 넣을지 알 수 없다.
     * swagger-core가 예시 문자열을 JSON으로 파싱해 넣으므로 문자열이 아니라 객체로 단언한다.
     */
    @Test
    void 업로드_meta_파트에_예시가_있다() throws Exception {
        String meta = "$.paths['/v0/sessions/{sessionId}/voice-items/{itemId}/recording']"
                + ".post.requestBody.content['multipart/form-data'].schema.properties.meta";
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(meta + ".example.durationMs").exists())
                // 4개 필드가 다 필수라 하나라도 빠진 예시는 그대로 400을 받는다
                .andExpect(jsonPath(meta + ".example.clientQuality.rms").exists())
                .andExpect(jsonPath(meta + ".example.clientQuality.peak").exists())
                .andExpect(jsonPath(meta + ".example.clientQuality.silenceRatio").exists())
                .andExpect(jsonPath(meta + ".example.clientQuality.clipped").exists());
    }

    /** 오류 응답이 어디서나 같은 형식이라는 게 계약이므로 스키마가 실제로 붙어 있어야 한다 */
    @Test
    void 오류_응답이_공통_봉투_스키마를_참조한다() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ErrorResponse.properties.code").exists())
                // 미디어 타입도 같이 본다 - 명시하지 않으면 */*로 새어 나간다
                .andExpect(jsonPath("$.paths['/v0/sessions'].post.responses['400']"
                        + ".content['application/json'].schema.$ref").value(containsString("ErrorResponse")))
                .andExpect(jsonPath("$.paths['/v0/sessions/{sessionId}/voice-items/{itemId}/recording']"
                        + ".post.responses['429'].content['application/json'].schema.$ref")
                        .value(containsString("ErrorResponse")));
    }

    /**
     * 리다이렉트만 확인하면 webjar이 클래스패스에서 빠져도 통과한다.
     * 실제 화면이 뜨는지는 정적 자원까지 받아 봐야 안다.
     */
    @Test
    void Swagger_UI가_열린다() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("swagger-ui")));

        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());

        mvc.perform(get("/swagger-ui/swagger-ui-bundle.js"))
                .andExpect(status().isOk());
    }

    /**
     * 인라인 코드가 위아래 줄을 덮는 문제를 {@code SwaggerUiStyleTransformer}가 고친다.
     * springdoc이 기본 transformer를 되찾아가면 이 단언이 깨진다.
     */
    @Test
    void 인라인_코드_겹침을_막는_스타일이_주입된다() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("renderedMarkdown code")))
                .andExpect(content().string(containsString("line-height: 1.9")));
    }
}
