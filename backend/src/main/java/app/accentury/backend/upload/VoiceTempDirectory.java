package app.accentury.backend.upload;

import app.accentury.backend.common.AccenturyProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

/**
 * 업로드 임시파일 전용 디렉터리와 그 불변식 (KAN-27, API 명세서 §3.3, §5.5).
 * <p>
 * 원본 음성은 <b>디스크에 닿지 않는 것이 정본</b>이다. {@code file-size-threshold}가
 * {@code max-request-size} 이상이면 컨테이너가 모든 파트를 메모리에서 처리하므로 임시파일
 * 자체가 만들어지지 않는다 - 이 설정 불변식을 기동 시점에 강제해(기동 실패) 나중의 설정
 * 변경이 조용히 원본 음성을 디스크로 흘리지 못하게 한다.
 * <p>
 * 그래도 전용 디렉터리를 두는 이유는 안전장치다. 불변식이 깨지거나 컨테이너가 바뀌어
 * 임시파일이 생기면, 공용 임시 디렉터리에 흩어지는 대신 여기 한 곳에 모여
 * {@link VoiceTempSweeper}가 청소할 수 있어야 한다. 디렉터리는 소유자 전용(700) 권한으로
 * 만들고, 파일명은 컨테이너가 JVM마다 새로 뽑는 UUID를 쓴다 - "예측 불가능한 이름 +
 * 전용 디렉터리 + 최소 권한"(KAN-27 Requirements)의 구현이다.
 * <p>
 * <b>디렉터리는 비어 있더라도 반드시 존재해야 한다</b> (Codex 리뷰 P1). Tomcat은 multipart
 * 요청마다 {@code location}을 {@code isDirectory()}로 확인하고, 없으면 스풀 여부와 무관하게
 * 파싱 자체를 실패시킨다 ({@code Request.parseParts()}). 재생성은 기본적으로 하지 않는다
 * ({@code createUploadTargets}의 기본값이 false). 그런데 기본 경로가 공용 임시 디렉터리
 * 아래라 {@code systemd-tmpfiles-clean} 같은 정리 잡이 - 우리 불변식 덕에 늘 비어 있고
 * 아무도 건드리지 않는 이 디렉터리를 - 오래됐다는 이유로 지운다. 그래서 살아 있는 서버가
 * 디렉터리를 잃고 모든 업로드가 500이 되는 경로가 있다. {@link #ensureExists()}가 스윕
 * 주기마다 이를 되돌린다. 운영에서는 애초에 정리 잡이 닿지 않는 곳에 두는 것이 정석이다 (KAN-36).
 * <p>
 * <b>요청 종료 즉시 정리</b>는 컨테이너가 맡는다. Tomcat은 요청 재활용 시점에
 * ({@code Request.recycle()}) 파싱된 모든 파트를 {@code delete()}한다 - 디스크 임시파일은
 * 지우고, 메모리 파트는 캐시 버퍼 참조를 놓는다. 파싱 도중 실패한 요청도 같은 정리를 타므로
 * 정상, 검증 실패, 크기 초과, 예외 어느 종료 경로든 요청 스코프를 넘겨 살아남는 파트는 없다.
 * <p>
 * 다만 파트 정리는 <b>참조를 놓을 뿐 버퍼를 덮어쓰지는 않는다</b> (Codex sol 리뷰 P1) -
 * 컨테이너 내부 버퍼라 애플리케이션이 손댈 수 없고, 리플렉션으로 비트는 것은 얻는 것보다
 * 잃는 것이 크다. 그래서 우리가 직접 만드는 사본만큼은 파기 시점을 못박는다:
 * 업로드 요청의 사본은 {@code VoiceUploadService}가, 분석으로 넘어간 사본은
 * {@code AnalysisRequest.wipeAudio()}가 종결 즉시 0으로 덮어쓴다. 남는 창은 컨테이너
 * 파서 버퍼가 수거될 때까지뿐이다.
 */
@Component
public class VoiceTempDirectory {

    private static final Logger log = LoggerFactory.getLogger(VoiceTempDirectory.class);

    /** 소유자 전용(rwx------) - 같은 호스트의 다른 계정이 임시파일을 읽지 못하게 한다 (NFR-SC-07) */
    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rwx------");

    private final Path directory;

    VoiceTempDirectory(AccenturyProperties properties, MultipartProperties multipart) {
        this.directory = prepare(verify(properties.upload().tempDir(), multipart));
        // 디렉터리 경로는 설정값이고 파일 단위 경로가 아니라 로그로 남긴다 - 운영자가
        // 잔존 파일 알림(KAN-38)을 받았을 때 어디를 볼지 알아야 한다. 개별 파일명은
        // 어디에도 남기지 않는다 (§2.6)
        log.info("업로드 임시 디렉터리 준비 완료 dir={}", directory);
    }

    public Path directory() {
        return directory;
    }

    /**
     * 설정 불변식을 검증하고 전용 디렉터리 경로를 돌려준다. 어긋나면 컨텍스트가 뜨지 않는다 -
     * 원본 음성이 디스크에 남는 설정으로 조용히 배포되는 것보다 기동 실패가 낫다.
     */
    static Path verify(@Nullable String configuredDir, MultipartProperties multipart) {
        if (configuredDir == null || configuredDir.isBlank()) {
            throw new IllegalStateException(
                    "accentury.upload.temp-dir가 비어 있다 - 업로드 임시파일 전용 디렉터리는 필수다 (KAN-27)");
        }
        Path directory = Path.of(configuredDir).toAbsolutePath().normalize();

        // 메모리 전용 불변식 - 파트 하나가 threshold를 넘는 순간 컨테이너가 디스크로 흘린다.
        // max-request-size가 상한이므로 threshold가 그 이상이면 넘길 방법이 없다
        DataSize threshold = multipart.getFileSizeThreshold();
        DataSize maxRequest = multipart.getMaxRequestSize();
        // 무제한 표기를 먼저 끊는다 (Codex 리뷰 P1) - 서블릿 규약의 -1은 "상한 없음"인데
        // 부등식으로는 2MB보다 작은 값이라 아래 검사를 그대로 통과한다. 즉 max-request-size를
        // -1로 두면 검사가 통과하면서 실제로는 어떤 크기의 파트든 디스크로 흘러간다.
        // max-file-size도 같은 이유로 본다 - 파트 하나의 상한이 사라지면 요청 상한만 남는다
        requireBounded("file-size-threshold", threshold);
        requireBounded("max-request-size", maxRequest);
        requireBounded("max-file-size", multipart.getMaxFileSize());
        if (threshold.toBytes() < maxRequest.toBytes()) {
            throw new IllegalStateException("spring.servlet.multipart.file-size-threshold("
                    + threshold + ")가 max-request-size(" + maxRequest + ")보다 작다 - "
                    + "원본 음성이 디스크 임시파일로 흘러간다 (KAN-27, §5.5)");
        }

        // 불변식이 깨졌을 때를 대비한 안전장치라, 임시파일이 생긴다면 반드시 청소 잡이
        // 보는 디렉터리여야 한다. 공용 임시 디렉터리로 흩어지면 스윕도 권한 제한도 없다
        String location = multipart.getLocation();
        if (location == null || location.isBlank()
                || !Path.of(location).toAbsolutePath().normalize().equals(directory)) {
            throw new IllegalStateException("spring.servlet.multipart.location(" + location
                    + ")이 accentury.upload.temp-dir(" + directory + ")와 다르다 - "
                    + "임시파일이 청소 잡이 보지 않는 곳에 생긴다 (KAN-27)");
        }
        return directory;
    }

    /** {@code -1}(무제한)이나 0을 거부한다 - 부등식 검사만으로는 걸러지지 않는 값들이다 */
    private static void requireBounded(String name, @Nullable DataSize size) {
        if (size == null || size.toBytes() <= 0) {
            throw new IllegalStateException("spring.servlet.multipart." + name + "(" + size
                    + ")에 상한이 없다 - 원본 음성이 디스크 임시파일로 흘러간다 (KAN-27, §5.5)");
        }
    }

    /**
     * 디렉터리가 사라졌으면 다시 만든다 - {@link VoiceTempSweeper}가 스윕 주기마다 부른다.
     * <p>
     * Tomcat이 요청마다 존재를 확인하므로(클래스 주석), 이 디렉터리가 없는 동안에는 업로드가
     * 전부 500이다. 여기서 되돌리지 않으면 프로세스를 재시작할 때까지 복구되지 않는다.
     *
     * @return 다시 만들었으면 true - 그 사이의 업로드는 이미 실패한 뒤다
     */
    public boolean ensureExists() {
        // 링크로 바꿔치기된 경우도 "정상 아님"이라 prepare()의 검사를 다시 태운다
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        prepare(directory);
        log.warn("업로드 임시 디렉터리가 사라져 다시 만들었다 - 그동안의 업로드는 실패했다 dir={}",
                directory);
        return true;
    }

    /** 전용 디렉터리를 만들고 소유자 전용 권한을 강제한다 - 이미 있으면 정체를 확인한 뒤 권한만 다시 맞춘다 */
    static Path prepare(Path directory) {
        try {
            Path parent = directory.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            boolean posix = supportsPosix(directory);
            try {
                // 먼저 만들어 보고, 이미 있을 때만 정체를 확인한다 - "확인 후 생성"은 그
                // 사이에 링크가 끼어드는 창을 남긴다. 권한은 생성과 원자적으로 준다 -
                // 만들고 나서 chmod하면 그 찰나에 umask가 열어 둔 디렉터리가 노출된다
                if (posix) {
                    Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
                } else {
                    Files.createDirectory(directory);
                }
                // 우리가 방금 원자적으로 만들었다 - 더 확인할 것이 없다
                return directory;
            } catch (FileAlreadyExistsException e) {
                requireOwnedDirectory(directory);
            }
            if (posix) {
                // 기존 디렉터리의 느슨한 권한(앞선 배포, 수동 조작)을 다시 조인다
                Files.setPosixFilePermissions(directory, OWNER_ONLY);
                // chmod는 링크를 따라가므로 위 검사와 이 호출 사이가 창이다 (Codex 리뷰).
                // NIO에는 원자적 무링크 열기가 없어 창 자체를 없앨 수는 없지만, 남의
                // 디렉터리에 권한을 걸어 놓고 그대로 지나가는 것은 막는다 - 바뀌었으면
                // 여기서 기동이 끊기고 뒤따르는 기동 정리(purgeLeftovers)가 돌지 않는다
                requireOwnedDirectory(directory);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "업로드 임시 디렉터리를 준비하지 못했다: " + directory, e);
        }
        return directory;
    }

    /**
     * 이미 있는 경로가 <b>우리 소유의 진짜 디렉터리</b>인지 확인한다 (Codex sol 리뷰 P1).
     * <p>
     * 기본 위치가 공용 임시 디렉터리 아래의 예측 가능한 경로라, 같은 호스트의 다른 계정이
     * 먼저 심볼릭 링크로 선점할 수 있다. 링크를 그대로 타면 권한 강제와 기동 정리
     * ({@code VoiceTempSweeper.purgeLeftovers()})가 링크가 가리키는 남의 디렉터리에 가해진다.
     * <p>
     * 방어선은 <b>마지막 구성 요소</b>까지다 (2026-08-15 확정, Codex sol 리뷰 P2 기각) - 부모
     * 경로까지 한 단계씩 검사해도 NIO에는 원자적 무링크 열기(openat2 RESOLVE_NO_SYMLINKS)가
     * 없어 TOCTOU가 남고, 부모가 공격자 쓰기 가능한 위치라면 그 설정 자체가 문제다.
     * 운영 전제는 전용 임시 디렉터리를 서비스 계정만 쓸 수 있는 곳(컨테이너 내부 파일시스템)에
     * 두는 것이다 (KAN-36).
     */
    private static void requireOwnedDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(directory,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IllegalStateException("업로드 임시 디렉터리 경로가 디렉터리가 아니다"
                    + "(심볼릭 링크 선점 가능성): " + directory);
        }
        if (!supportsPosix(directory)) {
            return;
        }
        UserPrincipal owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
        UserPrincipal self = currentOwner(directory);
        // 우리를 식별하지 못하면 소유자 검사만 건너뛴다 - 위의 링크 검사와 아래 권한 강제는
        // 그대로 간다. 확인 불가를 기동 실패로 바꾸면 안전장치가 곧 장애 원인이 된다
        if (self != null && !owner.equals(self)) {
            throw new IllegalStateException("업로드 임시 디렉터리의 소유자가 현재 프로세스가 아니다"
                    + " - owner=" + owner.getName());
        }
    }

    /**
     * 현재 프로세스의 소유자 - 부모 디렉터리에 탐침 파일을 만들어 그 소유자를 읽는다.
     * <p>
     * {@code user.name}과 이름을 맞대는 방식은 컨테이너에서 깨진다 (Codex 리뷰) -
     * {@code runAsUser: 1000670000}처럼 {@code /etc/passwd}에 없는 UID로 뜨면 디렉터리
     * 소유자는 {@code "1000670000"}, {@code user.name}은 {@code "?"}가 되어, 우리 소유인
     * 디렉터리를 남의 것으로 판정하고 기동을 거부한다. {@link UserPrincipal#equals}는 이름이
     * 아니라 UID로 비교하고, 양쪽 다 같은 조회 경로를 타므로 UID만 같으면 일치한다.
     * <p>
     * 탐침을 부모에 만드는 이유는, 검사 대상이 아직 우리 것이라고 확정되지 않았기 때문이다 -
     * 남의 디렉터리일 수 있는 곳에 파일을 쓰지 않는다. 부모는 이미 우리가 만들어 둔 뒤다.
     */
    private static @Nullable UserPrincipal currentOwner(Path directory) {
        Path parent = directory.getParent();
        if (parent == null) {
            return null;
        }
        Path probe = null;
        try {
            probe = Files.createTempFile(parent, "accentury-uid-", ".probe");
            return Files.getOwner(probe);
        } catch (IOException e) {
            return null;
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException e) {
                    // 탐침 정리 실패로 기동을 막지 않는다 - 빈 파일 하나이고, 전용
                    // 디렉터리 바깥이라 스윕 대상도 아니다
                    log.warn("소유자 확인용 탐침 파일을 지우지 못했다 reason={}",
                            e.getClass().getSimpleName());
                }
            }
        }
    }

    private static boolean supportsPosix(Path directory) {
        return directory.getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
