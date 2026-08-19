package app.accentury.backend.testdefinition;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /admin/v0/test-definitions} 응답 - 버전 목록과 발행 이력 (KAN-26, 명세서 §6).
 * <p>
 * 롤백하기 전에 무엇이 있고 어디로 돌아가게 되는지 눈으로 확인하는 용도다. 정의 본문은 담지
 * 않는다 - 한 건이 13KB라 목록에 실으면 응답이 백 KB대가 되고, 문항을 보고 싶으면 공개
 * 엔드포인트({@code GET /v0/tests/{testVersion}}, §3.2)가 이미 있다.
 *
 * @param activeVersion   지금 활성인 버전
 * @param previousVersion 롤백 목적지. null이면 롤백 요청이 409다.
 * @param definitions     발행된 정의 전부 (발행 시각 오름차순)
 * @param history         활성 전환 이력 (최신순)
 */
public record TestDefinitionListResponse(String activeVersion,
                                         @Nullable String previousVersion,
                                         List<Definition> definitions,
                                         List<HistoryEntry> history) {

    /**
     * @param active 이 버전이 지금 활성인지 - 목록과 활성 필드를 눈으로 대조하지 않아도 되게 한다.
     */
    public record Definition(String testVersion, String dialect, String scoreVersion,
                             Instant publishedAt, boolean active) {
    }

    public record HistoryEntry(ActiveVersionAudit.Action action,
                               @Nullable String previousVersion,
                               String newVersion,
                               @Nullable String reason,
                               Instant recordedAt) {
    }
}
