package app.accentury.backend.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "어떤 예외가 터지면 어떤 응답이 나가는가"의 실행 가능한 명세.
 * <p>
 * API 명세서 §2.3(오류 봉투)과 §2.4(오류 코드) 및 KAN-58 AC
 * "존재하지 않는 경로 요청 시 공통 오류 봉투 JSON이 반환된다"를 검증한다.
 * {@code @WebMvcTest} 슬라이스라 Docker와 DB 없이 실행된다.
 * 대상을 ThrowingController로 한정한다 - 프로덕션 컨트롤러(세션 등)가 늘어도
 * 이 슬라이스가 그 의존성을 요구하지 않도록.
 */
@WebMvcTest(GlobalExceptionHandlerTest.ThrowingController.class)
@Import(GlobalExceptionHandlerTest.ThrowingController.class)
// 슬라이스라 IntegrationTest를 상속하지 않는다 - 프로파일만 직접 맞춘다
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 예외를 실제로 던져줄 테스트 전용 컨트롤러 - 프로덕션 컨트롤러가 생기기 전까지의 대역
     */
    @RestController
    @RequestMapping("/test")
    static class ThrowingController {

        record EchoRequest(@NotBlank(message = "필수 값입니다") String name) {
        }

        @GetMapping("/expired")
        void expired() {
            throw new ApiException(ErrorCode.SESSION_EXPIRED);
        }

        @GetMapping("/custom-message")
        void customMessage() {
            throw new ApiException(ErrorCode.RESULT_INCOMPLETE, "음성 2번 문항이 누락되었습니다");
        }

        @GetMapping("/rate-limited")
        void rateLimited(@RequestParam long ms) {
            throw ApiException.rateLimited(ms);
        }

        @GetMapping("/items/{id}")
        String item(@PathVariable long id) {
            return "item-" + id;
        }

        @PostMapping("/echo")
        String echo(@Valid @RequestBody EchoRequest request) {
            return request.name();
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("내부 구현 정보 - 클라이언트에 노출되면 안 됨");
        }
    }

    // === ApiException → ErrorCode가 정한 상태와 봉투 ===

    @Test
    void 비즈니스_예외는_ErrorCode의_상태와_기본_메시지로_변환된다() throws Exception {
        mockMvc.perform(get("/test/expired"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(CorrelationIdFilter.HEADER))
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
                .andExpect(jsonPath("$.message").value(ErrorCode.SESSION_EXPIRED.defaultMessage()))
                .andExpect(jsonPath("$.retryable").value(false))
                // §2.3 - retryAfterMs는 429가 아니어도 null로 "존재"해야 한다
                .andExpect(jsonPath("$.retryAfterMs").value(nullValue()))
                .andExpect(jsonPath("$.correlationId").value(startsWith("c_")))
                // §2.3 봉투는 정확히 5개 필드 - 필드가 늘면 이 테스트가 알려준다
                .andExpect(jsonPath("$").value(aMapWithSize(5)));
    }

    @Test
    void 메시지를_교체해도_상태와_retryable은_ErrorCode를_따른다() throws Exception {
        mockMvc.perform(get("/test/custom-message"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RESULT_INCOMPLETE"))
                .andExpect(jsonPath("$.message").value("음성 2번 문항이 누락되었습니다"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    // === 429 - Retry-After 헤더 (§2.2, KAN-28, 34) ===

    @Test
    void 요청_제한은_429와_Retry_After_헤더를_반환한다() throws Exception {
        mockMvc.perform(get("/test/rate-limited").param("ms", "3000"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.retryAfterMs").value(3000));
    }

    @Test
    void Retry_After는_초_단위_올림이다() throws Exception {
        // 1500ms를 내림(1초)하면 클라이언트가 너무 일찍 재시도해 또 429를 맞는다
        mockMvc.perform(get("/test/rate-limited").param("ms", "1500"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"));
    }

    // === 클라이언트 실수 → 4xx (500으로 새면 안 된다) ===

    @Test
    void 경로_파라미터_타입_불일치는_400이다() throws Exception {
        mockMvc.perform(get("/test/items/숫자아님"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("id")));
    }

    @Test
    void 검증_실패는_400과_첫_번째_필드_오류_메시지다() throws Exception {
        mockMvc.perform(post("/test/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("name: 필수 값입니다"));
    }

    @Test
    void 깨진_JSON은_500이_아니라_400이다() throws Exception {
        mockMvc.perform(post("/test/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{깨진 json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 없는_경로는_404_공통_봉투다() throws Exception {
        // KAN-58 AC - 존재하지 않는 경로 요청 시 공통 오류 봉투 JSON이 반환된다
        mockMvc.perform(get("/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value(startsWith("c_")));
    }

    @Test
    void 미지원_HTTP_메서드는_405다() throws Exception {
        mockMvc.perform(post("/test/expired"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 미지원_미디어_타입은_415다() throws Exception {
        mockMvc.perform(post("/test/echo")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("MEDIA_TYPE_UNSUPPORTED"));
    }

    // === 오류 봉투는 캐시되지 않는다 (Claude 리뷰 P2) ===

    @Test
    void 오류_응답에는_캐시_금지_지시자가_붙는다() throws Exception {
        // 404, 405, 410은 지시자가 없으면 휴리스틱 캐싱 대상이다 (RFC 9110 §15.1).
        // ApiException 경로와 프레임워크 예외 경로가 서로 다른 메서드로 나가므로 둘 다 확인한다
        mockMvc.perform(get("/test/expired"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mockMvc.perform(get("/no-such-path"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mockMvc.perform(post("/test/expired"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    // === 우리 잘못 → 500, 내부 정보는 숨긴다 (NFR-SC-07) ===

    @Test
    void 예상치_못한_예외는_500이고_내부_메시지를_숨긴다() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.defaultMessage()))
                .andExpect(jsonPath("$.message").value(not(containsString("내부 구현 정보"))))
                .andExpect(jsonPath("$.retryable").value(true));
    }
}
