"""테스트 공통 픽스처."""

from __future__ import annotations

import asyncio
import json

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.engine import AnalysisOutcome, AnalysisRequest
from app.main import create_app

#: 판정 실패 스텁을 태울 문항 - 실패 종료 경로 검증용
FAIL_ITEM = "v5"

#: 요청에 실어 보내는 오디오 - 내용은 보지 않는다 (스텁도 가짜 엔진도 크기만 만진다)
AUDIO = b"RIFF....WAVEfmt " + bytes(64)


def meta(item_id: str = "v1") -> str:
    """BE가 보내는 meta 파트 (§3.3, §4.1)."""
    return json.dumps(
        {
            "correlationId": "c_test",
            "itemId": item_id,
            "testVersion": "gn-2026.08.1",
            "scoreVersion": "sv-0.3",
            "durationMs": 3420,
        }
    )


def post(client, item_id: str = "v1", meta_json: str | None = None):
    return client.post(
        "/internal/v0/analyze",
        files={"audio": ("recording.wav", AUDIO, "audio/wav")},
        data={"meta": meta(item_id) if meta_json is None else meta_json},
        headers={"X-Correlation-Id": "c_test"},
    )


def residue(settings: Settings) -> list[str]:
    """임시 디렉터리에 남은 것 - 어느 종료 경로에서도 비어 있어야 한다 (KAN-27)."""
    return [entry.name for entry in settings.temp_dir.iterdir()]


@pytest.fixture
def settings(tmp_path) -> Settings:
    # 지연 0ms - 테스트가 추론 흉내에 시간을 쓰지 않게 한다
    return Settings(temp_dir=tmp_path / "ai-tmp", stub_delay_ms=0, stub_fail_item=FAIL_ITEM)


@pytest.fixture
def client(settings: Settings) -> TestClient:
    # with 블록이어야 lifespan(디렉터리 준비, 청소 잡)이 돈다
    with TestClient(create_app(settings)) as test_client:
        yield test_client


class FakeEngine:
    """:class:`app.engine.AnalysisEngine` 프로토콜만 맞춘 가짜 엔진 (KAN-135).

    상속하지 않는다 - 라우트가 구현이 아니라 구조만 본다는 것이 이 클래스의 존재 이유다.
    호출 기록을 남겨 "엔진에 닿기 전에 끊는" 경로(413)까지 확인할 수 있게 한다.
    """

    def __init__(
        self,
        outcome: AnalysisOutcome | None = None,
        model_version: str = "fake-9.9",
        error: Exception | None = None,
        delay_seconds: float = 0.0,
    ) -> None:
        self._outcome = outcome or AnalysisOutcome.ok(intonation_score=42)
        self._model_version = model_version
        self._error = error
        self._delay_seconds = delay_seconds
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
        return self._outcome

    @property
    def calls(self) -> int:
        return len(self.seen)
