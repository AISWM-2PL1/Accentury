package app.accentury.backend.upload;

import app.accentury.backend.common.AccenturyProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 업로드 임시 디렉터리의 잔존 파일 청소 잡 (KAN-27 - 프로세스 비정상 종료 대비 안전장치).
 * <p>
 * 정상 경로에서는 임시파일이 아예 생기지 않고({@link VoiceTempDirectory}의 메모리 전용
 * 불변식), 생기더라도 컨테이너가 요청 종료 시점에 지운다. 이 잡이 지우는 것은 그 두 겹이
 * 모두 통하지 않은 잔여물이다 - 요청 처리 중 프로세스가 kill 되면 파트 정리 코드가 아예
 * 실행되지 못한다. 그래서 판단 기준을 "요청이 끝났는가"가 아니라 <b>파일 나이</b>로 둔다:
 * 수정 시각이 {@code accentury.upload.temp-retention}(30분)보다 오래된 파일만 지우므로,
 * 살아 있는 요청의 파일을 앞질러 지울 위험이 없다 (업로드 1건은 초 단위로 끝난다).
 * <p>
 * 삭제는 멱등하다 - 이미 사라진 파일은 정상 경로로 넘어가고, 두 번 돌려도 같은 결과다.
 * <p>
 * 잔존 파일 수와 최장 잔존 시간은 게이지로 노출한다 (KAN-27 AC, KAN-38이 대시보드와
 * 알림으로 소비). 값은 마지막 스윕 시점 기준이다 - 스크레이프마다 디렉터리를 훑지 않는다.
 * 미터 등록까지가 이 티켓의 범위다 - actuator 엔드포인트 노출과 exporter 결선은 Ops
 * 범위라 KAN-38이 맡는다 (2026-08-15 확정, Codex sol 리뷰 P2 기각 근거).
 * <p>
 * 스윕은 청소에 더해 <b>디렉터리 자체를 되살리는</b> 일도 한다 - 없어지면 업로드가 전부
 * 실패하기 때문이다 ({@link VoiceTempDirectory#ensureExists()}의 설명).
 * <p>
 * <b>로그에 경로와 파일명을 남기지 않는다</b> (NFR-SC-07, KAN-27 AC). 건수와 바이트 수만
 * 남기고, 실패 사유도 예외 메시지(파일 경로가 들어간다) 대신 예외 종류만 적는다.
 */
@Component
public class VoiceTempSweeper {

    private static final Logger log = LoggerFactory.getLogger(VoiceTempSweeper.class);

    private final VoiceTempDirectory tempDirectory;
    private final Duration retention;
    private final AtomicInteger residualFiles = new AtomicInteger();
    private final AtomicLong oldestResidualSeconds = new AtomicLong();
    private final Counter deleteFailures;
    private final Counter scanFailures;
    private volatile boolean shuttingDown;

    VoiceTempSweeper(VoiceTempDirectory tempDirectory, AccenturyProperties properties,
                     MeterRegistry meterRegistry) {
        this.tempDirectory = tempDirectory;
        this.retention = properties.upload().tempRetention();
        Gauge.builder("accentury.upload.temp.files", residualFiles, AtomicInteger::doubleValue)
                .description("마지막 스윕 기준 업로드 임시 디렉터리의 잔존 파일 수")
                .register(meterRegistry);
        Gauge.builder("accentury.upload.temp.oldest.age", oldestResidualSeconds,
                        AtomicLong::doubleValue)
                .description("마지막 스윕 기준 잔존 파일의 최장 잔존 시간")
                .baseUnit("seconds")
                .register(meterRegistry);
        // 삭제 실패와 훑기 실패를 가른다 (Codex 리뷰) - 이름이 delete.failures인 카운터에
        // 디렉터리 목록 오류까지 섞으면, 알림을 받은 운영자가 삭제 권한을 들여다보는 동안
        // 실제 원인(마운트, 권한, 소실)은 그대로 남는다. 잔존 게이지가 멎는 것도 이쪽이다.
        this.deleteFailures = Counter.builder("accentury.upload.temp.delete.failures")
                .description("임시파일 삭제 실패 누적 - 임계치 알림 대상 (KAN-38)")
                .register(meterRegistry);
        this.scanFailures = Counter.builder("accentury.upload.temp.scan.failures")
                .description("임시 디렉터리 훑기 실패 누적 - 잔존 게이지가 멎었다는 신호 (KAN-38)")
                .register(meterRegistry);
    }

    /**
     * 기동 시 1회 - 나이와 무관하게 전부 지운다 (Codex sol 리뷰 P1).
     * <p>
     * 이 시점에 디렉터리에 있는 파일은 앞선 프로세스가 kill 되며 남긴 잔여물뿐이다 -
     * 웹 서버가 아직 요청을 받기 전이라(빈 초기화는 컨텍스트 리프레시 중이다) 살아 있는
     * 업로드가 없다. 나이 기준을 그대로 적용하면 kill 직전에 만들어진 파일이 30분을 더
     * 살아남으므로, 재시작 즉시 없앤다 ("재시작 시 30분 내 제거" AC).
     * <p>
     * 같은 호스트에서 BE 두 인스턴스가 <b>같은 임시 디렉터리</b>를 공유하면 한쪽의 기동이
     * 다른 쪽의 처리 중 파일을 지울 수 있다. 프로토타입은 단일 인스턴스이고(§2.1 - 다중
     * 인스턴스는 Redis 전환과 함께 검토), 애초에 메모리 전용 불변식 아래에서는 파일이
     * 생기지 않는다. 다중 인스턴스로 갈 때는 인스턴스별 디렉터리로 나눈다.
     */
    @PostConstruct
    void purgeLeftovers() {
        // 나이를 아예 보지 않는다 - 보존 기간 0으로 두면 시각이 뒤로 돌아갔거나(NTP 되감기)
        // 수정 시각이 미래인 파일이 살아남는다 (Codex sol 리뷰 P2).
        SweepResult result = sweepBefore(tempDirectory.directory(), Instant.now(), Instant.MAX);
        publish(result);
        if (result.deleted() > 0 || result.anyFailure()) {
            log.warn("기동 시 남아 있던 업로드 임시파일 정리 - 삭제 {}건({}바이트), 삭제 실패 {}건, 훑기 실패 {}건",
                    result.deleted(), result.deletedBytes(), result.deleteFailures(),
                    result.scanFailures());
        }
    }

    /**
     * 주기 스윕 - 실행 중 생겼다가 정리되지 못한 파일을 나이 기준으로 지운다.
     * 기동 시 잔여물은 {@link #purgeLeftovers()}가 이미 걷어낸 뒤다.
     * <p>
     * 청소 전에 디렉터리부터 되살린다 - 없어진 채로 두면 청소할 대상이 없는 것이 아니라
     * 업로드가 전부 실패하는 상태다 ({@link VoiceTempDirectory#ensureExists()}).
     */
    @Scheduled(initialDelay = 5, fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void sweep() {
        if (shuttingDown) {
            // 종료 중에는 디렉터리를 되살리거나 파일을 훑지 않는다 (KAN-166). 스케줄러 풀은
            // 컨텍스트 close 이벤트에서 조기 종료되므로 보통은 여기까지 오지 않지만, 이미
            // 시작된 실행이나 테스트의 직접 호출까지 같은 규칙으로 막는다.
            log.info("종료 중이라 업로드 임시파일 스윕을 건너뛴다");
            return;
        }
        heal();
        SweepResult result = sweep(tempDirectory.directory(), Instant.now(), retention);
        publish(result);
        if (result.deleted() > 0 || result.anyFailure()) {
            // 잔여물이 있었다는 것 자체가 비정상 종료의 신호다 - warn으로 남긴다.
            log.warn("업로드 임시파일 정리 - 삭제 {}건({}바이트), 삭제 실패 {}건, 훑기 실패 {}건, "
                            + "잔존 {}건, 최장 잔존 {}초",
                    result.deleted(), result.deletedBytes(), result.deleteFailures(),
                    result.scanFailures(), result.remaining(), result.oldestRemainingSeconds());
        }
    }

    /**
     * 종료가 시작되면 스윕을 멈춘다 (KAN-166). 컨텍스트 close 이벤트는 웹 서버 정지와 워커
     * 배수보다 먼저 오므로, 이 뒤에 도는 스윕은 없다 - 종료 중에 임시 디렉터리를 새로
     * 만들면 다음 기동의 잔여물 정리가 볼 것만 늘어난다.
     */
    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        shuttingDown = true;
    }

    /** 복구 실패로 청소까지 멈추지는 않는다 - 다음 주기에 다시 시도한다. */
    private void heal() {
        try {
            tempDirectory.ensureExists();
        } catch (RuntimeException e) {
            log.warn("업로드 임시 디렉터리 복구 실패 reason={}", e.getClass().getSimpleName());
        }
    }

    private void publish(SweepResult result) {
        // 훑기에 실패했으면 잔존 수를 모른다 - 0으로 덮어써 "깨끗하다"고 보고하면 정리가
        // 막힌 바로 그 순간에 알림이 조용해진다. 직전 값을 그대로 두고, 값이 멎었다는
        // 사실은 scan.failures가 알린다 (Codex 리뷰 - 삭제 실패 쪽과 같은 이유다).
        if (result.scanned()) {
            residualFiles.set(result.remaining());
            oldestResidualSeconds.set(result.oldestRemainingSeconds());
        }
        deleteFailures.increment(result.deleteFailures());
        scanFailures.increment(result.scanFailures());
    }

    /**
     * 디렉터리 1회 스윕 - 보존 기간이 지난 일반 파일만 지우고 결과를 집계한다.
     * 스케줄과 미터에서 분리해 두어 시각을 넣고 그대로 검증할 수 있다.
     */
    static SweepResult sweep(Path directory, Instant now, Duration retention) {
        return sweepBefore(directory, now, now.minus(retention));
    }

    /** {@code cutoff}보다 오래된 파일을 지운다 - 기동 정리는 {@link Instant#MAX}로 전부 지운다. */
    private static SweepResult sweepBefore(Path directory, Instant now, Instant cutoff) {
        int deleted = 0;
        long deletedBytes = 0;
        int deleteFailures = 0;
        int scanFailures = 0;
        int remaining = 0;
        long oldestRemainingSeconds = 0;
        boolean scanned = true;

        try (Stream<Path> entries = Files.list(directory)) {
            for (Iterator<Path> it = entries.iterator(); it.hasNext(); ) {
                Path entry = it.next();
                BasicFileAttributes attributes;
                try {
                    // 링크를 따라가지 않는다 (Codex 리뷰) - 따라가면 끊어진 링크가
                    // NoSuchFileException으로 보여 "이미 지워졌다"로 넘어가고, 정작 링크는
                    // 영영 남으면서 잔존 집계에도 빠져 디렉터리가 깨끗하다고 보고된다.
                    // 링크가 살아 있을 때 크기를 대상 파일에서 읽는 문제도 같이 없앤다.
                    attributes = Files.readAttributes(entry, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException e) {
                    // 컨테이너나 앞선 스윕이 먼저 지웠다 - 멱등의 정상 경로다.
                    continue;
                } catch (IOException e) {
                    scanFailures++;
                    log.warn("임시파일 정보를 읽지 못했다 reason={}", e.getClass().getSimpleName());
                    continue;
                }
                // 디렉터리는 우리가 만드는 것이 아니라 지우지 않는다 - 다만 잔존으로 세어
                // 사람 눈에 띄게 한다. 그 외(일반 파일, 심볼릭 링크 등)는 모두 정리 대상이다.
                if (attributes.isDirectory()) {
                    remaining++;
                    continue;
                }
                Instant modified = attributes.lastModifiedTime().toInstant();
                if (modified.isBefore(cutoff)) {
                    try {
                        if (Files.deleteIfExists(entry)) {
                            deleted++;
                            deletedBytes += attributes.size();
                        }
                        continue;
                    } catch (IOException e) {
                        deleteFailures++;
                        log.warn("임시파일 삭제 실패 reason={}", e.getClass().getSimpleName());
                        // 지우지 못한 파일은 그대로 남아 있다 - 아래로 흘려 잔존으로 센다.
                        // 실패만 세고 넘기면 정리가 막힌 그 순간에 잔존 게이지가 0을 가리켜
                        // 알림(KAN-38)이 거꾸로 조용해진다 (Codex sol 리뷰 P2).
                    }
                }
                remaining++;
                oldestRemainingSeconds = Math.max(oldestRemainingSeconds,
                        Duration.between(modified, now).toSeconds());
            }
        } catch (IOException | UncheckedIOException e) {
            // 디렉터리가 통째로 사라졌거나 읽을 수 없다 - 다음 주기에 다시 본다.
            // UncheckedIOException을 같이 잡는 이유는 Files.list가 지연 평가이기 때문이다
            // (Codex 리뷰) - 순회 도중의 오류는 IOException이 아니라 이쪽으로 나오고,
            // 놓치면 @PostConstruct인 purgeLeftovers에서 새어 컨텍스트 기동이 실패한다.
            // scanned=false로 두어 잔존 집계를 폐기한다 - 도중에 끊긴 수는 진실이 아니다.
            scanned = false;
            scanFailures++;
            log.warn("업로드 임시 디렉터리를 읽지 못했다 reason={}", e.getClass().getSimpleName());
        }
        return new SweepResult(deleted, deletedBytes, deleteFailures, scanFailures,
                remaining, oldestRemainingSeconds, scanned);
    }

    /**
     * 스윕 1회의 집계 - 로그와 게이지의 유일한 입력이다.
     * 경로나 파일명은 여기 담기지 않으므로 그대로 로그에 써도 안전하다 (NFR-SC-07).
     *
     * @param deleteFailures         지우려다 실패한 건수 - 파일은 그대로 남아 잔존에도 포함된다.
     * @param scanFailures           디렉터리나 파일 정보를 읽지 못한 건수
     * @param remaining              보존 기간이 아직 안 지나 남겨 둔 파일 수
     * @param oldestRemainingSeconds 남겨 둔 파일 중 최장 잔존 시간 (없으면 0)
     * @param scanned                디렉터리를 끝까지 훑었는가 - false면 잔존 값은 의미가 없다.
     */
    record SweepResult(int deleted, long deletedBytes, int deleteFailures, int scanFailures,
                       int remaining, long oldestRemainingSeconds, boolean scanned) {

        boolean anyFailure() {
            return deleteFailures > 0 || scanFailures > 0;
        }
    }
}
