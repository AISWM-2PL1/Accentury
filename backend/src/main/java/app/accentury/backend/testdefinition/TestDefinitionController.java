package app.accentury.backend.testdefinition;

import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.time.Duration;

/**
 * {@code GET /v0/tests/{testVersion}} - 불변 테스트 정의 조회 (KAN-10, API 명세서 §3.2).
 * <p>
 * 인증 불필요 엔드포인트다 (§2.1). 세션은 생성 응답의 {@code testVersion}으로 이 경로를
 * 조회하고, 버전 경로가 불변이라 몇 번을 조회해도 같은 정의를 받는다 (KAN-10 AC).
 */
@RestController
@RequestMapping("/v0/tests")
public class TestDefinitionController {

    /**
     * 버전 경로는 불변이므로 장기 캐싱을 허용한다 (§3.2 - Cache-Control: immutable, ETag).
     * 내용 교체는 언제나 새 testVersion 발행이다 (KAN-26 버전 불변 원칙).
     * 공유 캐시는 쓰지 않는다 - CDN 미도입 확정으로 public이 아니라 private다
     * (2026-08-09, KAN-101 종결). 버전 경로가 불변이라 브라우저 캐시는 그대로 유효하다.
     */
    private static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365))
            .cachePrivate()
            .immutable();

    private final TestDefinitionRegistry registry;

    public TestDefinitionController(TestDefinitionRegistry registry) {
        this.registry = registry;
    }

    /**
     * 테스트 정의를 조회한다.
     * <p>
     * 세션 생성 응답의 {@code testVersion}을 그대로 넣어 문항 목록을 받는다. 인증이 필요 없다.
     * <p>
     * 버전 경로는 <b>불변</b>이다. 문항 내용이 바뀌면 같은 경로를 고치는 게 아니라 새 {@code testVersion}을
     * 발행한다. 그래서 응답에 {@code ETag}와 1년짜리 {@code Cache-Control: private, immutable}이 붙는다.
     * {@code If-None-Match}로 다시 물으면 304를 받는다.
     * <p>
     * 응답에는 정답({@code correctChoiceId})이 들어가지 않는다. 채점은 서버에서만 한다.
     * 문항 유형이 갖지 않는 필드는 아예 빠진다 - {@code VOICE}에는 {@code choices}가 없고,
     * {@code VOCABULARY}에는 {@code maxDurationMs}와 {@code guideF0}가 없다.
     * <p>
     * 200 테스트 정의 / 304 {@code If-None-Match}가 현재 ETag와 같음(본문 없음) /
     * 404 그런 {@code testVersion}이 없음({@code RESOURCE_NOT_FOUND}).
     *
     * @param testVersion 세션 생성 응답의 {@code testVersion} (예: {@code gn-2026.08.1})
     */
    @GetMapping("/{testVersion}")
    public @Nullable ResponseEntity<TestDefinitionResponse> get(
            @PathVariable String testVersion,
            WebRequest request) {
        TestDefinitionRegistry.PublishedDefinition published = registry.get(testVersion);
        if (request.checkNotModified(published.etag())) {
            return null;    // Spring이 304 Not Modified + ETag 헤더를 만든다.
        }
        return ResponseEntity.ok()
                .eTag(published.etag())
                .cacheControl(IMMUTABLE)
                .body(published.response());
    }
}
