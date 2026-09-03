package app.accentury.backend.testdefinition;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.time.Duration;

/**
 * {@code GET /v0/tests/{testVersion}?voiceSet={n}} - 불변 테스트 정의 조회 (KAN-10, KAN-182,
 * API 명세서 §3.2).
 * <p>
 * 인증 불필요 엔드포인트다 (§2.1). 세션은 생성 응답의 {@code testVersion}과 {@code voiceSet}으로
 * 이 경로를 조회하고, 버전과 세트가 URL에 들어가 불변이라 몇 번을 조회해도 같은 정의를 받는다
 * (KAN-10 AC).
 */
@RestController
@RequestMapping("/v0/tests")
public class TestDefinitionController {

    /**
     * 버전 경로는 불변이므로 장기 캐싱을 허용한다 (§3.2 - Cache-Control: immutable, ETag).
     * 내용 교체는 언제나 새 testVersion 발행이다 (KAN-26 버전 불변 원칙).
     * 공유 캐시는 쓰지 않는다 - CDN 미도입 확정으로 public이 아니라 private다
     * (2026-08-09, KAN-101 종결). 버전 경로가 불변이라 브라우저 캐시는 그대로 유효하다.
     * 세트 번호가 쿼리에 들어가므로 세트마다 캐시 키가 갈린다 (KAN-182).
     */
    private static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365))
            .cachePrivate()
            .immutable();

    /** 세트를 모르는 기존 클라이언트(웹)는 세트 1을 받는다 - 생략이 곧 1이다 (KAN-182 하위 호환). */
    static final int DEFAULT_VOICE_SET = 1;

    private final TestDefinitionRegistry registry;

    public TestDefinitionController(TestDefinitionRegistry registry) {
        this.registry = registry;
    }

    /**
     * 테스트 정의(세트 하나)를 조회한다.
     * <p>
     * 세션 생성 응답의 {@code testVersion}을 그대로 넣어 문항 목록을 받는다. 인증이 필요 없다.
     * {@code voiceSet}은 세션 생성 응답의 값이고, 생략하면 세트 1이다 - 세트를 모르는 기존
     * 클라이언트는 변경 없이 세트 1을 받고, 그 응답은 {@code voiceSet=1}과 바이트 단위로 같다.
     * <p>
     * 버전 경로는 <b>불변</b>이다. 문항 내용이 바뀌면 같은 경로를 고치는 게 아니라 새 {@code testVersion}을
     * 발행한다. 그래서 응답에 {@code ETag}와 1년짜리 {@code Cache-Control: private, immutable}이 붙는다.
     * {@code If-None-Match}로 다시 물으면 304를 받는다. 세트마다 본문이 달라 ETag도 세트별이다.
     * <p>
     * 응답에는 정답({@code correctChoiceId})이 들어가지 않는다. 채점은 서버에서만 한다.
     * 문항 유형이 갖지 않는 필드는 아예 빠진다 - {@code VOICE}에는 {@code choices}가 없고,
     * {@code VOCABULARY}에는 {@code maxDurationMs}와 {@code guideF0}가 없다. 풀 전체를 한 응답에
     * 싣지 않는다 - 세트 하나의 응답 크기는 현행(약 90KB)과 같다.
     * <p>
     * 200 세트 정의 / 304 {@code If-None-Match}가 현재 ETag와 같음(본문 없음) /
     * 400 {@code voiceSet}이 정수가 아니거나 1 미만({@code VALIDATION_FAILED}) /
     * 404 그런 {@code testVersion}이 없거나 {@code voiceSet}이 세트 수를 넘음({@code RESOURCE_NOT_FOUND}).
     *
     * @param testVersion 세션 생성 응답의 {@code testVersion} (예: {@code gn-2026.08.1})
     * @param voiceSet    세션 생성 응답의 {@code voiceSet} (1부터). 생략 시 1.
     */
    @GetMapping("/{testVersion}")
    public @Nullable ResponseEntity<TestDefinitionResponse> get(
            @PathVariable String testVersion,
            @RequestParam(required = false) @Nullable Integer voiceSet,
            WebRequest request) {
        // 정수가 아닌 값은 프레임워크가 컨트롤러 진입 전에 400 VALIDATION_FAILED로 끊는다
        // (GlobalExceptionHandler의 MethodArgumentTypeMismatchException). 여기는 범위만 본다.
        int number = voiceSet != null ? voiceSet : DEFAULT_VOICE_SET;
        if (number < 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "voiceSet은 1 이상의 정수여야 합니다.");
        }
        // 버전 404가 세트 404보다 먼저다 - 둘 다 같은 코드라 순서가 응답을 가르지는 않는다.
        TestDefinitionRegistry.VoiceSet published = registry.get(testVersion).voiceSet(number);
        if (request.checkNotModified(published.etag())) {
            return null;    // Spring이 304 Not Modified + ETag 헤더를 만든다.
        }
        return ResponseEntity.ok()
                .eTag(published.etag())
                .cacheControl(IMMUTABLE)
                .body(published.response());
    }
}
