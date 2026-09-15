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
from app.track1 import LOADING_MODEL_VERSION, Track1Engine, _is_service_sentence

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
        # 진짜 전달본과 같은 모양 - script_key -> 문장 항목. 어댑터가 score() 전에 이 목록을 본다
        self.sentences = {{"1|5": {{"대본": "내일 잔치가 있어서"}}, "slow": {{"대본": "느린 문장"}},
                          "broken": {{"대본": "참조가 빠진 문장"}}}}

    def score(self, wav, script_key, transcript=None, with_feedback=True):
        if script_key not in self.sentences:
            raise KeyError(f"서비스 문장이 아니다: {{script_key!r}}")
        if script_key == "broken":
            # 전달본 내부의 dict 조회 실패를 흉내 낸다 - 서비스 문장인데 참조가 빠진 경우
            raise KeyError("reference_1|7")
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
            # 전달본이 돌려줄 단계 시간 (KAN-204에서 정한 인터페이스). 진짜 전달본은 아직
            # 싣지 않지만, 실으면 어댑터가 무엇을 하는지는 지금 정해져 있어야 한다
            "stageMs": {{"transcribe": 40.0, "align": 10.0, "알수없음": 1.0}},
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


def test_모델_내부의_KeyError는_사용자_잘못이_아니라_서버_오류다(tmp_path, transfer):
    # PR #87 리뷰 P2. 예전에는 score() 전체를 except KeyError로 감싸, 전달본이 참조나 중간 결과
    # dict의 키 하나를 놓친 서버 결함이 "서비스 문장이 아닌 scriptKey"(비재전송 판정 실패)로
    # 둔갑했다. 사용자는 문항을 잃고 BE는 재전송하지 않으며 로그는 조사를 엉뚱한 데로 보냈다.
    settings = _settings(tmp_path, transfer)
    engine = Track1Engine(settings)

    async def scenario():
        await engine.warm_up()
        try:
            with pytest.raises(RuntimeError, match="트랙 1 추론 실패: KeyError"):
                await engine.analyze(_request(settings, script_key="broken"))
            # 워커는 살아 있다 - 예외는 응답이지 워커의 죽음이 아니다
            정상 = await engine.analyze(_request(settings))
        finally:
            await engine.close()
        return 정상

    정상 = asyncio.run(scenario())

    assert 정상.failed is False


def test_서비스_문장_판정은_전달본의_목록으로_한다():
    # 목록이 없는 전달본은 판단할 수 없으므로 통과시킨다 - 그때 KeyError는 500이 되고 BE가
    # 재전송한다 (과거처럼 판정 실패로 접지 않는다)
    class 목록_있음:
        sentences = {"1|5": {}}

    class 목록_없음:
        pass

    assert _is_service_sentence(목록_있음(), "1|5") is True
    assert _is_service_sentence(목록_있음(), "9|9") is False
    assert _is_service_sentence(목록_있음(), None) is False
    assert _is_service_sentence(목록_없음(), "9|9") is True
    assert _is_service_sentence(목록_없음(), None) is False


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


def test_요청마다_단계_시간과_콜드_웜이_적힌다(tmp_path, transfer):
    """어댑터가 잴 수 있는 바깥 구간과 전달본이 준 안쪽 구간 (KAN-204).

    워밍업이 워커를 이미 올려 뒀으므로 적재 대기는 없다. 첫 채점만 콜드다 - 가중치가 올라와
    있어도 전달본 안쪽의 지연 초기화가 거기서 한 번 일어나기 때문이고, 그것이 staging 첫 호출
    22.9초의 자리다.
    """
    settings = _settings(tmp_path, transfer)
    engine = Track1Engine(settings)

    async def scenario():
        await engine.warm_up()
        첫째, 둘째 = _request(settings), _request(settings)
        try:
            await engine.analyze(첫째)
            await engine.analyze(둘째)
        finally:
            await engine.close()
        return 첫째.stages, 둘째.stages

    첫째, 둘째 = asyncio.run(scenario())

    단계 = dict(첫째.items())
    assert set(단계) == {"lockWait", "model", "transcribe", "align"}
    # 전달본이 준 값은 그대로 오고, 목록에 없는 이름은 버려진다 (차원 값이 곧 요금이다)
    assert 단계["transcribe"] == 40.0
    assert 단계["align"] == 10.0
    assert 단계["model"] > 0
    assert 첫째.warm == "cold"
    assert 둘째.warm == "warm"


def test_재적재를_기다린_요청만_적재_대기를_적는다(tmp_path, transfer):
    """0을 적지 않는다 - 그 0들이 재적재 표본을 희석해 "재적재가 얼마나 비싼가"를 가린다."""
    settings = _settings(tmp_path, transfer)
    engine = Track1Engine(settings)

    async def scenario():
        await engine.warm_up()
        평시 = _request(settings)
        await engine.analyze(평시)
        느린 = _request(settings, script_key="slow")
        analysis = asyncio.create_task(engine.analyze(느린))
        for _ in range(200):
            await asyncio.sleep(0.05)
            if any(entry.name.startswith("track1-") for entry in settings.temp_dir.iterdir()):
                break
        analysis.cancel()
        with pytest.raises(asyncio.CancelledError):
            await analysis
        재적재_뒤 = _request(settings)
        try:
            await engine.analyze(재적재_뒤)
        finally:
            await engine.close()
        return 평시.stages, 느린.stages, 재적재_뒤.stages

    평시, 느린, 재적재_뒤 = asyncio.run(scenario())

    assert "workerLoad" not in dict(평시.items())
    # 끊긴 요청은 model을 적지 않는다 - 그 값은 곧 상한이라 분포만 오염시킨다. 그래도 그
    # 앞에서 끝난 단계는 남아 "어디까지 갔다가 끊겼는가"를 말해 준다
    assert "model" not in dict(느린.items())
    assert "lockWait" in dict(느린.items())
    assert dict(재적재_뒤.items())["workerLoad"] > 0
    assert 재적재_뒤.warm == "cold"


def test_서비스_문장이_아닌_요청은_첫_채점_자리를_쓰지_않는다(tmp_path, transfer):
    """자식이 ``score()``를 부르기 **전에** 가른 요청이다 (Codex astra 리뷰 P2).

    채점이 아니므로 그 요청의 model 표본(0에 가깝다)도, 첫 채점 표시도 남기지 않는다. 남기면
    뒤이은 진짜 첫 채점의 22.9초짜리 오버헤드가 웜으로 찍혀 지표가 그것을 영영 못 본다.
    """
    settings = _settings(tmp_path, transfer)
    engine = Track1Engine(settings)

    async def scenario():
        await engine.warm_up()
        거절, 첫_채점 = _request(settings, script_key="9|9"), _request(settings)
        try:
            await engine.analyze(거절)
            await engine.analyze(첫_채점)
        finally:
            await engine.close()
        return 거절.stages, 첫_채점.stages

    거절, 첫_채점 = asyncio.run(scenario())

    assert "model" not in dict(거절.items())
    # 콜드로도 찍히지 않는다 - 라우트가 적는 합계가 콜드 표본이 되면 워커당 1건뿐인 그
    # 계열의 p95를 0ms가 가져간다 (Codex astra 리뷰 P3)
    assert 거절.warm == "warm"
    assert 첫_채점.warm == "cold"
    assert dict(첫_채점.items())["model"] > 0
