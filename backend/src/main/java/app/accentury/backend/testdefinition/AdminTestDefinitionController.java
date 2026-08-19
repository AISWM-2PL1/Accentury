package app.accentury.backend.testdefinition;

import app.accentury.backend.common.AdminAuth;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 테스트 콘텐츠 버전의 활성 전환과 목록 조회 (KAN-26, API 명세서 §6).
 * <p>
 * <b>발행 엔드포인트는 없다.</b> 발행 입력이 DB로 확정되면서(2026-08-09) 정의는 마이그레이션의
 * INSERT로 들어오고, 관리자 API에 남는 것은 "그중 무엇을 활성으로 쓸지"와 "무엇이 있는지"
 * 둘뿐이다. 명세서 §6 표의 {@code POST /admin/v0/test-definitions}는 그 결정으로 폐기됐다.
 * <p>
 * 운영자 전용이라 세션 토큰이 아닌 {@link AdminAuth}의 관리자 토큰을 쓴다 (§6의 규약).
 * {@code /internal/v0}는 BE에서 AI 서버로 나가는 기계 간 호출의 접두사라(§4) 방향이 반대다.
 * <p>
 * <b>토큰({@code accentury.admin.token})을 설정해야만 생긴다</b> - 미설정이 기본값이라 설정을
 * 빼먹어도 열려 있는 경로가 만들어지지 않는다. 미설정 상태에서 이 경로들은 다른 없는 경로와
 * 똑같은 404다.
 */
@RestController
@RequestMapping("/admin/v0")
@ConditionalOnProperty(prefix = "accentury.admin", name = "token")
class AdminTestDefinitionController {

    /**
     * 이력 응답의 최대 건수. 전환은 운영자가 이따금 하는 조작이라 이 정도면 프로토타입 기간
     * 전체를 덮는다. 페이지네이션 대신 상한을 두는 것은, 이력이 예상 밖으로 길어져도 응답
     * 크기가 한계 없이 늘지 않게 하려는 것이다.
     */
    private static final Limit HISTORY_LIMIT = Limit.of(50);

    private final AdminAuth adminAuth;
    private final ActiveVersionService activeVersions;
    private final StoredTestDefinitionRepository definitions;
    private final ActiveVersionAuditRepository audits;

    AdminTestDefinitionController(AdminAuth adminAuth, ActiveVersionService activeVersions,
                                  StoredTestDefinitionRepository definitions,
                                  ActiveVersionAuditRepository audits) {
        this.adminAuth = adminAuth;
        this.activeVersions = activeVersions;
        this.definitions = definitions;
        this.audits = audits;
    }

    /**
     * 활성 버전을 지정하거나 직전 버전으로 되돌린다 (§6 - 활성 버전 지정 / 이전 버전 롤백).
     * <p>
     * 200 전환 완료(이미 그 버전이 활성이면 {@code changed=false}로 아무것도 바꾸지 않는다) /
     * 400 요청 형식 오류({@code VALIDATION_FAILED}) / 401 토큰 누락이나 불일치
     * ({@code ADMIN_UNAUTHORIZED}) / 404 발행되지 않은 버전({@code RESOURCE_NOT_FOUND}) /
     * 409 되돌아갈 이전 버전 없음({@code ADMIN_ROLLBACK_UNAVAILABLE}) 또는 활성화할 수 없는
     * 방언({@code ADMIN_DIALECT_NOT_ALLOWED}).
     * <p>
     * 이 호출은 <b>진행 중 세션을 건드리지 않는다</b> - 세션은 생성 시점의 버전에 고정되고
     * (§5.4), 활성이 아니게 된 정의도 자기 버전 경로로 계속 조회된다 (AC).
     *
     * <b>인증이 첫 관문인 범위에는 경계가 있다.</b> 본문이 JSON으로 파싱되지 않거나
     * {@code Content-Type}이 다르면 프레임워크가 컨트롤러 진입 전에 400과 415로 끊으므로,
     * 토큰 없는 호출도 그 응답을 받는다. 집계 조회(KAN-106)는 일자를 {@code String}으로 받아
     * 이 구간을 없앨 수 있었지만, 본문을 받는 이 엔드포인트에서 같은 것을 하려면 원문을
     * 직접 파싱해야 한다. 그렇게까지 하지 않은 것은 새는 정보가 "여기 JSON 본문을 받는 경로가
     * 있다"뿐이고, 그건 401과 404의 차이로 이미 드러나기 때문이다. 토큰의 옳고 그름은 어느
     * 경로로도 새지 않는다. <b>의미 검증</b>({@link ActiveVersionRequest#validate})은 인증 뒤다.
     *
     * @param token {@code X-Admin-Token} 헤더 - 설정된 값과 같아야 한다.
     */
    @PutMapping("/active-version")
    ResponseEntity<ActiveVersionResponse> putActiveVersion(
            @RequestBody ActiveVersionRequest request,
            @RequestHeader(value = AdminAuth.TOKEN_HEADER, required = false) @Nullable String token) {
        // 인증이 첫 관문이다 - 형식 검증을 먼저 하면 미인증 호출자가 입력 검증 피드백을
        // 얻는다 (KAN-106의 일자 파싱과 같은 순서 규칙).
        adminAuth.authorize(token);
        request.validate();

        ActiveVersionResponse response = switch (request.action()) {
            case ACTIVATE -> activeVersions.activate(request.testVersion(), request.reason());
            case ROLLBACK -> activeVersions.rollback(request.reason());
        };
        return noStore().body(response);
    }

    /**
     * 발행된 버전 목록과 활성 전환 이력 (§6 - 버전 목록·발행 이력).
     * <p>
     * 롤백하기 전에 무엇이 있고 어디로 돌아가게 되는지 확인하는 용도다. 정의 본문은 담지 않는다 -
     * 문항을 보려면 공개 엔드포인트({@code GET /v0/tests/{testVersion}}, §3.2)를 쓴다.
     * <p>
     * 200 조회 성공 / 401 토큰 누락이나 불일치({@code ADMIN_UNAUTHORIZED}).
     */
    @GetMapping("/test-definitions")
    ResponseEntity<TestDefinitionListResponse> listDefinitions(
            @RequestHeader(value = AdminAuth.TOKEN_HEADER, required = false) @Nullable String token) {
        adminAuth.authorize(token);

        ActiveTestVersion current = activeVersions.current();
        List<TestDefinitionListResponse.Definition> published =
                definitions.findAllByOrderByPublishedAtAscTestVersionAsc().stream()
                        .map(stored -> new TestDefinitionListResponse.Definition(
                                stored.testVersion(), stored.dialect(), stored.scoreVersion(),
                                stored.publishedAt(), stored.testVersion().equals(current.testVersion())))
                        .toList();
        List<TestDefinitionListResponse.HistoryEntry> history =
                audits.findAllByOrderByRecordedAtDescIdDesc(HISTORY_LIMIT).stream()
                        .map(audit -> new TestDefinitionListResponse.HistoryEntry(
                                audit.action(), audit.previousVersion(), audit.newVersion(),
                                audit.reason(), audit.recordedAt()))
                        .toList();
        return noStore().body(new TestDefinitionListResponse(
                current.testVersion(), current.previousTestVersion(), published, history));
    }

    /**
     * 운영 상태를 담은 응답이라 중간 캐시에 남기지 않는다 - 캐시된 목록을 보고 롤백을 판단하면
     * 이미 바뀐 활성 버전 위에 조작이 얹힌다 (상태 응답 캐시 금지와 같은 방침, §3.4).
     */
    private static ResponseEntity.BodyBuilder noStore() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore());
    }
}
