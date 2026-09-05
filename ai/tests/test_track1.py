"""실모델 어댑터의 워커 프로토콜 명세 (KAN-22).

전달본은 이미지 안에만 있으므로(Whisper 가중치 2.8GB, 참조 191MB) 여기서는 **같은 자리에
가짜 ``scoring.serve``를 놓고** 어댑터가 그것을 어떻게 다루는지를 본다. 검사 대상은 모델이
아니라 어댑터가 지는 약속이다.

1. 가중치 적재는 ``warm_up``에서 한 번 (KAN-36 준비 상태 게이트).
2. **취소가 실제로 닿는다** - 엔진 계약 2번. 워커 프로세스가 정말 죽는지 pid로 확인한다.
3. 취소 뒤에도 오디오가 남지 않는다 - 죽은 워커가 남긴 정렬 작업 폴더를 치운다 (KAN-27).
   그리고 numpy 스칼라가 섞인 결과도 봉투까지 온전히 온다 (KAN-135가 예고한 자리).
4. 서비스 문장이 아닌 ``scriptKey``는 비재전송 판정 실패 (2026-09-05 결정, KAN-182 계약).

실모델을 실제로 태운 검증은 계약 적합성 스위트가 이미지 안에서 한다 (tests/contract, KAN-137).
"""

from __future__ import annotations

import asyncio
import os
import textwrap
from pathlib import Path

import pytest

from app.config import Settings
from app.engine import AnalysisRequest
from app.track1 import LOADING_MODEL_VERSION, Track1Engine

#: 가짜 전달본이 보고하는 버전 - 엔진이 이 값을 그대로 물어 와야 한다 (KAN-135)
FAKE_MODEL_VERSION = "track1-fake+0000000"

#: 가짜 전달본. 진짜와 같은 이름(``Track1Scorer``), 같은 생성자, 같은 ``score`` 계약이다.
#:
#: ``scriptKey``가 ``slow``면 채점이 오래 걸린다 - 취소 항목이 그 사이에 끊는다. 환경
#: 변수가 아니라 키로 고르는 이유는 취소 뒤 재적재한 워커에는 느림이 없어야 하기 때문이다.
#: 정렬 작업 폴더를 흉내 내어 임시 디렉터리에 ``track1-`` 폴더를 하나 만들어 두는 것도
#: 일부러다. 진짜 전달본이 거기에 오디오 사본을 두기 때문이다.
_FAKE_SERVE = '''
import os
import tempfile
import time
from pathlib import Path


class 넘파이흉내:
    """numpy 스칼라의 스탠드인 - json.dumps가 그대로는 거절하는 타입이다."""

    def __init__(self, value):
        self.value = value

    def item(self):
        return self.value


class Track1Scorer:
    def __init__(self, ref_dir=None, sentences=None, stt=True, whisper_device="auto"):
        self.model_version = "{model_version}"
        self.ref_dir = ref_dir
        self.sentences = sentences

    def score(self, wav, script_key, transcript=None, with_feedback=True):
        if script_key not in ("1|5", "slow"):
            raise KeyError(f"서비스 문장이 아니다: {{script_key!r}}")
        if script_key == "slow":
            # 정렬 작업 폴더를 만들어 둔 채 오래 돈다 - 취소되면 이것이 남는다
            workdir = Path(tempfile.mkdtemp(prefix="track1-"))
            (workdir / "u.wav").write_bytes(b"audio")
            time.sleep(60)
        return {{
            "status": "OK",
            "intonationScore": 87,
            # 전달본의 자연스러운 출력은 numpy 스칼라다 (KAN-135가 예고한 자리)
            "confidence": 넘파이흉내(0.91),
            "quality": {{"code": "OK"}},
            "segments": [{{"kind": "order", "word": "여기는", "st": 넘파이흉내(1.5)}}],
            "modelVersion": self.model_version,
            "processingMs": 1234,
        }}
'''


@pytest.fixture
def transfer(tmp_path: Path) -> Path:
    """가짜 전달본을 ``srcDir`` 자리에 놓는다."""
    source = tmp_path / "src" / "scoring"
    source.mkdir(parents=True)
    (source / "__init__.py").write_text("")
    (source / "serve.py").write_text(
        textwrap.dedent(_FAKE_SERVE.format(model_version=FAKE_MODEL_VERSION))
    )
    return tmp_path / "src"


def _settings(tmp_path: Path, transfer: Path, **overrides) -> Settings:
    temp_dir = tmp_path / "ai-tmp"
    temp_dir.mkdir(exist_ok=True)
    return Settings(temp_dir=temp_dir, track1_src_dir=transfer, **overrides)


def _request(settings: Settings, script_key: str | None = "1|5") -> AnalysisRequest:
    audio = settings.temp_dir / "audio-x.wav"
    audio.write_bytes(b"RIFF")
    meta = {"itemId": "v1", "scriptKey": script_key} if script_key else {"itemId": "v1"}
    return AnalysisRequest(audio_path=audio, meta=meta, correlation_id="c_track1")


def test_워밍업이_끝나면_엔진이_적재한_버전을_보고한다(tmp_path, transfer):
    settings = _settings(tmp_path, transfer)
    engine = Track1Engine(settings)

    # 적재 전에도 자리는 비지 않는다 - 앱이 기동 시 이 값을 검사한다
    assert engine.model_version == LOADING_MODEL_VERSION

    async def scenario():
        await engine.warm_up()
        try:
            assert engine.model_version == FAKE_MODEL_VERSION
            outcome = await engine.analyze(_request(settings))
        finally:
            await engine.close()
        return outcome

    outcome = asyncio.run(scenario())

    assert outcome.status == "OK"
    assert outcome.intonation_score == 87
    # numpy 스칼라가 파이썬 기본형으로 접혀 왔다 - 접지 않으면 워커가 직렬화에서 죽는다
    assert outcome.confidence == 0.91
    assert outcome.quality_code == "OK"
    assert outcome.segments == ({"kind": "order", "word": "여기는", "st": 1.5},)


def test_서비스_문장이_아닌_scriptKey는_비재전송_판정_실패다(tmp_path, transfer):
    # 재전송 가능으로 내면 정의가 바뀌지 않는 한 결과가 같은 요청을 BE가 예산이 마를
    # 때까지 반복한다 (2026-09-05 결정)
    settings = _settings(tmp_path, transfer)
    engine = Track1Engine(settings)

    async def scenario():
        await engine.warm_up()
        try:
            없음 = await engine.analyze(_request(settings, script_key=None))
            모르는_문장 = await engine.analyze(_request(settings, script_key="9|9"))
        finally:
            await engine.close()
        return 없음, 모르는_문장

    없음, 모르는_문장 = asyncio.run(scenario())

    for outcome in (없음, 모르는_문장):
        assert outcome.failed is True
        assert outcome.quality_code == "ANALYSIS_MISREAD"
        assert outcome.retryable is False


def test_취소는_워커_프로세스까지_닿고_잔여물을_남기지_않는다(tmp_path, transfer):
    """엔진 계약 2번 - 취소가 실제로 닿는다.

    스레드로 넘겼다면 여기서 프로세스가 살아 있고, 라우트가 지운 오디오를 계속 붙들고
    있게 된다. 워커가 만든 정렬 작업 폴더(오디오 사본이 든다)도 함께 사라져야 한다 (KAN-27).
    """
    settings = _settings(tmp_path, transfer)
    engine = Track1Engine(settings)

    async def scenario():
        await engine.warm_up()
        pid = engine._process.pid  # noqa: SLF001 - 프로세스가 정말 죽는지가 이 항목의 전부다
        analysis = asyncio.create_task(engine.analyze(_request(settings, script_key="slow")))
        # 워커가 정렬 작업 폴더를 만들 때까지 기다린다
        for _ in range(200):
            await asyncio.sleep(0.05)
            if any(entry.name.startswith("track1-") for entry in settings.temp_dir.iterdir()):
                break
        analysis.cancel()
        with pytest.raises(asyncio.CancelledError):
            await analysis
        # 거두기와 잔여물 정리는 뒤에서 돈다 - close가 그것을 기다린다
        await engine.close()
        return pid

    pid = asyncio.run(scenario())

    assert not _alive(pid), "취소 뒤에도 워커가 살아 있다 - 계약 2 위반"
    assert [entry.name for entry in settings.temp_dir.iterdir()] == ["audio-x.wav"]


def test_취소_직후의_요청이_재적재를_기다렸다_정상으로_돌아온다(tmp_path, transfer):
    """취소는 재적재를 뒤에서 시작한다 - 그 워커를 준비 전에 쓰면 안 된다.

    준비 메시지를 기다리는 코루틴과 요청 코루틴이 같은 파이프를 동시에 읽으면 asyncio가
    거절하고, 요청은 계약과 무관한 오류로 죽는다. KAN-137 계약 스위트가 실모델에서 잡은
    자리이고(2026-09-05), 여기서는 그것을 초 단위로 재현한다.
    """
    settings = _settings(tmp_path, transfer)
    engine = Track1Engine(settings)

    async def scenario():
        await engine.warm_up()
        analysis = asyncio.create_task(engine.analyze(_request(settings, script_key="slow")))
        for _ in range(200):
            await asyncio.sleep(0.05)
            if any(entry.name.startswith("track1-") for entry in settings.temp_dir.iterdir()):
                break
        analysis.cancel()
        with pytest.raises(asyncio.CancelledError):
            await analysis
        try:
            # 재적재가 아직 도는 중이다 - 이 요청은 그것을 기다렸다가 정상으로 끝나야 한다
            return await engine.analyze(_request(settings))
        finally:
            await engine.close()

    outcome = asyncio.run(scenario())

    assert outcome.status == "OK"
    assert outcome.intonation_score == 87


def test_워커가_죽으면_다음_요청이_새_워커로_간다(tmp_path, transfer):
    # 워커가 죽는 것은 취소 말고도(OOM 킬러 등) 일어난다. 그때 엔진이 그대로 멈추면
    # health는 UP인 채 전 요청이 실패한다
    settings = _settings(tmp_path, transfer)
    engine = Track1Engine(settings)

    async def scenario():
        await engine.warm_up()
        first = engine._process.pid  # noqa: SLF001
        os.killpg(os.getpgid(first), 9)
        await asyncio.sleep(0.2)
        try:
            outcome = await engine.analyze(_request(settings))
            second = engine._process.pid  # noqa: SLF001
        finally:
            await engine.close()
        return first, second, outcome

    first, second, outcome = asyncio.run(scenario())

    assert second != first
    assert outcome.status == "OK"


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except (ProcessLookupError, PermissionError):
        return False
    return True
