"""내부 호출 인증과 준비 상태 게이트 (KAN-36).

backend와 다른 호스트로 갈라진 뒤의 두 보장이다 - 토큰 없는 호출은 오디오를 받지 않고
401로 끊기고, 워밍업 전에는 health가 UP을 내지 않는다.
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.auth import INTERNAL_TOKEN_HEADER
from app.config import Settings
from app.main import create_app
from tests.conftest import AUDIO, FakeEngine, meta, residue

TOKEN = "shared-secret-0123456789abcdef0123456789abcdef"


def _settings(tmp_path, token: str | None = TOKEN) -> Settings:
    return Settings(temp_dir=tmp_path / "ai-tmp", stub_delay_ms=0, internal_token=token)


def _analyze(client: TestClient, headers: dict[str, str]):
    return client.post(
        "/internal/v0/analyze",
        files={"audio": ("recording.wav", AUDIO, "audio/wav")},
        data={"meta": meta()},
        headers=headers,
    )


def test_토큰_없는_분석_요청은_401이고_엔진에_닿지_않는다(tmp_path):
    settings = _settings(tmp_path)
    engine = FakeEngine()

    with TestClient(create_app(settings, engine=engine)) as client:
        response = _analyze(client, {})

        assert response.status_code == 401
        assert response.json()["status"] == "FAILED"
        assert engine.calls == 0
        # 인증 전에 끊었으므로 오디오가 임시 디렉터리에 내려간 적도 없다
        assert residue(settings) == []


def test_토큰이_다르면_401이다(tmp_path):
    with TestClient(create_app(_settings(tmp_path))) as client:
        response = _analyze(client, {INTERNAL_TOKEN_HEADER: TOKEN + "x"})

        assert response.status_code == 401


def test_토큰이_맞으면_분석이_돈다(tmp_path):
    settings = _settings(tmp_path)

    with TestClient(create_app(settings)) as client:
        response = _analyze(client, {INTERNAL_TOKEN_HEADER: TOKEN})

        assert response.status_code == 200
        assert response.json()["status"] == "OK"
        assert residue(settings) == []


def test_metrics도_토큰을_요구한다(tmp_path):
    with TestClient(create_app(_settings(tmp_path))) as client:
        assert client.get("/internal/v0/metrics").status_code == 401
        assert client.get("/internal/v0/metrics", headers={INTERNAL_TOKEN_HEADER: TOKEN}).status_code == 200


def test_health는_토큰_없이_열려_있다(tmp_path):
    # compose healthcheck와 호스트 상태 지표 프로브가 토큰 없이 두드린다
    with TestClient(create_app(_settings(tmp_path))) as client:
        response = client.get("/internal/v0/health")

        assert response.status_code == 200
        assert response.json() == {"status": "UP"}


def test_토큰이_설정되지_않은_서버는_검사를_건너뛴다(tmp_path):
    # 로컬 개발 편의 - 배포에서는 Terraform이 언제나 값을 넣는다
    with TestClient(create_app(_settings(tmp_path, token=None))) as client:
        assert _analyze(client, {}).status_code == 200


def test_워밍업_전에는_health가_503_STARTING이다(tmp_path):
    # with 블록 없이 만들면 lifespan이 돌지 않는다 - 프로세스는 떴지만 준비 전인 상태다
    client = TestClient(create_app(_settings(tmp_path)))

    response = client.get("/internal/v0/health")

    assert response.status_code == 503
    assert response.json() == {"status": "STARTING"}


def test_엔진의_워밍업이_끝난_뒤에야_UP이다(tmp_path):
    class WarmingEngine(FakeEngine):
        def __init__(self) -> None:
            super().__init__()
            self.warmed = False

        async def warm_up(self) -> None:
            self.warmed = True

    engine = WarmingEngine()
    app = create_app(_settings(tmp_path), engine=engine)
    assert TestClient(app).get("/internal/v0/health").status_code == 503

    with TestClient(app) as client:
        assert engine.warmed is True
        assert client.get("/internal/v0/health").json() == {"status": "UP"}

    # 종료 뒤에는 다시 준비 전이다 - 재기동 사이에 UP을 내지 않는다
    assert TestClient(app).get("/internal/v0/health").status_code == 503
