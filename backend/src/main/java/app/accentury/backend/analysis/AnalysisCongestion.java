package app.accentury.backend.analysis;

import app.accentury.backend.common.AccenturyProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * 폴링 혼잡 판정의 입력 - <b>전 인스턴스</b>의 진행 중(PROCESSING) 분석 작업 수 (KAN-167).
 * <p>
 * KAN-24는 이 값을 인메모리 카운터({@link AnalysisBacklog})로 셌다. backend가 Fargate 태스크
 * 여러 개로 돌면(KAN-165, KAN-168) 그 카운터는 자기 태스크가 받은 업로드만 세어 전체 밀림을
 * 과소 판정한다 - 태스크 3개가 각각 임계치 아래로 나눠 가지면 AI는 이미 임계치의 3배를
 * 처리 중인데 어느 태스크도 간격을 올리지 않는다. 그래서 DB의 PROCESSING 행 수로 바꿨다.
 * 어느 태스크가 접수했든 작업 행은 같은 PostgreSQL에 있고, 종결과 타임아웃 전이도 전부 DB
 * UPDATE라 이 수가 곧 AI에 걸려 있는 전체 압력이다.
 * <p>
 * <b>짧은 캐시를 둔다.</b> 혼잡 판정은 모든 상태 응답 경로에 놓이므로(§3.4, §3.6) 폴링마다
 * 세면 판정 자체가 폴링 증폭에 얹히는 부하가 된다. 캐시 TTL의 근거는
 * {@code accentury.analysis.congestion-cache-ttl}(기본 1초)에 적어 두었다 - 기준 폴링 간격이
 * 800ms라 1초면 인스턴스당 초당 count 1회로 묶이고, 판정 지연은 폴링 한 번 분량이다.
 * 밀림은 초 단위 추론이 수십 건 쌓여야 생기는 현상이라 그 안에 임계치를 넘나들지 않는다.
 * count 자체는 {@code status = 'PROCESSING'} 부분 인덱스(V4)를 타므로 진행 중 건수에만
 * 비례한다 - §5.3 규칙 6(가벼운 조회만)을 지킨다.
 * <p>
 * 캐시가 만료된 순간 여러 폴링이 겹쳐도 count는 한 번만 나간다 - 갱신을 직렬화하고 나머지는
 * 그 결과를 받는다. 갱신 중 대기는 count 1회 시간(ms 단위)이라 응답 지연으로 드러나지 않는다.
 */
@Component
public class AnalysisCongestion {

    private final LongSupplier processingCounter;
    private final Duration cacheTtl;
    private final Clock clock;

    private volatile @Nullable Snapshot snapshot;

    private record Snapshot(long processingJobs, Instant expiresAt) {
    }

    @Autowired
    public AnalysisCongestion(AnalysisJobRepository repository, AccenturyProperties properties) {
        this(repository::countProcessing, properties.analysis().congestionCacheTtl(), Clock.systemUTC());
    }

    /**
     * @param processingCounter 전 인스턴스의 PROCESSING 작업 수를 세는 조회
     * @param cacheTtl          그 결과를 다시 세지 않고 쓰는 시간. 0이면 매번 센다.
     */
    AnalysisCongestion(LongSupplier processingCounter, Duration cacheTtl, Clock clock) {
        if (cacheTtl.isNegative()) {
            // 음수는 "항상 만료"라 매 폴링이 count를 나가게 한다 - 0으로 적으려던 설정 실수가
            // 캐시를 조용히 끄는 쪽으로 새지 않게 기동 시점에 세운다.
            throw new IllegalStateException(
                    "accentury.analysis.congestion-cache-ttl은 0 이상이어야 한다: " + cacheTtl);
        }
        this.processingCounter = processingCounter;
        this.cacheTtl = cacheTtl;
        this.clock = clock;
    }

    /** 전 인스턴스의 진행 중 분석 작업 수 - 캐시 TTL 안에서는 마지막으로 센 값이다. */
    public long processingJobs() {
        Snapshot current = snapshot;
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.processingJobs();
        }
        return refresh(now);
    }

    private synchronized long refresh(Instant now) {
        // 잠금을 기다리는 동안 다른 스레드가 이미 갱신했으면 그 값을 쓴다 - 만료 순간에 겹친
        // 폴링 수만큼 count가 나가지 않게 하는 이중 검사다.
        Snapshot current = snapshot;
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.processingJobs();
        }
        long processingJobs = processingCounter.getAsLong();
        snapshot = new Snapshot(processingJobs, now.plus(cacheTtl));
        return processingJobs;
    }

    /**
     * 캐시를 비운다 - 테스트가 DB에 심은 작업을 다음 판정에 바로 반영시키는 용도다.
     * 운영 경로는 부르지 않는다. TTL이 판정 지연의 상한이라는 계약을 지키기 위해서다.
     */
    void invalidate() {
        snapshot = null;
    }
}
