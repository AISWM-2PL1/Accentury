package app.accentury.backend.upload;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 업로드 임시파일 전용 디렉터리의 기동 불변식 (KAN-27).
 * <p>
 * 여기서 지키는 것은 "원본 음성이 디스크에 닿지 않는다"(§3.3, §5.5)가 설정 한 줄로 조용히
 * 뒤집히지 않는다는 것이다 - 어긋난 설정은 배포가 아니라 기동에서 끊는다.
 */
class VoiceTempDirectoryTest {

    @TempDir
    Path base;

    @Test
    void 임계값이_요청_상한보다_작으면_기동이_실패한다() {
        // threshold를 넘는 파트는 컨테이너가 디스크로 흘린다 - 이 설정이면 원본 음성이 파일이 된다
        Path directory = base.resolve("voice-tmp");
        MultipartProperties multipart = multipart(directory, DataSize.ofMegabytes(1),
                DataSize.ofMegabytes(2));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> VoiceTempDirectory.verify(directory.toString(), multipart));
        assertTrue(e.getMessage().contains("file-size-threshold"));
    }

    @Test
    void multipart_위치가_전용_디렉터리와_다르면_기동이_실패한다() {
        // 청소 잡이 보지 않는 곳에 임시파일이 생기면 안전장치가 통째로 무력해진다
        Path directory = base.resolve("voice-tmp");
        MultipartProperties multipart = multipart(base.resolve("elsewhere"),
                DataSize.ofMegabytes(2), DataSize.ofMegabytes(2));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> VoiceTempDirectory.verify(directory.toString(), multipart));
        assertTrue(e.getMessage().contains("location"));
    }

    @Test
    void 요청_상한이_무제한이면_기동이_실패한다() {
        // 서블릿 규약의 -1은 "상한 없음"인데 부등식으로는 2MB보다 작은 값이라 검사를 그대로
        // 통과한다 - 검사가 통과하면서 실제로는 어떤 크기의 파트든 디스크로 흘러간다 (Codex 리뷰)
        Path directory = base.resolve("voice-tmp");
        MultipartProperties multipart = multipart(directory, DataSize.ofMegabytes(2),
                DataSize.ofBytes(-1));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> VoiceTempDirectory.verify(directory.toString(), multipart));
        assertTrue(e.getMessage().contains("max-request-size"));
    }

    @Test
    void 파트_상한이_무제한이면_기동이_실패한다() {
        // 파트 하나의 상한이 사라지면 요청 상한만 남는다 - 부등식은 통과하지만 방어가 얇아진다
        Path directory = base.resolve("voice-tmp");
        MultipartProperties multipart = multipart(directory, DataSize.ofMegabytes(2),
                DataSize.ofMegabytes(2));
        multipart.setMaxFileSize(DataSize.ofBytes(-1));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> VoiceTempDirectory.verify(directory.toString(), multipart));
        assertTrue(e.getMessage().contains("max-file-size"));
    }

    @Test
    void 전용_디렉터리_설정이_비면_기동이_실패한다() {
        MultipartProperties multipart = multipart(base, DataSize.ofMegabytes(2),
                DataSize.ofMegabytes(2));

        assertThrows(IllegalStateException.class, () -> VoiceTempDirectory.verify("  ", multipart));
    }

    @Test
    void 같은_경로면_표기가_달라도_통과한다() {
        // location과 temp-dir은 같은 설정값에서 오지만(${accentury.upload.temp-dir}),
        // java.io.tmpdir의 뒤쪽 슬래시 때문에 문자열이 갈릴 수 있다 - 정규화해서 비교한다
        Path directory = base.resolve("voice-tmp");
        MultipartProperties multipart = multipart(DataSize.ofMegabytes(2), DataSize.ofMegabytes(2));
        multipart.setLocation(base + "//./voice-tmp");

        assertEquals(directory.toAbsolutePath().normalize(),
                VoiceTempDirectory.verify(directory.toString(), multipart));
    }

    @Test
    void 디렉터리는_소유자_전용_권한으로_만들어진다() throws Exception {
        assumePosix();
        Path directory = base.resolve("voice-tmp");

        VoiceTempDirectory.prepare(directory);

        assertTrue(Files.isDirectory(directory));
        assertEquals("rwx------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
    }

    @Test
    void 경로가_심볼릭_링크면_기동이_실패한다() throws Exception {
        // 공용 임시 디렉터리 아래의 예측 가능한 경로라 다른 계정이 먼저 링크로 선점할 수 있다.
        // 링크를 타면 권한 강제와 기동 정리가 남의 디렉터리에 가해진다 (Codex sol 리뷰 P1)
        assumePosix();
        Path target = Files.createDirectory(base.resolve("남의-디렉터리"));
        Path directory = Files.createSymbolicLink(base.resolve("voice-tmp"), target);
        Path victim = Files.createFile(target.resolve("남의-파일"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> VoiceTempDirectory.prepare(directory));

        assertTrue(e.getMessage().contains("디렉터리가 아니다"));
        assertTrue(Files.exists(victim));
    }

    @Test
    void 이미_있는_디렉터리의_느슨한_권한은_다시_조인다() throws Exception {
        // 앞선 배포나 수동 조작으로 열려 있던 디렉터리를 그대로 쓰면 같은 호스트의 다른
        // 계정이 잔존 임시파일을 읽을 수 있다 (NFR-SC-07)
        assumePosix();
        Path directory = Files.createDirectory(base.resolve("voice-tmp"));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));

        VoiceTempDirectory.prepare(directory);

        assertEquals("rwx------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
    }

    /** 권한 강제는 POSIX 파일 시스템 전제다 - 그 외(Windows)에서는 검증할 대상이 없다 */
    private void assumePosix() {
        Assumptions.assumeTrue(base.getFileSystem().supportedFileAttributeViews().contains("posix"));
    }

    private static MultipartProperties multipart(Path location, DataSize threshold,
                                                 DataSize maxRequest) {
        MultipartProperties properties = multipart(threshold, maxRequest);
        properties.setLocation(location.toString());
        return properties;
    }

    private static MultipartProperties multipart(DataSize threshold, DataSize maxRequest) {
        MultipartProperties properties = new MultipartProperties();
        properties.setFileSizeThreshold(threshold);
        properties.setMaxRequestSize(maxRequest);
        return properties;
    }
}
