package app.accentury.backend.analytics;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.scoring.AggregateScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * 익명 집계 카운터의 유일한 증가 진입점 (KAN-106, SRS FR-AN-10).
 * <p>
 * <b>이 클래스의 메서드는 절대 예외를 던지지 않는다.</b> 통계가 세션 생성이나 결과 반환을
 * 막으면 안 된다는 것이 티켓의 제약이라, 어떤 실패도 여기서 로그로 흡수된다. 그래서
 * 호출부는 {@code try/catch} 없이 한 줄로 부른다 - 호출부마다 다르게 삼키다가 한 곳이
 * 새는 것을 막으려고 삼키는 자리를 여기 하나로 모았다.
 * <p>
 * <b>호출은 사용자 트랜잭션이 커밋된 뒤여야 한다</b> (2026-08-17 확정). 롤백된 완료를
 * 세지 않고, 세션 행 잠금을 쥔 채로 커넥션을 하나 더 잡지도 않는다. 대신 커밋 직후
 * 프로세스가 죽으면 그 1건은 유실된다 - 카운터는 근사 통계이지 회계 장부가 아니라는
 * 전제를 받아들인 것이다 (KAN-20 리포트, KAN-21 편향 추적 모두 비율을 본다).
 */
@Service
public class AnalyticsCounters {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsCounters.class);

    private final CounterStore store;
    private final ZoneId zone;

    // 이웃 서비스와 달리 생성자가 패키지 전용이다. - CounterStore가 패키지 전용 타입이라
    // public으로 열어도 밖에서는 부를 수 없다.
    AnalyticsCounters(CounterStore store, AccenturyProperties properties) {
        this.store = store;
        this.zone = properties.analytics().zone();
    }

    /**
     * 응시 시도 1건 (KAN-9 - {@code POST /v0/sessions} 성공 직후).
     * 재응시도 새 시도로 센다. - 이전 세션이 즉시 폐기돼도(KAN-107) 되돌리지 않는다.
     *
     * @param at 세션이 생긴 시각 - 일자 경계는 설정 타임존 기준이다.
     */
    public void recordSessionStarted(Instant at, String testVersion, String scoreVersion) {
        record(at, testVersion, scoreVersion, CounterDelta::sessionStarted);
    }

    /**
     * 완주 1건과 그 등급, 점수 (KAN-16 - {@code /complete}가 결과를 확정한 뒤).
     * 완료 재시도는 결과를 다시 만들지 않으므로 여기도 오지 않는다 (§3.6 멱등).
     *
     * @param at    결과가 확정된 시각 - 결과 행의 {@code createdAt}과 같은 값이라 일자가 어긋나지 않는다
     * @param score 집계 결과 (KAN-21) - 등급과 세 점수를 그대로 더한다
     */
    public void recordSessionCompleted(Instant at, String testVersion, AggregateScore score) {
        record(at, testVersion, score.scoreVersion(), () -> CounterDelta.completion(score));
    }

    /**
     * UPDATE → (없으면) INSERT → (경합에 지면) UPDATE 한 번 더.
     * 마지막 UPDATE까지 실패하는 경우는 첫 INSERT 실패가 경합이 아니라 진짜 오류였을 때뿐이라,
     * 그때는 원인 예외를 함께 남긴다.
     * <p>
     * <b>증가분 계산과 식별자 유도까지 try 안이다.</b> 인자 자리에서 계산하면 그 예외가 삼킴
     * 경계 밖에서 터져 사용자 요청을 죽인다 - {@link CounterDelta#completion}의 등급 백스톱과
     * {@link DailyCounter#idOf}의 구분자 검사가 정확히 그런 예외다. 백스톱이 사용자를 죽이면
     * 백스톱이 아니다 (Fable 리뷰 P2).
     */
    private void record(Instant at, String testVersion, String scoreVersion,
                        Supplier<CounterDelta> deltaSupplier) {
        // 커밋 후 호출 계약(클래스 javadoc)이 지켜지는지 값싸게 감시한다. 깨지면 REQUIRES_NEW가
        // 두 번째 커넥션을 잡아 풀 고갈로 가고, 롤백될 요청까지 세게 된다 (Fable 리뷰 P3).
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("집계 카운터가 열린 트랜잭션 안에서 호출됐다 - 호출부를 커밋 뒤로 옮겨야 한다");
        }
        try {
            CounterDelta delta = deltaSupplier.get();
            LocalDate date = LocalDate.ofInstant(at, zone);
            String id = DailyCounter.idOf(date, testVersion, scoreVersion);
            if (store.increment(id, delta)) {
                return;
            }
            try {
                store.insert(date, testVersion, scoreVersion, delta);
                return;
            } catch (RuntimeException firstWriterWon) {
                // 같은 키를 다른 요청이 먼저 만들었다 - 하루에 한 번 있는 정상 경합이다.
                if (store.increment(id, delta)) {
                    log.debug("집계 행 동시 생성 - 증가로 되돌아왔다 id={}", id);
                    return;
                }
                throw firstWriterWon;
            }
        } catch (RuntimeException e) {
            // 사용자 요청은 이미 성공했다 (티켓 제약) - 여기서 끝내고 통계 1건만 버린다.
            // 식별자 유도 자체가 실패했을 수 있으므로 키 조각을 그대로 남긴다.
            log.warn("익명 집계 카운터 증가 실패 - 이 1건은 통계에 빠진다 at={} testVersion={} scoreVersion={}",
                    at, testVersion, scoreVersion, e);
        }
    }
}
