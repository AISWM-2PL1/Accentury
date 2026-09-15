"""테스트 공통 픽스처."""

from __future__ import annotations

import asyncio
import json

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.engine import AnalysisOutcome, AnalysisRequest
from app.main import create_app

def pytest_addoption(parser: pytest.Parser) -> None:
    """계약 적합성 스위트(KAN-137, ``tests/contract``)가 읽는 옵션.

    선언이 스위트 옆이 아니라 여기 있는 이유는 pytest가 ``pytest_addoption``을 **테스트
    루트의 conftest에서만** 읽기 때문이다 - 하위 디렉터리에 두면 그 디렉터리를 직접
    지목해 실행할 때만 살아나고, 그냥 ``pytest``로 돌리면 옵션을 모르는 실행이 된다.
    """
    parser.addoption(
        "--contract-audio",
        default=None,
        help=(
            "계약 스위트가 보낼 WAV 파일 경로 (기본: 합성 사인파). 성공 경로를 보려면 "
            "실제 발화 녹음을 준다 - 합성음은 내용 게이트에서 판정 실패로 떨어진다"
        ),
    )


#: 가짜 엔진이 판정 실패를 내는 문항 - 실패 종료 경로 검증용
FAIL_ITEM = "v5"

#: 가짜 엔진이 보고하는 modelVersion. 엔진이 스스로 보고하는 값이라는 것을 테스트가
#: 확인할 때 쓴다 (KAN-135)
FAKE_MODEL_VERSION = "fake-9.9"

#: 요청에 실어 보내는 오디오 - 가짜 엔진은 내용을 보지 않고 크기만 만진다
AUDIO = b"RIFF....WAVEfmt " + bytes(64)


#: 기본 추적 ID (§2.2)
CORRELATION_ID = "c_test"

#: "meta의 추적 ID를 헤더와 같게 두라"는 기본값.
#:
#: ``None``을 기본값으로 쓸 수 없어서 따로 둔다 - ``None``은 "meta에서 키를 빼라"는 뜻이고,
#: 그 둘을 구분해야 헤더만 오는 호출자를 재현할 수 있다 (KAN-136).
SAME_AS_HEADER = object()


def meta(item_id: str = "v1", correlation_id: object | None = CORRELATION_ID) -> str:
    """BE가 보내는 meta 파트 (§3.3, §4.1).

    ``correlation_id=None``이면 ``correlationId`` 키 자체를 뺀다. 문자열이 아닌 값도
    받는다 - BE가 계약을 어긴 meta를 보냈을 때 서버가 어떻게 구는지가 테스트 대상이다.
    """
    payload: dict[str, object] = {}
    if correlation_id is not None:
        # 명세 예시의 순서를 지킨다 - correlationId가 맨 앞이다
        payload["correlationId"] = correlation_id
    payload.update(
        {
            "itemId": item_id,
            "testVersion": "gn-2026.08.1",
            "scoreVersion": "sv-0.3",
            "durationMs": 3420,
        }
    )
    return json.dumps(payload)


def post(
    client,
    item_id: str = "v1",
    meta_json: str | None = None,
    correlation_id: str | None = CORRELATION_ID,
    meta_correlation_id: object = SAME_AS_HEADER,
):
    """``POST /internal/v0/analyze`` 한 번.

    추적 ID를 **헤더와 meta로 나눠 준다**. 라우트는 헤더를 먼저 보고 없을 때만 meta를
    보므로(§2.2), 하나로 묶어 두면 그 우선순위가 지켜지는지를 테스트가 물어볼 수 없다.
    기본값은 BE가 실제로 하는 것과 같게 둘을 같은 값으로 채운다 (``RestAiAnalysisClient``).

    - ``correlation_id=None`` - 헤더를 아예 붙이지 않는다.
    - ``meta_correlation_id=None`` - meta에서 ``correlationId`` 키를 뺀다.
    """
    in_meta = correlation_id if meta_correlation_id is SAME_AS_HEADER else meta_correlation_id
    body = meta(item_id, in_meta) if meta_json is None else meta_json
    return client.post(
        "/internal/v0/analyze",
        files={"audio": ("recording.wav", AUDIO, "audio/wav")},
        data={"meta": body},
        headers={} if correlation_id is None else {"X-Correlation-Id": correlation_id},
    )


def residue(settings: Settings) -> list[str]:
    """임시 디렉터리에 남은 것 - 어느 종료 경로에서도 비어 있어야 한다 (KAN-27)."""
    return [entry.name for entry in settings.temp_dir.iterdir()]


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(temp_dir=tmp_path / "ai-tmp")


@pytest.fixture
def client(settings: Settings) -> TestClient:
    """라우트 검증용 서버 - 엔진은 언제나 가짜다.

    설정이 고르는 엔진(실모델)을 쓰지 않는 이유는 이 파일의 테스트가 **라우트의 보장**을
    보기 때문이다 (KAN-135) - 무잔존, 봉투, 상한은 엔진 종류와 무관해야 하고, 실모델은
    전달본 이미지 안에서만 임포트된다. 엔진 자체의 계약은 tests/contract가 본다 (KAN-137).
    """
    # with 블록이어야 lifespan(디렉터리 준비, 청소 잡)이 돈다
    with TestClient(create_app(settings, engine=FakeEngine(fail_item=FAIL_ITEM))) as test_client:
        yield test_client


class FakeEngine:
    """:class:`app.engine.AnalysisEngine` 프로토콜만 맞춘 가짜 엔진 (KAN-135).

    상속하지 않는다 - 라우트가 구현이 아니라 구조만 본다는 것이 이 클래스의 존재 이유다.
    호출 기록을 남겨 "엔진에 닿기 전에 끊는" 경로(413)까지 확인할 수 있게 한다.
    """

    def __init__(
        self,
        outcome: AnalysisOutcome | None = None,
        model_version: str = FAKE_MODEL_VERSION,
        error: Exception | None = None,
        delay_seconds: float = 0.0,
        fail_item: str | None = None,
    ) -> None:
        self._outcome = outcome or AnalysisOutcome.ok(intonation_score=42)
        self._model_version = model_version
        self._error = error
        self._delay_seconds = delay_seconds
        #: 이 itemId면 판정 실패(422)를 낸다 - 실패 종료 경로를 여는 수단이다
        self._fail_item = fail_item
        #: 라우트가 넘긴 입력 - 경로와 meta를 그대로 들고 있는다
        self.seen: list[AnalysisRequest] = []
        #: 호출 시점에 오디오 파일이 실제로 있었는지 (수명 관리는 라우트 몫이다)
        self.audio_existed: list[bool] = []

    @property
    def model_version(self) -> str:
        return self._model_version

    async def analyze(self, request: AnalysisRequest) -> AnalysisOutcome:
        self.seen.append(request)
        self.audio_existed.append(request.audio_path.exists())
        if self._delay_seconds:
            await asyncio.sleep(self._delay_seconds)
        if self._error is not None:
            raise self._error
        if self._fail_item is not None and request.item_id == self._fail_item:
            return AnalysisOutcome.failure(quality_code="AUDIO_TOO_QUIET", retryable=True)
        return self._outcome

    @property
    def calls(self) -> int:
        return len(self.seen)
