package app.accentury.backend.testdefinition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 발행된 테스트 정의 한 건의 저장 행 (KAN-26, 명세서 §6).
 * <p>
 * <b>발행 후 불변이다</b> (§5.4). 문항이나 곡선이 바뀌면 이 행을 UPDATE하는 것이 아니라 새
 * {@code testVersion}으로 INSERT한다 - 이미 발행된 버전에 고정된 세션(§5.4)이 응시 도중
 * 다른 문항을 받으면 안 되기 때문이다. 그래서 이 엔티티에는 변경 메서드가 없다.
 * <p>
 * 본문({@link #body})은 정의 JSON 통째다 - {@link TestDefinition}과 1:1로 파싱된다.
 * 문항과 선택지를 별도 테이블로 정규화하지 않은 이유는 마이그레이션 V2의 주석에 적어 두었다.
 * {@link #dialect}와 {@link #scoreVersion}은 본문 안에도 있는 값을 꺼내 둔 사본이라, 관리자
 * 목록 조회(§6)가 본문을 파싱하지 않고 답할 수 있다. 사본과 본문의 일치는 기동 시
 * {@link TestDefinitionRegistry}가 강제한다 - 어긋나면 서버가 뜨지 않는다.
 * <p>
 * 행이 있다는 것과 활성이라는 것은 다르다. 활성은 {@link ActiveTestVersion} 한 행이 가리키는
 * 하나뿐이고, 나머지는 발행 상태로 남아 자기 버전 경로(§3.2)로 계속 조회된다.
 */
@Entity
@Table(name = "test_definition")
public class StoredTestDefinition {

    /** 정의 버전 (예: gn-2026.08.1) - 그 자체가 식별자다. 중복 발행은 이 PK가 막는다 (§6). */
    @Id
    @Column(name = "test_version", length = 40)
    private String testVersion;

    /** 대상 방언 - MVP는 GYEONGNAM만 발행된다 (KAN-8 범위 제외, §6). 본문 값의 사본이다. */
    @Column(nullable = false, length = 20)
    private String dialect;

    /** 이 정의를 채점할 점수 버전 (KAN-21). 본문 값의 사본이다. */
    @Column(name = "score_version", nullable = false, length = 20)
    private String scoreVersion;

    /**
     * 정의 JSON 원본. 발행 시점의 바이트 그대로이고 서버가 이 컬럼을 덮어쓰지 않는다.
     * <p>
     * <b>응답이 이 본문을 그대로 흘려보내는 것은 아니다.</b> 기동 시 파싱해서 문항을 seq
     * 오름차순으로 정렬하고 정답을 뺀 뒤 다시 직렬화한 것이 클라이언트가 받는 값이다
     * (§3.2, KAN-10 AC - 순서 고정과 정답 미노출). 불변인 것은 발행본이지 응답 바이트가 아니다.
     * <p>
     * {@code text}인 것은 guideF0 배열 때문에 길이 상한을 걸 자리가 없어서다 - 문항당 실수
     * 90~120개 x 3벌(values, bandLow, bandHigh) x 5문항이라 현재 발행본이 이미 13KB다.
     */
    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** 발행 시각 - 관리자 목록 조회(§6)의 정렬 기준이다. */
    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    protected StoredTestDefinition() {
        // JPA 전용
    }

    /**
     * 픽스처 전용 생성자다.
     * <p>
     * 발행 경로는 마이그레이션의 INSERT뿐이므로(2026-08-09 확정, §6) 애플리케이션 코드에는
     * 이 행을 만드는 자리가 없다 - {@code public}으로 열면 "코드로도 발행할 수 있다"는
     * 잘못된 신호가 된다. 패키지 범위로 두어 발행 검증(기동 시 전 행 검증)의 단위 테스트가
     * 망가진 행을 조립할 수 있게만 한다.
     */
    StoredTestDefinition(String testVersion, String dialect, String scoreVersion,
                         String body, Instant publishedAt) {
        this.testVersion = testVersion;
        this.dialect = dialect;
        this.scoreVersion = scoreVersion;
        this.body = body;
        this.publishedAt = publishedAt;
    }

    public String testVersion() {
        return testVersion;
    }

    public String dialect() {
        return dialect;
    }

    public String scoreVersion() {
        return scoreVersion;
    }

    public String body() {
        return body;
    }

    public Instant publishedAt() {
        return publishedAt;
    }
}
