"""분석 엔진 어댑터 경계의 명세 (KAN-135).

여기서 지키는 것은 둘이다.

1. **라우트는 엔진 구현을 모른다** - 프로토콜만 맞춘 가짜 엔진을 꽂아도 라우트 코드를
   한 줄도 고치지 않고 응답이 그대로 바뀐다. 실모델(KAN-22)이 들어올 때 고쳐야 할 파일이
   엔진 하나로 묶인다는 뜻이다.
2. **보장은 엔진 종류와 무관하다** - 무잔존(KAN-27), 추론 상한, 오디오 상한(413)은
   라우트에 있으므로 어떤 엔진을 꽂아도 같이 걸린다.
"""

from __future__ import annotations

import itertools
import logging
import math
import time

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.engine import (
    JUDGED_QUALITY_CODES,
    MAX_QUALITY_CODE_LENGTH,
    STATUS_FAILED,
    STATUS_OK,
    AnalysisOutcome,
    AnalysisRequest,
    StubEngine,
    create_engine,
)
from app.main import create_app
from tests.conftest import FakeEngine, meta, post, residue


def app_with(settings: Settings, engine) -> TestClient:
    return TestClient(create_app(settings, engine=engine))


def 라우트_오류_로그(caplog) -> list[str]:
    """라우트가 낸 ERROR만 고른다.

    ``caplog.text``를 그대로 보면 다른 로거가 낸 ERROR와 ``with`` 블록 밖의 기록까지
    섞인다 - ``at_level``은 레벨만 조정할 뿐 캡처 범위를 좁히지 않기 때문이다.
    """
    return [
        record.getMessage()
        for record in caplog.records
        if record.name == "app.analyze" and record.levelno >= logging.ERROR
    ]


class BlockingEngine:
    """이벤트 루프를 막는 엔진 - 프로토콜 계약의 반례다 (KAN-135 리뷰 P2-1).

    실모델을 붙이는 사람이 동기 추론을 ``async def`` 안에서 그대로 돌리면 이 모양이 된다.
    """

    model_version = "blocking-0.1"

    def __init__(self, seconds: float) -> None:
        self._seconds = seconds

    async def analyze(self, request: AnalysisRequest) -> AnalysisOutcome:
        # 일부러 await 없이 막는다 - 이벤트 루프가 여기서 멈춘다
        time.sleep(self._seconds)
        return AnalysisOutcome.ok(intonation_score=50)


def test_가짜_엔진을_꽂아도_라우트를_고치지_않는다(settings):
    # 라우트가 구현이 아니라 구조만 본다는 증명 - FakeEngine은 프로토콜을 상속하지 않는다
    engine = FakeEngine(
        outcome=AnalysisOutcome.ok(
            intonation_score=17,
            confidence=0.42,
            segments=[{"startMs": 0, "endMs": 500, "score": 17}],
        ),
        model_version="fake-9.9",
    )

    with app_with(settings, engine) as client:
        body = post(client).json()

    assert body["status"] == "OK"
    assert body["intonationScore"] == 17
    assert body["confidence"] == 0.42
    assert body["segments"] == [{"startMs": 0, "endMs": 500, "score": 17}]
    assert body["modelVersion"] == "fake-9.9"
    # 엔진이 알 바 아닌 값은 라우트가 채운다 - 봉투 조립이 경계 밖이라는 확인이다
    assert body["scoreVersion"] == "sv-0.3"
    assert "processingMs" in body


def test_modelVersion은_설정이_아니라_엔진이_보고한다(client):
    body = post(client).json()

    assert body["modelVersion"] == StubEngine.MODEL_VERSION == "stub-0.1"
    # 설정으로 덮어쓸 수 있게 두면 배포가 부른 이름과 실제 도는 것이 어긋나도 아무도 모른다
    assert not hasattr(Settings(), "stub_model_version")


def test_판정_실패_봉투는_엔진이_준_사유를_그대로_싣는다(settings):
    engine = FakeEngine(
        outcome=AnalysisOutcome.failure(quality_code="AUDIO_TOO_LONG", retryable=False),
        model_version="fake-9.9",
    )

    with app_with(settings, engine) as client:
        response = post(client)

    body = response.json()
    assert response.status_code == 422
    assert body["quality"]["code"] == "AUDIO_TOO_LONG"
    assert body["retryable"] is False
    assert body["modelVersion"] == "fake-9.9"
    assert residue(settings) == []


def test_엔진에는_살아_있는_파일과_meta_원본이_넘어간다(settings):
    engine = FakeEngine()

    with app_with(settings, engine) as client:
        post(client, item_id="v3")

    request = engine.seen[0]
    assert isinstance(request, AnalysisRequest)
    # 라우트가 meta에서 필요한 것만 골라 넘기면 새 필드를 쓸 때마다 라우트를 고쳐야 한다
    assert request.meta["durationMs"] == 3420
    assert request.item_id == "v3"
    assert request.correlation_id == "c_test"
    # 호출 시점에는 파일이 있고, 응답 뒤에는 없다 - 수명은 라우트가 쥔다 (KAN-27)
    assert engine.audio_existed == [True]
    assert not request.audio_path.exists()
    assert residue(settings) == []


def test_엔진이_터져도_오디오가_남지_않는다(settings):
    engine = FakeEngine(error=RuntimeError("엔진 폭발"))

    with app_with(settings, engine) as client:
        with pytest.raises(RuntimeError):
            post(client)

    assert residue(settings) == []


def test_느린_엔진은_라우트가_상한으로_끊는다(tmp_path):
    # 상한이 엔진 안에 있으면 새 엔진마다 다시 구현해야 하고, 빠뜨리면 임시파일 수명이 무한해진다
    settings = Settings(temp_dir=tmp_path / "ai-tmp", analysis_timeout_seconds=0.01)
    engine = FakeEngine(delay_seconds=0.5)

    with app_with(settings, engine) as client:
        response = post(client)

    assert response.status_code == 503
    assert residue(settings) == []


def test_상한을_넘는_오디오는_엔진에_닿지도_않는다(tmp_path):
    settings = Settings(temp_dir=tmp_path / "ai-tmp", max_audio_bytes=16)
    engine = FakeEngine()

    with app_with(settings, engine) as client:
        response = client.post(
            "/internal/v0/analyze",
            files={"audio": ("recording.wav", b"x" * 1024, "audio/wav")},
            data={"meta": meta()},
        )

    assert response.status_code == 413
    assert engine.calls == 0
    assert residue(settings) == []


def test_meta가_깨졌으면_엔진에_닿지도_않는다(settings):
    engine = FakeEngine()

    with app_with(settings, engine) as client:
        response = post(client, meta_json="not-json")

    assert response.status_code == 400
    assert engine.calls == 0


def test_본문_상한은_엔진과_무관하게_걸린다(tmp_path):
    settings = Settings(temp_dir=tmp_path / "ai-tmp", max_request_bytes=64)
    engine = FakeEngine()

    with app_with(settings, engine) as client:
        response = post(client)

    assert response.status_code == 413
    assert engine.calls == 0


def test_기본_설정은_스텁_엔진을_만든다():
    engine = create_engine(Settings())

    assert isinstance(engine, StubEngine)
    assert engine.model_version == "stub-0.1"


def test_모르는_엔진_이름은_기동을_세운다():
    # 스텁으로 조용히 흘러가면 실모델을 띄웠다고 믿는 환경이 고정 점수를 내보낸다
    with pytest.raises(ValueError, match="알 수 없는 분석 엔진"):
        create_engine(Settings(analysis_engine="real"))


def test_고정_모드의_스텁은_설정한_점수와_실패_문항을_따른다(tmp_path):
    # 모드를 명시한다 - 기본값은 분산이라 점수가 correlationId를 따른다 (KAN-136)
    settings = Settings(
        temp_dir=tmp_path / "ai-tmp",
        stub_delay_ms=0,
        stub_score_mode=StubEngine.SCORE_MODE_FIXED,
        stub_intonation_score=61,
        stub_fail_item="v2",
    )

    with TestClient(create_app(settings)) as client:
        정상 = post(client, item_id="v1").json()
        실패 = post(client, item_id="v2")

    assert 정상["intonationScore"] == 61
    assert 정상["confidence"] == 1.0
    assert 정상["quality"]["code"] == "OK"
    assert 정상["segments"] == []
    assert 실패.status_code == 422
    assert 실패.json()["quality"]["code"] == "AUDIO_TOO_QUIET"


def test_meta의_타입이_어긋나도_엔진_입력이_무너지지_않는다(tmp_path):
    # BE가 보내는 값이라 정상 경로에서는 문자열이지만, 어긋난 값에 엔진이 터지면
    # 무잔존 검증이 통과해도 운영에서는 500이 난다
    request = AnalysisRequest(audio_path=tmp_path / "x.wav", meta={"itemId": 3, "durationMs": 10})

    assert request.item_id == ""
    assert request.correlation_id == ""


def test_결과_분류가_상태를_따른다():
    assert AnalysisOutcome.ok(intonation_score=1).failed is False
    assert AnalysisOutcome.failure(quality_code="AUDIO_TOO_QUIET", retryable=True).failed is True


def test_이벤트_루프를_막는_엔진에는_상한이_걸리지_않는다(tmp_path, caplog):
    """라우트가 막을 수 없는 위반이라, 사실을 못 박고 신호가 남는 것까지만 보장한다.

    ``asyncio.timeout``은 ``await`` 지점에서만 발화한다 - 이 테스트가 503을 기대하도록
    바뀌는 날은 실행 방식을 공용 계층이 쥐게 된 날이고, 그때 이 테스트를 고치면 된다.
    계약 자체의 강제는 KAN-137이 맡는다.
    """
    settings = Settings(temp_dir=tmp_path / "ai-tmp", analysis_timeout_seconds=0.01)

    with caplog.at_level(logging.ERROR, logger="app.analyze"):
        with app_with(settings, BlockingEngine(0.05)) as client:
            response = post(client)

    # 503이 아니다 - 상한이 발화조차 못 했다
    assert response.status_code == 200
    assert any("상한이 발화하지 않았다" in message for message in 라우트_오류_로그(caplog))
    # 그래도 파일은 남지 않는다 - 수명 관리는 상한과 별개로 with 블록이 쥐고 있다
    assert residue(settings) == []


class 고정시계:
    """미리 정한 값을 순서대로 돌려주는 시계.

    라우트의 ``time`` 이름만 갈아끼운다 - 전역 :mod:`time`을 건드리면 이벤트 루프의
    시계까지 이 값을 받아 간다.

    값이 떨어지면 마지막 값을 계속 돌려준다 - 소진되게 두면 라우트에 ``monotonic()``
    한 줄이 느는 날 ``RuntimeError: coroutine raised StopIteration``으로 깨져서, 무엇이
    잘못됐는지 알아보기 어렵다. 호출 횟수는 계약이 아니다.
    """

    def __init__(self, values: list[float]) -> None:
        self._values = itertools.chain(values, itertools.repeat(values[-1]))

    def monotonic(self) -> float:
        return next(self._values)


def test_감지는_요청_전체가_아니라_엔진_호출_구간을_잰다(tmp_path, monkeypatch, caplog):
    """예산 비교의 대상이 엔진 구간이라는 것을 시계로 확정한다 (KAN-135 리뷰 P2-B, P2-F).

    지연으로 흉내 내면 가르지 못한다 - 요청 전체와 엔진 구간의 차이는 멀티파트를
    임시파일로 옮기는 몇 ms뿐이라, 그 차이가 드러나려면 예산의 98%를 쓰는 엔진이
    필요하고 그러면 이번엔 상한이 먼저 발화해 503이 날 위험이 생긴다. 시계를 직접
    통제하면 타이밍에 기대지 않고 두 구간을 갈라낼 수 있다.

    성공 경로에서 라우트는 ``monotonic``을 정확히 4번 부른다 (요청 시작, 엔진 시작,
    엔진 종료, 응답 조립).
    """
    # 엔진 구간 100ms(예산 1000ms 안) / 요청 전체 10200ms(예산 밖) - 요청 전체를 재던
    # 옛 감지라면 여기서 ERROR가 찍힌다
    monkeypatch.setattr("app.analyze.time", 고정시계([0.0, 10.0, 10.1, 10.2]))
    settings = Settings(temp_dir=tmp_path / "ai-tmp", analysis_timeout_seconds=1.0)

    with caplog.at_level(logging.ERROR, logger="app.analyze"):
        with app_with(settings, FakeEngine()) as client:
            body = post(client).json()

    assert body["processingMs"] == 10_200
    assert 라우트_오류_로그(caplog) == []


@pytest.mark.parametrize(
    ("설명", "kwargs"),
    [
        ("성공인데 점수가 없다", {"status": STATUS_OK}),
        ("성공인데 점수가 범위 밖이다", {"status": STATUS_OK, "intonation_score": 101}),
        ("성공인데 점수가 bool이다", {"status": STATUS_OK, "intonation_score": True}),
        (
            "성공인데 신뢰도가 없다",
            {"status": STATUS_OK, "intonation_score": 50, "confidence": None},
        ),
        ("모르는 상태다", {"status": "DEGRADED", "intonation_score": 50}),
        ("상태 대소문자가 다르다", {"status": "failed"}),
        # 실패인데 기본값을 그대로 두면 BE에서 알 수 없는 코드 "OK"가 되어, 성공 경로에서
        # 막은 것과 같은 계약 위반이 기본값으로 발생한다
        ("실패인데 사유 코드가 없다", {"status": STATUS_FAILED}),
        ("품질 코드가 비었다", {"status": STATUS_OK, "intonation_score": 50, "quality_code": ""}),
        (
            "실패 사유가 오타 난 코드다",
            {"status": STATUS_FAILED, "quality_code": "AUDIO_TOOQUIET"},
        ),
        (
            "실패 사유가 지어낸 서술적 코드다",
            {"status": STATUS_FAILED, "quality_code": "AUDIO_TOO_SHORT"},
        ),
        (
            "retryable이 불리언이 아니다",
            {"status": STATUS_FAILED, "quality_code": "AUDIO_TOO_QUIET", "retryable": "yes"},
        ),
        (
            "품질 코드가 컬럼 길이를 넘는다",
            {
                "status": STATUS_OK,
                "intonation_score": 50,
                "quality_code": "X" * (MAX_QUALITY_CODE_LENGTH + 1),
            },
        ),
    ],
)
def test_봉투로_나갈_수_없는_결과는_아예_만들어지지_않는다(설명, kwargs):
    # BE는 이런 응답을 계약 위반으로 끊고 그 거절을 회로 차단기의 실패로 센다 - 여기서 막는다
    with pytest.raises(ValueError):
        AnalysisOutcome(**kwargs)


def test_실패_결과는_점수가_없어도_된다():
    outcome = AnalysisOutcome(status=STATUS_FAILED, quality_code="AUDIO_TOO_QUIET", retryable=True)

    assert outcome.failed is True
    assert outcome.intonation_score is None


def test_modelVersion을_보고하지_않는_엔진은_기동을_세운다(settings):
    # 첫 요청에서 알게 되면 이미 BE 회로가 열린 뒤다
    with pytest.raises(ValueError, match="modelVersion"):
        create_app(settings, engine=FakeEngine(model_version=""))


def test_엔진_이름을_환경_변수에서_읽는다():
    # create_engine을 Settings로 직접 시험하면 환경 변수 이름 오타가 전부 통과한다
    assert Settings.from_env({"ACCENTURY_AI_ANALYSIS_ENGINE": "real"}).analysis_engine == "real"
    assert Settings.from_env({}).analysis_engine == "stub"


class 직렬화불가:
    """``json.dumps``가 거절하는 값 - numpy 스칼라의 스탠드인이다."""


@pytest.mark.parametrize(
    ("설명", "kwargs"),
    [
        (
            "신뢰도가 직렬화되지 않는다",
            {"status": STATUS_OK, "intonation_score": 50, "confidence": 직렬화불가()},
        ),
        (
            "신뢰도가 수가 아니다",
            {"status": STATUS_OK, "intonation_score": 50, "confidence": True},
        ),
        (
            "구간 값이 직렬화되지 않는다",
            {
                "status": STATUS_OK,
                "intonation_score": 50,
                "segments": [{"startMs": 직렬화불가()}],
            },
        ),
        (
            "구간이 매핑이 아니다",
            {"status": STATUS_OK, "intonation_score": 50, "segments": ["구간"]},
        ),
        (
            "실패 결과의 구간이 직렬화되지 않는다",
            {
                "status": STATUS_FAILED,
                "quality_code": "AUDIO_TOO_QUIET",
                "segments": [{"startMs": 직렬화불가()}],
            },
        ),
    ],
)
def test_봉투로_직렬화되지_않는_값은_아예_만들어지지_않는다(설명, kwargs):
    """실모델이 채우는 자리가 하필 검사가 가장 느슨한 자리였다 (KAN-135 리뷰 P2-E).

    ``numpy.float32``는 파이썬 ``float``의 하위 타입이 아니라 ``json.dumps``가 거절한다.
    여기서 막지 않으면 응답 조립 시점에 TypeError로 터져 500이 되고, BE는 그것을 일시
    장애로 보고 재전송 예산이 마를 때까지 같은 실패를 반복한다.
    """
    with pytest.raises(ValueError):
        AnalysisOutcome(**kwargs)


def test_정상_구간은_그대로_봉투에_실린다(settings):
    구간 = [{"startMs": 0, "endMs": 500, "score": 17}, {"startMs": 500, "endMs": 900, "score": 20}]
    engine = FakeEngine(outcome=AnalysisOutcome.ok(intonation_score=17, segments=구간))

    with app_with(settings, engine) as client:
        assert post(client).json()["segments"] == 구간


@pytest.mark.parametrize("값", [float("nan"), float("inf"), float("-inf")])
def test_수가_아닌_실수는_렌더_전에_막힌다(값):
    """검사가 렌더러와 같은 규칙이어야 한다 (KAN-135 리뷰 P2-G).

    Starlette의 JSONResponse는 ``allow_nan=False``로 직렬화한다. 검사만 기본값(True)으로
    두면 NaN이 여기를 통과한 뒤 응답 조립에서 터져 500이 된다. NaN은 무음 프레임의
    0 나눗셈이나 빈 F0 배열의 평균으로 나오고, ``isinstance(float)``도 통과한다.
    """
    assert math.isnan(값) or math.isinf(값)

    with pytest.raises(ValueError):
        AnalysisOutcome.ok(intonation_score=50, confidence=값)


def test_제너레이터로_준_구간도_봉투까지_간다(settings):
    """검증이 시퀀스를 다 소비해 버리면 안 된다 (KAN-135 리뷰 P2-H).

    조용히 빈 배열이 나가는 것은 500보다 나쁘다 - BE도 사용자도 알 방법이 없다.
    """
    구간 = [{"startMs": 0, "endMs": 500}, {"startMs": 500, "endMs": 900}]
    outcome = AnalysisOutcome.ok(intonation_score=17, segments=(s for s in 구간))

    # 굳혀 두었으므로 몇 번을 읽어도 같다
    assert list(outcome.segments) == 구간
    assert list(outcome.segments) == 구간

    with app_with(settings, FakeEngine(outcome=outcome)) as client:
        assert post(client).json()["segments"] == 구간


def test_검증_뒤에_원본_목록에_더해도_봉투가_흔들리지_않는다():
    """굳히는 것은 바깥 목록 한 겹이다.

    구간 dict 안쪽까지 복사하지는 않으므로, 엔진이 같은 dict를 계속 쥐고 값을 덮어쓰면
    봉투는 흔들린다. 그 경우는 렌더에서 걸린다.
    """
    구간 = [{"startMs": 0}]
    outcome = AnalysisOutcome.ok(intonation_score=17, segments=구간)

    # 엔진이 버퍼를 재사용하는 경우 - frozen은 필드 재바인딩만 막고 리스트 내용은 못 막는다
    구간.append({"startMs": 직렬화불가()})

    assert list(outcome.segments) == [{"startMs": 0}]


class 덕타이핑결과:
    """속성 이름만 맞춘 결과 - AnalysisOutcome의 검사를 한 번도 지나지 않는다."""

    status = STATUS_OK
    failed = False
    intonation_score = 999
    confidence = 1.0
    quality_code = "OK"
    segments = ()


def test_AnalysisOutcome이_아닌_결과는_봉투로_나가지_못한다(settings):
    """프로토콜이 구조만 보는 대가를 라우트가 막는다 (KAN-135 최종 리뷰 P2-1).

    엔진이 자기 dataclass나 SimpleNamespace를 돌려주면 __post_init__의 검사가 통째로
    우회돼 점수 999짜리 200이 나간다. BE는 그것을 계약 위반으로 끊고 **재전송 없이**
    종결하므로(INTERNAL_ERROR) 사용자가 문항을 잃는다. 500이면 BE가 다시 시도한다.
    """

    class 덕타이핑엔진:
        model_version = "duck-0.1"

        async def analyze(self, request):
            return 덕타이핑결과()

    with app_with(settings, 덕타이핑엔진()) as client:
        with pytest.raises(TypeError, match="AnalysisOutcome"):
            post(client)

    assert residue(settings) == []


@pytest.mark.parametrize("코드", sorted(JUDGED_QUALITY_CODES))
def test_2_4_판정_코드는_그대로_통과한다(코드):
    """허용 목록이 정상 실패를 막지 않는지 본다.

    BE의 ``ErrorCode`` enum에 실제로 있는 이름들이다. 하나라도 막히면 멀쩡한 판정 실패가
    500으로 바뀐다.
    """
    outcome = AnalysisOutcome.failure(quality_code=코드, retryable=True)

    assert outcome.quality_code == 코드
    assert len(코드) <= MAX_QUALITY_CODE_LENGTH
