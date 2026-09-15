"""내부 호출 인증과 준비 상태 게이트 (KAN-36).

backend와 다른 호스트로 갈라진 뒤의 두 보장이다 - 토큰 없는 호출은 오디오를 받지 않고
401로 끊기고, 워밍업 전에는 health가 UP을 내지 않는다.
"""

from __future__ import annotations

import logging
import re
import time

import pytest
from fastapi.testclient import TestClient

from app.auth import INTERNAL_TOKEN_HEADER
from app.config import Settings
from app.main import create_app
from tests.conftest import AUDIO, FakeEngine, meta, residue

TOKEN = "shared-secret-0123456789abcdef0123456789abcdef"


def _settings(tmp_path, token: str | None = TOKEN) -> Settings:
    return Settings(temp_dir=tmp_path / "ai-tmp", internal_token=token)


def _app(settings: Settings):
    """가짜 엔진을 꽂은 앱 - 인증은 엔진 종류와 무관하다 (KAN-135)."""
    return create_app(settings, engine=FakeEngine())


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
    app = create_app(settings, engine=engine)

    def never_spool():
        raise AssertionError("인증 전에 끊어야 한다 - 임시파일을 만들면 본문을 이미 파싱한 것이다")

    # 라우트가 오디오를 내려놓는 유일한 경로를 막아 둔다 - 미들웨어가 아니라 엔드포인트 의존성으로
    # 검사하면 여기서 터진다 (500). 응답 뒤 잔존만 보는 검사로는 "파싱 전"이 증명되지 않는다 (리뷰)
    app.state.temp_store.temp_file = never_spool

    with TestClient(app) as client:
        response = _analyze(client, {})

        assert response.status_code == 401
        assert response.json()["status"] == "FAILED"
        assert engine.calls == 0
        assert residue(settings) == []


def test_토큰이_다르면_401이다(tmp_path):
    with TestClient(_app(_settings(tmp_path))) as client:
        response = _analyze(client, {INTERNAL_TOKEN_HEADER: TOKEN + "x"})

        assert response.status_code == 401


def test_토큰이_맞으면_분석이_돈다(tmp_path):
    settings = _settings(tmp_path)

    with TestClient(_app(settings)) as client:
        response = _analyze(client, {INTERNAL_TOKEN_HEADER: TOKEN})

        assert response.status_code == 200
        assert response.json()["status"] == "OK"
        assert residue(settings) == []


def test_로그에_내부_토큰과_오디오_바이트가_남지_않는다(tmp_path, caplog):
    """미들웨어의 401 줄과 요청 종료 줄을 대상으로 본다 (KAN-203, 명세서 §2.6).

    ai 컨테이너 로그는 이제 호스트 디스크가 아니라 CloudWatch 로그 그룹에 보존 14일로 남는다
    (`infra/modules/ai-host`) - 토큰이나 오디오가 한 번 찍히면 그 창 동안 남는다. backend에는 출력
    직전의 마지막 관문(`LogMasking`, KAN-28)이 있지만 ai에는 그 층이 없고 "코드가 애초에 넣지 않는
    것"이 유일한 방어라, 그 성질을 이 테스트가 붙잡는다.
    """
    settings = _settings(tmp_path)

    with caplog.at_level(logging.INFO):
        with TestClient(_app(settings)) as client:
            assert _analyze(client, {INTERNAL_TOKEN_HEADER: TOKEN + "x"}).status_code == 401
            assert _analyze(client, {INTERNAL_TOKEN_HEADER: TOKEN}).status_code == 200

    # 두 줄이 실제로 찍혔는지 먼저 본다 - 아무것도 안 남았으면 아래 검사가 공짜로 통과한다
    assert "내부 호출 토큰 불일치" in caplog.text
    assert "분석 종료" in caplog.text

    assert TOKEN not in caplog.text, "내부 호출 토큰 원문이 로그에 있다"
    assert TOKEN[:12] not in caplog.text, "토큰 앞부분이 로그에 있다"
    assert "RIFF" not in caplog.text, "오디오 원문이 로그에 있다"
    # 십진수 바이트 목록 - docs/wiki/observability.md의 로그 샘플 검사와 같은 규칙이다
    assert re.search(r"[0-9]{1,3}(?:, ?[0-9]{1,3}){20,}", caplog.text) is None, "오디오 바이트 목록이 로그에 있다"


def test_metrics도_토큰을_요구한다(tmp_path):
    with TestClient(_app(_settings(tmp_path))) as client:
        assert client.get("/internal/v0/metrics").status_code == 401
        assert client.get("/internal/v0/metrics", headers={INTERNAL_TOKEN_HEADER: TOKEN}).status_code == 200


def test_health는_토큰_없이_열려_있다(tmp_path):
    # compose healthcheck와 호스트 상태 지표 프로브가 토큰 없이 두드린다
    with TestClient(_app(_settings(tmp_path))) as client:
        response = client.get("/internal/v0/health")

        assert response.status_code == 200
        assert response.json() == {"status": "UP"}


def test_토큰이_설정되지_않은_서버는_검사를_건너뛴다(tmp_path):
    # 로컬 개발 편의 - 배포에서는 Terraform이 언제나 값을 넣는다
    with TestClient(_app(_settings(tmp_path, token=None))) as client:
        assert _analyze(client, {}).status_code == 200


def test_배포_모드에서_토큰이_없으면_기동이_실패한다(tmp_path):
    # SSM에서 토큰이 빠진 채 뜨면 fail-open인데 health는 UP이라 아무도 모른다 - 기동을 세운다 (리뷰)
    required = Settings(temp_dir=tmp_path / "ai-tmp", internal_token_required=True)

    with pytest.raises(ValueError, match="ACCENTURY_AI_INTERNAL_TOKEN"):
        create_app(required)

    # 토큰이 있으면 그대로 뜬다
    with TestClient(_app(Settings(temp_dir=tmp_path / "ai-tmp",
                                  internal_token_required=True, internal_token=TOKEN))) as client:
        assert client.get("/internal/v0/health").status_code == 200


def test_required_플래그는_환경_변수_true_1_yes만_켠다():
    base = {"ACCENTURY_AI_INTERNAL_TOKEN": TOKEN}
    assert Settings.from_env(base).internal_token_required is False
    for value in ("true", "TRUE", "1", "yes"):
        assert Settings.from_env({**base, "ACCENTURY_AI_INTERNAL_TOKEN_REQUIRED": value}).internal_token_required
    assert Settings.from_env({**base, "ACCENTURY_AI_INTERNAL_TOKEN_REQUIRED": "false"}).internal_token_required is False


def _wait_health(client: TestClient, status: int, timeout: float = 3.0) -> bool:
    """워밍업은 뒤에서 도는 작업이라 잠시 기다린다."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if client.get("/internal/v0/health").status_code == status:
            return True
        time.sleep(0.02)
    return False


def test_워밍업_전에는_health가_503_STARTING이다(tmp_path):
    # with 블록 없이 만들면 lifespan이 돌지 않는다 - 프로세스는 떴지만 준비 전인 상태다
    client = TestClient(_app(_settings(tmp_path)))

    response = client.get("/internal/v0/health")

    assert response.status_code == 503
    assert response.json() == {"status": "STARTING"}


def test_엔진의_워밍업이_끝난_뒤에야_UP이다(tmp_path):
    class WarmingEngine(FakeEngine):
        def __init__(self) -> None:
            super().__init__()
            self.release = None  # 테스트가 풀어 주기 전까지 워밍업이 끝나지 않는다
            self.warmed = False

        async def warm_up(self) -> None:
            import asyncio

            self.release = asyncio.Event()
            await self.release.wait()
            self.warmed = True

    engine = WarmingEngine()
    app = create_app(_settings(tmp_path), engine=engine)
    assert TestClient(app).get("/internal/v0/health").status_code == 503

    with TestClient(app) as client:
        # 포트는 열렸고(lifespan 완료) 워밍업은 뒤에서 도는 중 - 이 창이 503 STARTING이다
        assert client.get("/internal/v0/health").json() == {"status": "STARTING"}
        assert engine.warmed is False
        client.portal.call(engine.release.set)
        assert _wait_health(client, 200), "워밍업이 끝나면 UP이어야 한다"
        assert engine.warmed is True
        assert client.get("/internal/v0/health").json() == {"status": "UP"}

    # 종료 뒤에는 다시 준비 전이다 - 재기동 사이에 UP을 내지 않는다
    assert TestClient(app).get("/internal/v0/health").status_code == 503


def test_동기_warm_up도_루프를_막지_않고_돈다(tmp_path):
    # 실모델 가중치 적재는 블로킹이다 - async가 아니어도 스레드로 넘겨 health가 그동안 STARTING을 낸다
    class BlockingEngine(FakeEngine):
        def __init__(self) -> None:
            super().__init__()
            self.warmed = False

        def warm_up(self) -> None:
            time.sleep(0.2)
            self.warmed = True

    engine = BlockingEngine()
    with TestClient(create_app(_settings(tmp_path), engine=engine)) as client:
        assert client.get("/internal/v0/health").status_code == 503
        assert _wait_health(client, 200)
        assert engine.warmed is True


def test_워밍업이_실패하면_준비_전에_머문다(tmp_path):
    class BrokenEngine(FakeEngine):
        async def warm_up(self) -> None:
            raise RuntimeError("가중치 파일 없음")

    with TestClient(create_app(_settings(tmp_path), engine=BrokenEngine())) as client:
        # 잠시 기다려도 UP이 되지 않는다 - compose healthcheck가 unhealthy로 두고 롤백한다
        assert _wait_health(client, 200, timeout=0.3) is False
        assert client.get("/internal/v0/health").json() == {"status": "STARTING"}
