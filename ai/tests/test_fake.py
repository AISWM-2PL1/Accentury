"""가짜 엔진 (app/fake.py) - 로컬 스택의 유일한 AI다 (KAN-22, PR #87 리뷰 반영)."""

from __future__ import annotations

import asyncio
from pathlib import Path

import pytest

from app.config import Settings
from app.engine import FAKE_ENGINE, AnalysisRequest, create_engine
from app.fake import FAKE_MODEL_VERSION, FakeEngine


def _request(tmp_path: Path, item_id: str = "v1", correlation_id: str = "c_fake") -> AnalysisRequest:
    audio = tmp_path / "audio-x.wav"
    audio.write_bytes(b"RIFF")
    return AnalysisRequest(
        audio_path=audio, meta={"itemId": item_id, "scriptKey": "1|5"}, correlation_id=correlation_id
    )


def test_설정으로_고르면_가짜_엔진이_만들어지고_정체를_보고한다():
    engine = create_engine(Settings(analysis_engine=FAKE_ENGINE))

    assert isinstance(engine, FakeEngine)
    # 응답 봉투의 modelVersion에 이 값이 보이면 가짜가 돌고 있다는 뜻이어야 한다
    assert engine.model_version == FAKE_MODEL_VERSION


def test_배포_환경에서는_기동을_거부한다():
    # 배포 compose가 켜는 토큰 필수 옵션과 양립하지 않는다 - 운영 사용자가 해시 점수를 받는 일이
    # 기동 실패(롤백)로 바뀐다
    with pytest.raises(ValueError, match="배포 환경"):
        create_engine(Settings(analysis_engine=FAKE_ENGINE, internal_token_required=True))


def test_점수는_correlationId의_해시라_결정적이고_0_100_안이다(tmp_path):
    engine = FakeEngine(Settings(analysis_engine=FAKE_ENGINE, fake_delay_ms=0))

    first = asyncio.run(engine.analyze(_request(tmp_path, correlation_id="c_1")))
    again = asyncio.run(engine.analyze(_request(tmp_path, correlation_id="c_1")))
    other = asyncio.run(engine.analyze(_request(tmp_path, correlation_id="c_2")))

    assert first.failed is False
    assert first.intonation_score == again.intonation_score
    assert 0 <= first.intonation_score <= 100
    # 다른 추적 ID는 (거의 언제나) 다른 점수다 - 5등급이 전부 관측되는 데모 데이터의 전제
    assert {FakeEngine.hashed_score(f"c_{i}") for i in range(200)} != {first.intonation_score}
    assert other.failed is False


def test_지정한_문항은_재전송_가능한_판정_실패다(tmp_path):
    # E2E의 실패 갈래(retake)가 RETRYABLE_FAILED를 만드는 수단이다 - docker-compose.yml의 E2E_FAIL_ITEM
    engine = FakeEngine(Settings(analysis_engine=FAKE_ENGINE, fake_delay_ms=0, fake_fail_item="v3"))

    failed = asyncio.run(engine.analyze(_request(tmp_path, item_id="v3")))
    passed = asyncio.run(engine.analyze(_request(tmp_path, item_id="v1")))

    assert failed.failed is True
    assert failed.quality_code == "AUDIO_TOO_QUIET"
    assert failed.retryable is True
    assert passed.failed is False


def test_환경_변수로_실패_문항과_지연을_읽는다():
    settings = Settings.from_env(
        {"ACCENTURY_AI_ANALYSIS_ENGINE": "fake", "ACCENTURY_AI_FAKE_FAIL_ITEM": "v3", "ACCENTURY_AI_FAKE_DELAY_MS": "0"}
    )

    assert settings.analysis_engine == FAKE_ENGINE
    assert settings.fake_fail_item == "v3"
    assert settings.fake_delay_ms == 0
    # 빈 값은 없는 것과 같다 - compose가 "${E2E_FAIL_ITEM:-}"로 빈 문자열을 넘긴다
    assert Settings.from_env({"ACCENTURY_AI_FAKE_FAIL_ITEM": ""}).fake_fail_item is None
