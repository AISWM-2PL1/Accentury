"""임시 저장소의 명세 (KAN-27 - 전용 디렉터리, 즉시 삭제, 30분 청소, 멱등)."""

from __future__ import annotations

import asyncio
import os
import stat
import time
from contextlib import contextmanager

import pytest

from app.tempstore import VoiceTempStore

RETENTION = 30 * 60


@pytest.fixture
def store(tmp_path) -> VoiceTempStore:
    created = VoiceTempStore(tmp_path / "ai-tmp", RETENTION)
    created.prepare()
    return created


# === 전용 디렉터리와 권한 ===


def test_디렉터리는_소유자_전용_권한으로_만들어진다(store):
    mode = stat.S_IMODE(store.directory.stat().st_mode)

    assert mode == 0o700


def test_경로가_심볼릭_링크면_기동이_실패한다(tmp_path):
    # 링크를 타면 권한 강제와 기동 정리가 남의 디렉터리에 가해진다 (Codex sol 리뷰 P1)
    target = tmp_path / "남의-디렉터리"
    target.mkdir()
    희생_파일 = target / "남의-파일"
    희생_파일.write_bytes(b"other-owner")
    link = tmp_path / "ai-tmp"
    link.symlink_to(target)

    with pytest.raises(RuntimeError, match="디렉터리가 아니다"):
        VoiceTempStore(link, RETENTION).prepare()

    assert 희생_파일.exists()


def test_이미_있는_디렉터리의_느슨한_권한은_다시_조인다(store):
    # 앞선 배포나 수동 조작으로 열려 있으면 같은 호스트의 다른 계정이 잔존물을 읽는다
    os.chmod(store.directory, 0o777)

    store.prepare()

    assert stat.S_IMODE(store.directory.stat().st_mode) == 0o700


def test_임시파일은_예측_불가능한_이름과_최소_권한이다(store):
    with 오디오_임시파일(store) as path:
        assert path.parent == store.directory
        assert path.name.startswith("audio-") and path.name != "audio-.wav"
        assert stat.S_IMODE(path.stat().st_mode) == 0o600


# === 종료 경로별 삭제 ===


def test_정상_종료면_파일이_지워진다(store):
    with 오디오_임시파일(store) as path:
        assert path.exists()

    assert not path.exists()


def test_예외로_빠져나가도_파일이_지워진다(store):
    with pytest.raises(RuntimeError):
        with 오디오_임시파일(store) as path:
            raise RuntimeError("추론 실패 시뮬레이션")

    assert not path.exists()


def test_클라이언트_취소로_빠져나가도_파일이_지워진다(store):
    # 요청 중 연결이 끊기면 서버 태스크가 취소된다 - 정상 반환 경로에만 기대면 여기서 남는다
    async def 취소된_요청():
        with 오디오_임시파일(store) as path:
            holder.append(path)
            raise asyncio.CancelledError

    holder = []
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(취소된_요청())

    assert not holder[0].exists()


def test_삭제는_멱등하다(store):
    with 오디오_임시파일(store) as path:
        pass

    store.discard(path)  # 이미 없는 파일을 다시 지워도 실패로 세지 않는다

    assert store.metrics()["tempDeleteFailures"] == 0


# === 청소 잡 ===


def test_보존_기간이_지난_파일만_지운다(store):
    now = time.time()
    stale = 파일(store, "stale", now - RETENTION - 60)
    fresh = 파일(store, "fresh", now - RETENTION + 60)

    result = store.sweep(now)

    assert not stale.exists()
    assert fresh.exists()
    assert result.deleted == 1
    assert result.remaining == 1


def test_두_번_돌려도_결과가_같다(store):
    now = time.time()
    파일(store, "stale", now - RETENTION - 60)

    first = store.sweep(now)
    second = store.sweep(now)

    assert first.deleted == 1
    assert second.deleted == 0
    assert not second.any_failure


def test_최장_잔존_시간은_남은_파일_중_가장_오래된_값이다(store):
    now = time.time()
    파일(store, "older", now - 1200)
    파일(store, "newer", now - 300)

    result = store.sweep(now)

    assert result.remaining == 2
    assert result.oldest_remaining_seconds == pytest.approx(1200, abs=1)
    assert store.metrics()["tempOldestAgeSeconds"] == pytest.approx(1200, abs=1)


def test_하위_디렉터리는_건드리지_않는다(store):
    nested = store.directory / "nested"
    nested.mkdir()
    os.utime(nested, (0, 0))

    result = store.sweep()

    assert nested.exists()
    assert result.deleted == 0
    assert not result.any_failure
    # 지우지는 않되 잔존으로는 센다 - 우리가 만들지 않은 것이 있다는 사실은 보여야 한다
    assert result.remaining == 1


def test_끊어진_심볼릭_링크도_정리된다(store):
    # lstat 대신 stat을 쓰면 끊어진 링크가 "이미 지워졌다"로 보여, 링크는 영영 남으면서
    # 잔존 집계에도 빠져 디렉터리가 깨끗하다고 보고된다 (Codex 리뷰)
    link = store.directory / "dangling"
    link.symlink_to(store.directory / "없는-대상")

    result = store.purge_leftovers()

    assert not link.is_symlink()
    assert result.deleted == 1
    assert result.remaining == 0


def test_디렉터리를_읽지_못해도_예외를_던지지_않는다(tmp_path):
    # 청소 잡 루프에서 예외가 새면 이후 주기가 통째로 끊긴다
    없는_디렉터리 = VoiceTempStore(tmp_path / "없음", RETENTION)

    result = 없는_디렉터리.sweep()

    assert result.scan_failures == 1
    assert result.deleted == 0
    assert not result.scanned


def test_훑기에_실패하면_잔존_메트릭을_덮어쓰지_않는다(store):
    # 0으로 덮어써 "깨끗하다"고 보고하면 정리가 막힌 바로 그 순간에 알림이 조용해진다
    now = time.time()
    파일(store, "fresh", now - 300)
    store.sweep(now)
    assert store.metrics()["tempFiles"] == 1

    (store.directory / "fresh").unlink()
    store.directory.rmdir()
    result = store.sweep(now)

    assert not result.scanned
    assert store.metrics()["tempFiles"] == 1
    assert store.metrics()["tempScanFailures"] == 1


def test_사라진_디렉터리를_되살린다(store):
    # 없어진 채로 두면 mkstemp가 실패해 분석 요청이 전부 죽는다 (Codex 리뷰)
    store.directory.rmdir()

    assert store.ensure_exists() is True
    assert store.directory.is_dir()
    assert stat.S_IMODE(store.directory.stat().st_mode) == 0o700
    assert store.ensure_exists() is False


def test_기동_정리는_나이를_보지_않고_전부_지운다(store):
    # kill 직전에 만들어진 파일까지 재시작 즉시 없앤다 - 이 시점에는 살아 있는 요청이 없다.
    # 수정 시각이 미래인 파일(시각 되감기)도 남으면 안 된다 (Codex sol 리뷰 P2)
    방금_만든_파일 = 파일(store, "leftover", time.time())
    미래_파일 = 파일(store, "future", time.time() + 3600)

    result = store.purge_leftovers()

    assert not 방금_만든_파일.exists()
    assert not 미래_파일.exists()
    assert result.deleted == 2
    assert result.remaining == 0


def test_지우지_못한_만료_파일은_잔존으로_센다(store, monkeypatch):
    # 실패만 세고 넘기면 정리가 막힌 그 순간에 tempFiles가 0을 가리켜 알림이 조용해진다
    now = time.time()
    파일(store, "stale", now - RETENTION - 60)

    def 삭제_거부(self, missing_ok: bool = False):
        raise PermissionError("삭제 권한 없음 시뮬레이션")

    monkeypatch.setattr("pathlib.Path.unlink", 삭제_거부)

    result = store.sweep(now)

    assert result.delete_failures == 1
    assert result.deleted == 0
    assert result.remaining == 1
    assert result.oldest_remaining_seconds == pytest.approx(RETENTION + 60, abs=1)


@contextmanager
def 오디오_임시파일(store: VoiceTempStore):
    """전용 임시파일에 오디오를 채워 넣은 상태 - 실제 엔드포인트가 하는 일과 같다."""
    with store.temp_file() as path:
        path.write_bytes(b"audio")
        yield path


def 파일(store: VoiceTempStore, name: str, modified: float):
    path = store.directory / name
    path.write_bytes(b"audio")
    os.utime(path, (modified, modified))
    return path
