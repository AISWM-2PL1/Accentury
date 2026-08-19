package app.accentury.backend.testdefinition;

import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * 발행된 정의 저장소 - <b>읽기만 연다</b> (KAN-26).
 * <p>
 * 발행 경로가 마이그레이션의 INSERT뿐이므로(2026-08-09 확정, §6) 애플리케이션에는 쓰기가
 * 없다. {@code JpaRepository}를 상속하지 않는 것은 그쪽이 딸려 오는 {@code save()}와
 * {@code delete()}가 "코드로도 발행하거나 폐기할 수 있다"는 잘못된 문을 열기 때문이다 -
 * 발행본은 불변이라는 §5.4의 전제가 저장소 API에서부터 지켜져야 한다
 * ({@code DailyCounterRepository}와 같은 방침).
 */
public interface StoredTestDefinitionRepository extends Repository<StoredTestDefinition, String> {

    /**
     * 발행된 정의 전부 - 기동 시 {@link TestDefinitionRegistry}가 한 번 읽어 메모리에 올린다.
     * <p>
     * 발행이 마이그레이션으로만 일어나므로 기동 후 이 목록은 늘지 않는다. 페이지네이션이
     * 없는 것도 그래서다 - 프로토타입의 발행본은 열 손가락으로 셀 수 있는 규모다.
     * 정렬은 발행 시각 오름차순이라 목록 조회(§6)의 순서가 흔들리지 않는다.
     */
    List<StoredTestDefinition> findAllByOrderByPublishedAtAscTestVersionAsc();
}
