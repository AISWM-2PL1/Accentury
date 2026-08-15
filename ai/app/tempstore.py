"""입력 오디오의 임시 저장과 파기 (KAN-27).

추론은 파형 파일을 읽는 라이브러리(librosa, torchaudio 등)를 쓰게 되므로, 받은 오디오가
디스크를 한 번 거치는 것을 전제로 한다 - BE처럼 "파일을 아예 만들지 않는" 전략을 쓸 수
없다. 대신 세 겹으로 막는다:

1. **전용 디렉터리** - 소유자 전용(700)으로 기동 시 만들고, 프로세스가 만드는 모든
   임시파일을 여기로 몬다 (``tempfile.tempdir``까지 이 디렉터리로 돌린다 - 웹 프레임워크가
   업로드를 스풀할 때도 공용 임시 디렉터리로 새지 않는다).
2. **요청 종료 즉시 삭제** - :meth:`VoiceTempStore.temp_file`이 컨텍스트 매니저라
   성공, 판정 실패, 예외, 클라이언트 취소(``CancelledError``) 어느 쪽으로 빠져나가도
   ``finally``에서 지운다.
3. **청소 잡** - 프로세스가 kill 되면 2번이 실행조차 못 되므로, 보존 기간(30분)이 지난
   파일을 주기적으로 지운다. 삭제는 멱등하다.

로그에는 경로와 파일명을 남기지 않는다 - 건수와 바이트 수, correlation ID만이다 (NFR-SC-07).

메모리 쪽은 BE(JVM)와 사정이 다르다. 파이썬의 ``bytes``는 불변이고 할당자가 재사용할
때까지 내용을 덮어쓸 방법이 없어서, BE의 ``AnalysisRequest.wipeAudio()``에 대응하는 수단이
없다 (Codex sol 리뷰 P1). 그래서 줄일 수 있는 것을 줄인다 - 업로드를 통째로 들지 않고
조각내어 임시파일로 흘려보내, 원본 음성이 한 번에 메모리에 올라오는 일이 없게 한다.
"""

from __future__ import annotations

import logging
import os
import stat
import tempfile
import time
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger(__name__)

#: 임시파일 접두사 - 뒤에 tempfile이 붙이는 예측 불가능한 무작위 문자열이 온다.
_PREFIX = "audio-"
_SUFFIX = ".wav"


@dataclass(frozen=True)
class SweepResult:
    """스윕 1회의 집계 - 로그와 메트릭의 유일한 입력이다 (경로는 담기지 않는다).

    삭제 실패와 훑기 실패를 가른다 - 이름이 삭제 실패인 값에 디렉터리 목록 오류까지
    섞으면, 알림을 받은 운영자가 엉뚱한 곳을 본다. 잔존 집계가 멎는 것도 이쪽이다.

    ``scanned``가 거짓이면 디렉터리를 끝까지 훑지 못한 것이라 ``remaining``은 의미가 없다.
    """

    deleted: int = 0
    deleted_bytes: int = 0
    delete_failures: int = 0
    scan_failures: int = 0
    remaining: int = 0
    oldest_remaining_seconds: float = 0.0
    scanned: bool = True

    @property
    def any_failure(self) -> bool:
        return self.delete_failures > 0 or self.scan_failures > 0


class VoiceTempStore:
    """전용 임시 디렉터리 하나를 소유한다."""

    def __init__(self, directory: Path, retention_seconds: float) -> None:
        self._directory = directory
        self._retention_seconds = retention_seconds
        self._delete_failures = 0
        self._scan_failures = 0
        self._sweeps = 0
        # 마지막으로 끝까지 훑은 스윕의 값이다 - 훑기에 실패한 스윕은 반영하지 않는다
        self._residual_files = 0
        self._oldest_remaining_seconds = 0.0

    @property
    def directory(self) -> Path:
        return self._directory

    def prepare(self) -> None:
        """디렉터리를 만들고 소유자 전용 권한을 강제한다 - 이미 있으면 정체를 확인한 뒤 권한만 다시 조인다."""
        self._directory.parent.mkdir(parents=True, exist_ok=True)
        try:
            # 먼저 만들어 보고 이미 있을 때만 확인한다 - "확인 후 생성"은 그 사이에 링크가
            # 끼어드는 창을 남긴다
            self._directory.mkdir(mode=0o700)
        except FileExistsError:
            self._require_owned_directory()
        # umask나 앞선 배포가 남긴 느슨한 권한을 그대로 두면 같은 호스트의 다른 계정이
        # 잔존 임시파일을 읽을 수 있다 (NFR-SC-07)
        os.chmod(self._directory, 0o700)

    def ensure_exists(self) -> bool:
        """디렉터리가 사라졌으면 다시 만든다 - 청소 잡이 주기마다 부른다 (Codex 리뷰).

        ``tempfile.tempdir``가 이 디렉터리를 가리키므로, 없어지면 ``mkstemp``가 실패해
        분석 요청이 전부 죽는다. 그런데 기본 위치가 공용 임시 디렉터리 아래라
        ``systemd-tmpfiles-clean`` 같은 정리 잡이 - 우리 쪽 사정으로는 늘 비어 있고
        아무도 건드리지 않는 이 디렉터리를 - 오래됐다는 이유로 지운다. 프로세스를
        재시작할 때까지 복구되지 않는 상태라 스윕이 매번 되돌린다.

        :return: 다시 만들었으면 참 - 그 사이의 요청은 이미 실패한 뒤다
        """
        if self._directory.is_dir() and not self._directory.is_symlink():
            return False
        self.prepare()
        log.warning("임시 디렉터리가 사라져 다시 만들었다 - 그동안의 분석은 실패했다")
        return True

    def _require_owned_directory(self) -> None:
        """이미 있는 경로가 우리 소유의 진짜 디렉터리인지 확인한다 (Codex sol 리뷰 P1).

        기본 위치가 공용 임시 디렉터리 아래의 예측 가능한 경로라 다른 계정이 심볼릭 링크로
        선점할 수 있다. 링크를 타면 권한 강제와 기동 정리가 남의 디렉터리에 가해진다.

        방어선은 마지막 구성 요소까지다 (Codex sol 리뷰 P2 기각) - 부모 경로를 한 단계씩
        검사해도 TOCTOU가 남고, 부모가 공격자 쓰기 가능한 위치라면 그 설정 자체가 문제다.
        운영 전제는 전용 디렉터리를 서비스 계정만 쓸 수 있는 곳(컨테이너 내부)에 두는 것이다.
        """
        info = self._directory.lstat()
        if not stat.S_ISDIR(info.st_mode):
            raise RuntimeError(
                f"임시 디렉터리 경로가 디렉터리가 아니다(심볼릭 링크 선점 가능성): {self._directory}"
            )
        if hasattr(os, "getuid") and info.st_uid != os.getuid():
            raise RuntimeError(
                f"임시 디렉터리의 소유자가 현재 사용자가 아니다: uid={info.st_uid}"
            )

    @contextmanager
    def temp_file(self) -> Iterator[Path]:
        """전용 디렉터리에 빈 임시파일을 만들고, 블록을 벗어나면 반드시 지운다.

        ``mkstemp``라 파일명은 예측 불가능하고 권한은 0600이다 (KAN-27 Requirements).
        내용을 채우는 것은 호출부다 - 업로드를 조각내어 흘려 넣을 수 있어야 하기 때문이다
        (전체를 메모리에 들지 않는다, Codex sol 리뷰 P1).
        """
        handle, name = tempfile.mkstemp(dir=self._directory, prefix=_PREFIX, suffix=_SUFFIX)
        os.close(handle)
        path = Path(name)
        try:
            yield path
        finally:
            self.discard(path)

    def discard(self, path: Path) -> None:
        """멱등 삭제 - 이미 없으면 성공으로 친다 (KAN-27 AC)."""
        try:
            path.unlink(missing_ok=True)
        except OSError as error:
            self._delete_failures += 1
            # 경로 원문 대신 예외 종류만 남긴다 - 예외 메시지에는 파일 경로가 들어간다
            log.warning("임시파일 삭제 실패 reason=%s", type(error).__name__)

    def purge_leftovers(self) -> SweepResult:
        """기동 시 1회 - 나이와 무관하게 전부 지운다 (Codex sol 리뷰 P1).

        프로세스가 막 뜬 시점에 이 디렉터리에 있는 파일은 앞선 프로세스가 kill 되며 남긴
        잔여물뿐이다 (살아 있는 요청이 아직 없다). 나이 기준을 그대로 적용하면 kill 직전에
        만들어진 파일이 30분을 더 살아남으므로, 재시작 즉시 없앤다.

        보존 기간 0이 아니라 무한대 기준으로 지운다 - 0으로 두면 시각이 뒤로 돌아갔거나
        (NTP 되감기) 수정 시각이 미래인 파일이 살아남는다 (Codex sol 리뷰 P2).
        """
        return self._sweep(time.time(), float("inf"))

    def sweep(self, now: float | None = None) -> SweepResult:
        """보존 기간이 지난 일반 파일만 지운다.

        기준을 "요청이 끝났는가"가 아니라 파일 나이로 두는 이유는 kill 복구 때문이다 -
        살아 있는 요청의 파일은 30분을 채우지 못하므로 앞질러 지울 위험이 없다.
        """
        moment = time.time() if now is None else now
        return self._sweep(moment, moment - self._retention_seconds)

    def _sweep(self, moment: float, cutoff: float) -> SweepResult:
        deleted = deleted_bytes = delete_failures = scan_failures = remaining = 0
        oldest_remaining = 0.0
        scanned = True

        try:
            entries = list(self._directory.iterdir())
        except OSError as error:
            log.warning("임시 디렉터리를 읽지 못했다 reason=%s", type(error).__name__)
            entries = []
            scan_failures += 1
            # 훑지 못했으면 잔존 수를 모른다 - 0으로 보고하면 정리가 막힌 바로 그 순간에
            # "깨끗하다"가 되어 알림이 거꾸로 조용해진다 (Codex 리뷰)
            scanned = False

        for entry in entries:
            try:
                # lstat이라 링크를 따라가지 않는다 - 따라가면 끊어진 링크가
                # FileNotFoundError로 보여 "이미 지워졌다"로 넘어가고, 링크는 영영 남는다
                info = entry.lstat()
            except FileNotFoundError:
                # 요청 쪽이 먼저 지웠다 - 멱등의 정상 경로다
                continue
            except OSError as error:
                scan_failures += 1
                log.warning("임시파일 정보를 읽지 못했다 reason=%s", type(error).__name__)
                continue

            # 디렉터리는 우리가 만드는 것이 아니라 지우지 않는다 - 다만 잔존으로 세어
            # 사람 눈에 띄게 한다. 그 외(일반 파일, 심볼릭 링크 등)는 모두 정리 대상이다
            if stat.S_ISDIR(info.st_mode):
                remaining += 1
                continue
            expired = info.st_mtime < cutoff
            if expired:
                try:
                    entry.unlink()
                except FileNotFoundError:
                    continue
                except OSError as error:
                    delete_failures += 1
                    log.warning("임시파일 삭제 실패 reason=%s", type(error).__name__)
                    # 지우지 못한 파일은 그대로 남아 있다 - 아래로 흘려 잔존으로 센다.
                    # 실패만 세고 넘기면 정리가 막힌 그 순간에 tempFiles가 0을 가리킨다
                    # (Codex sol 리뷰 P2)
                else:
                    deleted += 1
                    deleted_bytes += info.st_size
                    continue

            remaining += 1
            oldest_remaining = max(oldest_remaining, moment - info.st_mtime)

        result = SweepResult(
            deleted=deleted,
            deleted_bytes=deleted_bytes,
            delete_failures=delete_failures,
            scan_failures=scan_failures,
            remaining=remaining,
            oldest_remaining_seconds=oldest_remaining,
            scanned=scanned,
        )
        self._sweeps += 1
        self._delete_failures += delete_failures
        self._scan_failures += scan_failures
        if scanned:
            self._residual_files = remaining
            self._oldest_remaining_seconds = oldest_remaining
        if deleted or result.any_failure:
            # 잔여물이 있었다는 것 자체가 비정상 종료의 신호다
            log.warning(
                "임시파일 정리 - 삭제 %d건(%d바이트), 삭제 실패 %d건, 훑기 실패 %d건, "
                "잔존 %d건, 최장 잔존 %.0f초",
                deleted,
                deleted_bytes,
                delete_failures,
                scan_failures,
                remaining,
                oldest_remaining,
            )
        return result

    def metrics(self) -> dict[str, float | int]:
        """잔존 파일 수와 최장 잔존 시간 (KAN-27 AC, KAN-38이 소비).

        값은 마지막으로 끝까지 훑은 스윕 기준이다 - 조회할 때마다 디렉터리를 훑지 않고,
        훑기에 실패한 스윕으로 값을 덮어쓰지도 않는다. 값이 멎었다는 사실은
        ``tempScanFailures``가 알린다. BE의 ``accentury.upload.temp.*``와 같은 의미다.
        """
        return {
            "tempFiles": self._residual_files,
            "tempOldestAgeSeconds": round(self._oldest_remaining_seconds),
            "tempDeleteFailures": self._delete_failures,
            "tempScanFailures": self._scan_failures,
            "tempSweeps": self._sweeps,
        }
