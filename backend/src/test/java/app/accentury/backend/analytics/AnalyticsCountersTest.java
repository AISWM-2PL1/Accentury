package app.accentury.backend.analytics;

import app.accentury.backend.PropertiesFixture;
import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.scoring.AggregateScore;
import app.accentury.backend.scoring.ScorePolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 집계 증가 진입점의 실행 가능한 명세 (KAN-106).
 * <p>
 * 여기서는 DB 없이 <b>순서와 실패 처리</b>만 본다 - 실제 증가의 원자성과 동시성은
 * {@link AnalyticsCountersIntegrationTest}가 진짜 DB로 검증한다.
 */
class AnalyticsCountersTest {

    private static final String TEST_VERSION = "gn-2026.08.1";

    // === 일자 경계 (2026-08-17 확정 - Asia/Seoul) ===

    @Test
    void 일자는_설정_타임존_기준으로_자른다() {
        RecordingStore store = new RecordingStore();
        // UTC로는 8월 16일 23:00이지만 KST로는 8월 17일 08:00이다.
        counters(store, "Asia/Seoul").recordSessionStarted(
                Instant.parse("2026-08-16T23:00:00Z"), TEST_VERSION, "sv-0.3", Traffic.REAL);

        assertEquals(Set.of("2026-08-17|" + TEST_VERSION + "|sv-0.3"), store.rows.keySet());
    }

    @Test
    void 타임존을_UTC로_두면_같은_시각이_전날로_간다() {
        RecordingStore store = new RecordingStore();
        counters(store, "UTC").recordSessionStarted(
                Instant.parse("2026-08-16T23:00:00Z"), TEST_VERSION, "sv-0.3", Traffic.REAL);

        assertEquals(Set.of("2026-08-16|" + TEST_VERSION + "|sv-0.3"), store.rows.keySet());
    }

    @Test
    void 버전이_다르면_다른_행에_쌓인다() {
        RecordingStore store = new RecordingStore();
        AnalyticsCounters counters = counters(store, "Asia/Seoul");
        Instant at = Instant.parse("2026-08-17T01:00:00Z");

        counters.recordSessionStarted(at, TEST_VERSION, "sv-0.3", Traffic.REAL);
        counters.recordSessionStarted(at, "gn-2026.09.1", "sv-0.3", Traffic.REAL);
        counters.recordSessionStarted(at, TEST_VERSION, "sv-0.4", Traffic.REAL);

        assertEquals(3, store.rows.size(), "버전 조합마다 별도 행이어야 통계가 섞이지 않는다");
    }

    @Test
    void 실사용자_식별자는_트래픽_축이_생기기_전과_같다() {
        // 배포 겹침과 롤백에서 옛 바이너리와 새 바이너리가 같은 업무 키에 같은 식별자를 만들어야
        // 한다 (Codex sol 리뷰 P2). 여기가 어긋나면 한쪽의 증가가 0행 UPDATE로 조용히 사라지고,
        // 경고 로그 말고는 흔적도 없다. 형식을 "정리"하고 싶어질 때 이 테스트가 먼저 깨진다.
        assertEquals("2026-08-17|" + TEST_VERSION + "|sv-0.3",
                DailyCounter.idOf(LocalDate.of(2026, 8, 17), TEST_VERSION, "sv-0.3", Traffic.REAL));
        // 합성만 접미사를 받는다 - 옛 바이너리는 이 행을 만들지 않으므로 겹칠 일이 없다.
        assertEquals("2026-08-17|" + TEST_VERSION + "|sv-0.3|SYNTHETIC",
                DailyCounter.idOf(LocalDate.of(2026, 8, 17), TEST_VERSION, "sv-0.3", Traffic.SYNTHETIC));
    }

    @Test
    void 트래픽_종류가_다르면_다른_행에_쌓인다() {
        // 검증용 스모크가 실사용자 통계를 흔들지 않기 위한 축이다 (KAN-138). 버전 축과 같은
        // 성격이라 같은 자리에서 갈린다 - 여기서 접히면 분리 자체가 성립하지 않는다.
        RecordingStore store = new RecordingStore();
        AnalyticsCounters counters = counters(store, "Asia/Seoul");
        Instant at = Instant.parse("2026-08-17T01:00:00Z");

        counters.recordSessionStarted(at, TEST_VERSION, "sv-0.3", Traffic.REAL);
        counters.recordSessionStarted(at, TEST_VERSION, "sv-0.3", Traffic.SYNTHETIC);

        assertEquals(Set.of("2026-08-17|" + TEST_VERSION + "|sv-0.3",
                        "2026-08-17|" + TEST_VERSION + "|sv-0.3|SYNTHETIC"),
                store.rows.keySet());
    }

    @Test
    void 완주는_넘겨받은_트래픽_종류를_그대로_따른다() {
        // 완주 시점에 다시 판정하면 응시와 완주가 다른 통에 들어가 완주율이 뜻을 잃는다.
        RecordingStore store = new RecordingStore();
        counters(store, "Asia/Seoul").recordSessionCompleted(
                Instant.parse("2026-08-17T01:00:00Z"), TEST_VERSION,
                score("HONORARY", 75, 60, 70), Traffic.SYNTHETIC);

        assertEquals(Set.of("2026-08-17|" + TEST_VERSION + "|sv-0.3|SYNTHETIC"), store.rows.keySet());
    }

    // === 증가 순서 - UPDATE 먼저, 없을 때만 INSERT ===

    @Test
    void 이미_있는_행은_INSERT_없이_증가만_한다() {
        RecordingStore store = new RecordingStore();
        AnalyticsCounters counters = counters(store, "Asia/Seoul");
        Instant at = Instant.parse("2026-08-17T01:00:00Z");

        counters.recordSessionStarted(at, TEST_VERSION, "sv-0.3", Traffic.REAL);
        store.calls.clear();
        counters.recordSessionStarted(at, TEST_VERSION, "sv-0.3", Traffic.REAL);

        assertEquals(1, store.calls.size(), "두 번째부터는 문장 하나로 끝나야 한다");
        assertTrue(store.calls.getFirst().startsWith("increment:"));
    }

    @Test
    void 첫_행_생성_경합에_지면_증가로_되돌아온다() {
        // INSERT 직전에 다른 요청이 같은 키의 행을 만든 상황
        RecordingStore store = new RecordingStore() {
            @Override
            public void insert(LocalDate statDate, String testVersion, String scoreVersion,
                           Traffic traffic, CounterDelta delta) {
                rows.put(DailyCounter.idOf(statDate, testVersion, scoreVersion, traffic),
                        CounterDelta.sessionStarted());
                calls.add("insert-conflict");
                throw new IllegalStateException("유니크 제약 위반");
            }
        };
        counters(store, "Asia/Seoul").recordSessionStarted(
                Instant.parse("2026-08-17T01:00:00Z"), TEST_VERSION, "sv-0.3", Traffic.REAL);

        assertEquals(List.of("increment:2026-08-17|" + TEST_VERSION + "|sv-0.3",
                        "insert-conflict",
                        "increment:2026-08-17|" + TEST_VERSION + "|sv-0.3"),
                store.calls,
                "진 쪽은 UPDATE로 되돌아와야 증가를 잃지 않는다");
    }

    // === 실패 격리 (AC - 증가 실패가 사용자 요청을 막지 않는다) ===

    @Test
    void 저장소가_모든_경로에서_실패해도_예외를_던지지_않는다() {
        AnalyticsCounters counters = counters(new FailingStore(), "Asia/Seoul");

        assertDoesNotThrow(() -> counters.recordSessionStarted(
                Instant.parse("2026-08-17T01:00:00Z"), TEST_VERSION, "sv-0.3", Traffic.REAL));
        assertDoesNotThrow(() -> counters.recordSessionCompleted(
                Instant.parse("2026-08-17T01:00:00Z"), TEST_VERSION, score("HONORARY", 75, 60, 70), Traffic.REAL));
    }

    @Test
    void 증가분_계산이_실패해도_예외를_던지지_않는다() {
        // 등급 백스톱과 키 구분자 검사는 정상 저장소에서도 던질 수 있다. 이 예외가 삼킴 경계
        // 밖(인자 계산 자리)에서 터지면 결과가 커밋된 뒤 /complete가 500이 된다 (Fable 리뷰 P2).
        AnalyticsCounters counters = counters(new RecordingStore(), "Asia/Seoul");
        Instant at = Instant.parse("2026-08-17T01:00:00Z");

        assertDoesNotThrow(() -> counters.recordSessionCompleted(
                at, TEST_VERSION, score("LEGEND", 90, 90, 90), Traffic.REAL), "모르는 등급");
        assertDoesNotThrow(() -> counters.recordSessionStarted(
                at, "gn-a|b", "sv-0.3", Traffic.REAL), "키 구분자가 들어간 버전");
    }

    // === 완주 증가분 (등급과 점수) ===

    @Test
    void 완주는_등급_한_칸과_세_점수를_함께_올린다() {
        RecordingStore store = new RecordingStore();
        counters(store, "Asia/Seoul").recordSessionCompleted(
                Instant.parse("2026-08-17T01:00:00Z"), TEST_VERSION, score("HONORARY", 75, 60, 70), Traffic.REAL);

        CounterDelta delta = store.rows.values().iterator().next();
        assertEquals(0, delta.sessionsStarted(), "완주는 시도를 다시 세지 않는다");
        assertEquals(1, delta.sessionsCompleted());
        assertEquals(1, delta.tierHonorary());
        assertEquals(0, delta.tierNative());
        assertEquals(75, delta.intonationSum());
        assertEquals(60, delta.vocabularySum());
        assertEquals(70, delta.overallSum());
        assertEquals(1, delta.scoredCount());
    }

    @Test
    void 세는_자리가_없는_등급은_거부한다() {
        // 등급 code는 클라이언트 계약이라 발행 검증이 앞에서 막지만(KAN-21),
        // 여기서 조용히 버리면 등급 분포 합과 완주 수가 어긋난 채로 남는다.
        assertThrows(IllegalArgumentException.class,
                () -> CounterDelta.completion(score("LEGEND", 90, 90, 90)));
    }

    // === 조립 ===

    private static AnalyticsCounters counters(CounterStore store, String zone) {
        AccenturyProperties properties = PropertiesFixture.withAnalytics(
                new AccenturyProperties.Analytics(ZoneId.of(zone), 366));
        return new AnalyticsCounters(store, properties);
    }

    private static AggregateScore score(String tierCode, int intonation, int vocabulary, int overall) {
        return new AggregateScore("sv-0.3", intonation, vocabulary, overall,
                new ScorePolicy.Tier(tierCode, "이름", 4, 60), 5);
    }

    /** 행의 존재 여부만 흉내내는 저장소 - 호출 순서를 그대로 기록한다. */
    private static class RecordingStore implements CounterStore {

        final List<String> calls = new ArrayList<>();
        final Map<String, CounterDelta> rows = new LinkedHashMap<>();

        @Override
        public boolean increment(String id, CounterDelta delta) {
            calls.add("increment:" + id);
            return rows.containsKey(id);
        }

        @Override
        public void insert(LocalDate statDate, String testVersion, String scoreVersion,
                           Traffic traffic, CounterDelta delta) {
            String id = DailyCounter.idOf(statDate, testVersion, scoreVersion, traffic);
            calls.add("insert:" + id);
            rows.put(id, delta);
        }
    }

    /** 어느 갈래로 가도 실패하는 저장소 - DB 장애를 흉내낸다. */
    private static final class FailingStore implements CounterStore {

        @Override
        public boolean increment(String id, CounterDelta delta) {
            throw new IllegalStateException("DB 장애");
        }

        @Override
        public void insert(LocalDate statDate, String testVersion, String scoreVersion,
                           Traffic traffic, CounterDelta delta) {
            throw new IllegalStateException("DB 장애");
        }
    }
}
