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
     */
    private static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365))
            .cachePublic()
            .immutable();

    private final TestDefinitionRegistry registry;

    public TestDefinitionController(TestDefinitionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/{testVersion}")
    public @Nullable ResponseEntity<TestDefinitionResponse> get(@PathVariable String testVersion,
                                                                WebRequest request) {
        TestDefinitionRegistry.PublishedDefinition published = registry.get(testVersion);
        if (request.checkNotModified(published.etag())) {
            return null;    // Spring이 304 Not Modified + ETag 헤더를 만든다
        }
        return ResponseEntity.ok()
                .eTag(published.etag())
                .cacheControl(IMMUTABLE)
                .body(published.response());
    }
}
