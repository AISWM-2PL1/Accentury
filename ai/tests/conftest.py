"""테스트 공통 픽스처."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

#: 판정 실패 스텁을 태울 문항 - 실패 종료 경로 검증용
FAIL_ITEM = "v5"


@pytest.fixture
def settings(tmp_path) -> Settings:
    # 지연 0ms - 테스트가 추론 흉내에 시간을 쓰지 않게 한다
    return Settings(temp_dir=tmp_path / "ai-tmp", stub_delay_ms=0, stub_fail_item=FAIL_ITEM)


@pytest.fixture
def client(settings: Settings) -> TestClient:
    # with 블록이어야 lifespan(디렉터리 준비, 청소 잡)이 돈다
    with TestClient(create_app(settings)) as test_client:
        yield test_client
