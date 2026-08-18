package app.accentury.backend.upload;

import app.accentury.backend.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 잔존 임시파일 청소 잡의 명세 (KAN-27 AC - 30분 초과 삭제, 멱등, 잔존 모니터링).
 * <p>
 * 스윕 자체는 시각을 받는 정적 메서드라 파일을 만들어 두고 그대로 검증하고,
 * 게이지 결선은 실제 설정으로 조립된 빈으로 확인한다.
 */
class VoiceTempSweeperTest extends IntegrationTest {

    private static final Duration RETENTION = Duration.ofMinutes(30);

    @TempDir
    Path directory;

    @Autowired
    private VoiceTempSweeper sweeper;

    @Autowired
    private VoiceTempDirectory tempDirectory;

    @Autowired
    private MeterRegistry meterRegistry;

    // === 삭제 규칙 ===

    @Test
    void 보존_기간이_지난_파일만_지운다() throws IOException {
        Instant now = Instant.now();
        Path stale = file("stale", now.minus(Duration.ofMinutes(31)));
        Path fresh = file("fresh", now.minus(Duration.ofMinutes(29)));

        VoiceTempSweeper.SweepResult result = VoiceTempSweeper.sweep(directory, now, RETENTION);

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh));
        assertEquals(1, result.deleted());
        assertEquals(1, result.remaining());
    }

    @Test
    void 처리_중인_업로드의_파일은_지우지_않는다() throws IOException {
        // 판단 기준이 나이인 이유 - 요청은 초 단위로 끝나므로 방금 만들어진 파일은
        // 살아 있는 요청의 것이고, 그걸 앞질러 지우면 정상 분석이 깨진다.
        Instant now = Instant.now();
        Path inFlight = file("in-flight", now);

        VoiceTempSweeper.sweep(directory, now, RETENTION);

        assertTrue(Files.exists(inFlight));
    }

    @Test
    void 두_번_돌려도_결과가_같다() throws IOException {
        // 멱등 (KAN-27 AC) - 첫 스윕이 지운 파일을 두 번째가 다시 실패로 세지 않는다.
        Instant now = Instant.now();
        file("stale", now.minus(Duration.ofHours(2)));

        VoiceTempSweeper.SweepResult first = VoiceTempSweeper.sweep(directory, now, RETENTION);
        VoiceTempSweeper.SweepResult second = VoiceTempSweeper.sweep(directory, now, RETENTION);

        assertEquals(1, first.deleted());
        assertEquals(0, second.deleted());
        assertFalse(second.anyFailure());
    }

    @Test
    void 디렉터리를_읽지_못해도_예외를_던지지_않는다() {
        // 스케줄러 스레드에서 예외가 나가면 이후 주기가 통째로 끊긴다 - 실패로 세고 넘어간다.
        VoiceTempSweeper.SweepResult result = VoiceTempSweeper.sweep(
                directory.resolve("없는-디렉터리"), Instant.now(), RETENTION);

        assertEquals(1, result.scanFailures());
        assertEquals(0, result.deleted());
        // 훑지 못했으면 잔존 수를 모른다 - 0을 "깨끗하다"로 발행하면 안 된다.
        assertFalse(result.scanned());
    }

    @Test
    void 하위_디렉터리는_건드리지_않는다() throws IOException {
        // 컨테이너는 파일만 만든다 - 디렉터리를 지우려 들면 실패 카운터만 오염된다.
        Path nested = Files.createDirectory(directory.resolve("nested"));
        Files.setLastModifiedTime(nested, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        VoiceTempSweeper.SweepResult result = VoiceTempSweeper.sweep(
                directory, Instant.now(), RETENTION);

        assertTrue(Files.exists(nested));
        assertEquals(0, result.deleted());
        assertFalse(result.anyFailure());
        // 지우지는 않되 잔존으로는 센다 - 우리가 만들지 않은 것이 있다는 사실은 보여야 한다.
        assertEquals(1, result.remaining());
    }

    @Test
    void 끊어진_심볼릭_링크도_정리된다() throws IOException {
        // 링크를 따라가면 끊어진 링크가 NoSuchFileException으로 보여 "이미 지워졌다"로
        // 넘어가고, 정작 링크는 영영 남으면서 잔존 집계에도 빠진다 (Codex 리뷰).
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path dangling = Files.createSymbolicLink(directory.resolve("dangling"),
                directory.resolve("없는-대상"));

        VoiceTempSweeper.SweepResult result = VoiceTempSweeper.sweep(
                directory, Instant.now(), Duration.ZERO);

        assertFalse(Files.exists(dangling, LinkOption.NOFOLLOW_LINKS));
        assertEquals(1, result.deleted());
        assertEquals(0, result.remaining());
    }

    @Test
    void 지우지_못한_만료_파일은_잔존으로_센다() throws IOException {
        // 실패만 세고 넘기면 정리가 막힌 그 순간에 잔존 게이지가 0을 가리켜 알림(KAN-38)이
        // 거꾸로 조용해진다 (Codex sol 리뷰 P2).
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Instant now = Instant.now();
        Path stale = file("stale", now.minus(Duration.ofMinutes(40)));
        // 디렉터리에서 쓰기 권한을 빼면 목록은 읽히지만 삭제가 막힌다.
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
        try {
            VoiceTempSweeper.SweepResult result = VoiceTempSweeper.sweep(directory, now, RETENTION);

            // root로 돌리면 권한이 삭제를 막지 못한다 - 그때는 검증할 상황 자체가 아니다.
            assumeTrue(Files.exists(stale));
            assertEquals(1, result.deleteFailures());
            assertEquals(0, result.deleted());
            assertEquals(1, result.remaining());
            assertEquals(Duration.ofMinutes(40).toSeconds(), result.oldestRemainingSeconds());
        } finally {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void 최장_잔존_시간은_남은_파일_중_가장_오래된_값이다() throws IOException {
        Instant now = Instant.now();
        file("older", now.minus(Duration.ofMinutes(20)));
        file("newer", now.minus(Duration.ofMinutes(5)));

        VoiceTempSweeper.SweepResult result = VoiceTempSweeper.sweep(directory, now, RETENTION);

        assertEquals(2, result.remaining());
        assertEquals(Duration.ofMinutes(20).toSeconds(), result.oldestRemainingSeconds());
    }

    // === 기동 시 잔여물 정리 ===

    @Test
    void 기동_정리는_나이를_보지_않고_전부_지운다() throws IOException {
        // kill 직전에 만들어진 파일까지 재시작 즉시 없앤다 ("재시작 시 30분 내 제거" AC,
        // Codex sol 리뷰 P1) - 이 시점에는 살아 있는 업로드가 없어 앞질러 지울 위험이 없다.
        Path leftover = Files.createFile(
                tempDirectory.directory().resolve("leftover-" + UUID.randomUUID()));
        // 수정 시각이 미래인 파일(시각 되감기)도 남으면 안 된다 (Codex sol 리뷰 P2).
        Path future = Files.createFile(
                tempDirectory.directory().resolve("future-" + UUID.randomUUID()));
        Files.setLastModifiedTime(future, FileTime.from(Instant.now().plus(Duration.ofHours(1))));

        sweeper.purgeLeftovers();

        assertFalse(Files.exists(leftover));
        assertFalse(Files.exists(future));
        assertEquals(0, gauge("accentury.upload.temp.files"));
    }

    // === 모니터링 결선 (KAN-38 소비) ===

    @Test
    void 스윕이_잔존_파일_수와_최장_잔존_시간을_게이지로_노출한다() throws IOException {
        Path residual = Files.createFile(tempDirectory.directory().resolve("residual-" + UUID.randomUUID()));
        Files.setLastModifiedTime(residual,
                FileTime.from(Instant.now().minus(Duration.ofMinutes(10))));
        try {
            sweeper.sweep();

            assertTrue(gauge("accentury.upload.temp.files") >= 1);
            assertTrue(gauge("accentury.upload.temp.oldest.age") >= Duration.ofMinutes(10).toSeconds());
        } finally {
            Files.deleteIfExists(residual);
        }
    }

    @Test
    void 훑지_못하면_잔존_게이지를_0으로_덮어쓰지_않는다() throws IOException {
        // 0으로 덮어쓰면 정리가 막힌 바로 그 순간에 "깨끗하다"가 되어 알림(KAN-38)이
        // 거꾸로 조용해진다 - 삭제 실패 쪽에서 이미 피했던 함정이다 (Codex 리뷰).
        Path dir = tempDirectory.directory();
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path residual = Files.createFile(dir.resolve("residual-" + UUID.randomUUID()));
        Files.setLastModifiedTime(residual,
                FileTime.from(Instant.now().minus(Duration.ofMinutes(10))));
        try {
            sweeper.sweep();
            double before = gauge("accentury.upload.temp.files");
            assumeTrue(before >= 1);

            // 읽기 권한만 뺀다 - 실행 권한을 남겨야 디렉터리 존재 확인(자가 치유)은 통과하고
            // 목록 읽기만 막혀, 검증하려는 상황이 그대로 만들어진다.
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("--x------"));
            sweeper.sweep();

            assertEquals(before, gauge("accentury.upload.temp.files"));
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            Files.deleteIfExists(residual);
        }
    }

    @Test
    void 사라진_임시_디렉터리를_스윕이_되살린다() throws IOException {
        // Tomcat은 요청마다 이 디렉터리의 존재를 확인하고 없으면 파싱 자체를 실패시킨다 -
        // /tmp 정리 잡이 걷어가면 재시작할 때까지 모든 업로드가 500이다 (Codex 리뷰 P1).
        Path dir = tempDirectory.directory();
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                Files.deleteIfExists(entry);
            }
        }
        Files.deleteIfExists(dir);
        assumeTrue(!Files.isDirectory(dir));

        sweeper.sweep();

        assertTrue(Files.isDirectory(dir));
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            // 되살릴 때도 권한 불변식은 그대로다 - 느슨하게 다시 만들면 안 된다.
            assertEquals("rwx------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        }
    }

    @Test
    void 잔존이_없으면_게이지가_0으로_돌아온다() throws IOException {
        // 앞선 로컬 실행이 남긴 파일에 흔들리지 않게 비운 상태에서 본다.
        try (var entries = Files.list(tempDirectory.directory())) {
            for (Path entry : entries.toList()) {
                Files.deleteIfExists(entry);
            }
        }

        sweeper.sweep();

        assertEquals(0, gauge("accentury.upload.temp.files"));
        assertEquals(0, gauge("accentury.upload.temp.oldest.age"));
    }

    // === 헬퍼 ===

    private Path file(String name, Instant modified) throws IOException {
        Path file = Files.createFile(directory.resolve(name));
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }
}
