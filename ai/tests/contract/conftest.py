"""엔진 계약 적합성 스위트의 픽스처 (KAN-137).

이 디렉터리의 테스트는 **엔진 구현을 모른다.** 여기 있는 검사가 실모델(KAN-22)의 인수
조건이고, 모델 자체는 다른 팀원이 만들지만 "서버에 붙였을 때 계약을 지키는가"의 판정은
이 스위트가 정본이다.

2026-09-05에 스텁이 사라지면서 엔진 프로파일 표(``ENGINE_PROFILES``)와 ``--contract-engine``
옵션을 접었다 (KAN-22). 고를 엔진이 하나뿐이면 표는 목적을 잃고, 값이 테스트 본문에서 두 번
꺾이는 것보다 한 번 적히는 편이 읽힌다. 남은 것은 검증 항목 11개이고 그것이 이 스위트의
본체다.

## 실행

이 스위트는 **전달본이 들어 있는 이미지 안에서만** 돈다 (모델과 참조 215MB, Whisper
가중치 2.8GB가 그 안에 있다). 전달본 모듈이 없는 개발 기계에서는 사유와 함께 건너뛴다.

```bash
docker run --rm --platform linux/amd64 -v "$PWD:/src" -w /src \
    -e ACCENTURY_AI_ANALYSIS_TIMEOUT_SECONDS=600 accentury-ai:dev \
    sh -c "pip install -q --user pytest httpx && \
           python -m pytest tests/contract --contract-audio=/src/samples/1-5.wav"
```

운영 이미지에는 pytest가 없어 컨테이너 안에서 ``--user``로 깔고 돈다 (비루트라 시스템
site-packages에는 못 쓴다). 자세한 것은 ``ai/README.md``의 같은 절이다.

**두 가지를 맞추지 않으면 계약과 무관한 이유로 항목이 깨진다.**

1. ``--contract-audio``에 실제 발화 WAV를 준다. 기본 픽스처는 합성 사인파라 내용 게이트
   (§4-2)에서 판정 실패로 떨어지고, 그러면 성공 경로 항목이 통째로 오탐한다. 파일은
   문장 목록의 ``scriptKey``(아래 :data:`SCRIPT_KEY`)를 읽은 녹음이어야 한다.
2. ``ACCENTURY_AI_ANALYSIS_TIMEOUT_SECONDS``가 정상 추론(08-30 실측 14~30초)보다 넉넉해야
   한다. 기본값 90초면 되지만 느린 기계에서는 올린다 - 설정은
   :meth:`app.config.Settings.from_env`로 읽으므로 환경 변수를 그대로 쓴다.
"""

from __future__ import annotations

import io
import json
import math
import time
import wave
from contextlib import contextmanager
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

#: 스위트가 쓰는 추적 ID의 기본값 (§2.2).
CORRELATION_ID = "c_contract"

#: 정의가 실모델에 넘기는 문장 참조 키 (KAN-182 계약, "1|5" 형식).
#:
#: 전달본의 문장 목록(``handoff_sentences_*.json``)에 있는 값이어야 한다 - 없는 키는
#: 서비스 문장이 아니라 거절되고, 그러면 성공 경로 항목이 통째로 오탐한다.
SCRIPT_KEY = "1|5"

#: 세션이 고정한 점수 버전 - 응답에 그대로 에코백돼야 한다 (§5.4).
SCORE_VERSION = "sv-0.3"

#: 엔진이 보고해야 하는 ``modelVersion``의 앞머리 (전달본의 ``Track1Scorer.model_version``).
#:
#: 값을 통째로 박지 않는 이유는 그 뒤에 참조 stamp와 코드 해시, Whisper 저장소 이름이
#: 붙어 모델 이미지마다 달라지기 때문이다. 여기서 보는 것은 "설정이 부른 이름이 아니라
#: 엔진이 실제로 적재한 것을 보고한다"까지다 (KAN-135).
MODEL_VERSION_PREFIX = "track1-"

#: 준비 상태(health UP)를 기다리는 상한 - 실모델은 가중치 적재(KAN-22의 ``warm_up``)가
#: 끝날 때까지 걸린다.
READY_TIMEOUT_SECONDS = 300.0


def default_audio() -> bytes:
    """기본 오디오 픽스처 - 16kHz / Mono / 16-bit PCM WAV 1초 (§4.1 입력 규격).

    사인파다. 점수를 낼 만한 발화가 아니라 내용 게이트에서 판정 실패로 떨어지므로, 성공
    경로를 보려면 ``--contract-audio``로 실제 녹음을 준다 (모듈 주석). 뒤집으면 이 사인파는
    **판정 실패(422)를 유도하는 확실한 수단**이고, 그 항목이 실제로 그렇게 쓴다.
    """
    frame_rate = 16000
    frames = bytearray()
    for index in range(frame_rate):
        sample = int(12000 * math.sin(2 * math.pi * 220 * index / frame_rate))
        # 바이트 순서를 손으로 정한다 - WAV의 PCM은 리틀 엔디언이고, 플랫폼을 따라가는
        # 표현을 쓰면 빅 엔디언 기계에서 잡음이 담긴 파일이 된다
        frames += sample.to_bytes(2, "little", signed=True)
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(frame_rate)
        wav.writeframes(bytes(frames))
    return buffer.getvalue()


def contract_meta(
    item_id: str = "v1",
    *,
    correlation_id: str | None = CORRELATION_ID,
    script_key: str | None = SCRIPT_KEY,
    score_version: str = SCORE_VERSION,
) -> str:
    """BE가 보내는 meta 파트 (§4.1, KAN-182의 ``scriptKey`` 포함).

    ``script_key=None``이면 키 자체를 뺀다 - 정의에 scriptKey가 없는 문항을 BE가 그렇게
    보낸다 (``RestAiAnalysisClient``의 ``AnalyzeMeta``).
    """
    payload: dict[str, Any] = {}
    if correlation_id is not None:
        payload["correlationId"] = correlation_id
    payload["itemId"] = item_id
    if script_key is not None:
        payload["scriptKey"] = script_key
    payload.update(
        {
            "testVersion": "gn-2026.09.1",
            "scoreVersion": score_version,
            "durationMs": 3420,
        }
    )
    return json.dumps(payload)


def analyze(
    client: TestClient,
    audio: bytes,
    *,
    meta_json: str | None = None,
    item_id: str = "v1",
    correlation_id: str = CORRELATION_ID,
):
    """``POST /internal/v0/analyze`` 한 번 - BE가 보내는 모양 그대로다."""
    body = contract_meta(item_id, correlation_id=correlation_id) if meta_json is None else meta_json
    return client.post(
        "/internal/v0/analyze",
        files={"audio": ("recording.wav", audio, "audio/wav")},
        data={"meta": body},
        headers={"X-Correlation-Id": correlation_id},
    )


def residue(settings: Settings) -> list[str]:
    """임시 디렉터리에 남은 것 - 어느 종료 경로에서도 비어 있어야 한다 (KAN-27)."""
    return sorted(entry.name for entry in settings.temp_dir.iterdir())


def _contract_settings(temp_dir: Path, **overrides: Any) -> Settings:
    """스위트가 띄울 서버의 설정.

    환경 변수를 바탕에 깔고(엔진은 타임아웃과 전달본 경로를 거기서 받는다) 스위트가 반드시
    쥐어야 하는 것만 덮어쓴다. 내부 토큰은 끈다 - 인증은 이 스위트의 대상이 아니고(KAN-36에
    전용 테스트가 있다), 개발자 셸에 토큰이 떠 있으면 전 항목이 401로 죽는다.
    """
    base = Settings.from_env()
    return replace(
        base,
        temp_dir=temp_dir,
        internal_token=None,
        internal_token_required=False,
        **overrides,
    )


def _await_ready(client: TestClient) -> None:
    """health가 UP이 될 때까지 기다린다 (§4.2).

    실모델은 가중치를 다 적재해야 UP이다 (KAN-22의 ``warm_up``). 기다리지 않고 쏘면
    적재 중인 모델에 요청이 들어가 계약과 무관한 이유로 항목이 깨진다.
    """
    deadline = time.monotonic() + READY_TIMEOUT_SECONDS
    while True:
        body = client.get("/internal/v0/health").json()
        if body.get("status") == "UP":
            return
        if time.monotonic() > deadline:
            pytest.fail(
                f"{READY_TIMEOUT_SECONDS:.0f}초 안에 준비되지 않았다 (마지막 상태: {body})"
            )
        time.sleep(0.05)


@pytest.fixture(scope="session")
def transfer_image() -> None:
    """전달본이 있는 이미지 안에서만 이 스위트를 돌린다.

    건너뛰는 것과 통과하는 것을 가른다 - 모델이 없는 기계에서 조용히 초록이 되면, 계약을
    아무도 검사하지 않은 실행과 계약을 지킨 실행이 리포트에서 같아 보인다.
    """
    source = Settings.from_env().track1_src_dir / "scoring" / "serve.py"
    if not source.exists():
        pytest.skip(
            f"전달본 모듈이 없다 ({source}) - 이 스위트는 accentury/ai 이미지 안에서 돈다 "
            "(KAN-22, 모듈 주석의 실행 방법 참고)"
        )


@pytest.fixture(scope="session")
def audio(pytestconfig) -> bytes:
    """요청에 실을 오디오 - ``--contract-audio``가 없으면 합성 WAV다."""
    given = pytestconfig.getoption("--contract-audio")
    return Path(given).read_bytes() if given else default_audio()


@pytest.fixture(scope="session")
def settings(transfer_image, tmp_path_factory) -> Settings:
    return _contract_settings(tmp_path_factory.mktemp("contract-tmp"))


@pytest.fixture(scope="session")
def client(settings: Settings) -> TestClient:
    """정상 경로용 서버 - 세션에 하나만 띄운다.

    실모델은 기동마다 가중치를 적재하므로(RSS 7GB대, KAN-172의 08-30 실측) 항목마다
    새로 띄우면 스위트가 몇 분씩 늘어난다. 설정이 달라야 하는 항목(503)만 자기 서버를
    따로 띄운다.
    """
    with TestClient(create_app(settings)) as test_client:
        _await_ready(test_client)
        yield test_client


@contextmanager
def budget(client: TestClient, seconds: float):
    """이 블록 안에서만 분석 상한을 바꾼다 (503 항목).

    **서버를 하나 더 띄우지 않는다.** :class:`TestClient`는 컨텍스트마다 자기 이벤트 루프를
    돌리는데, 실모델 엔진은 워커 프로세스의 파이프와 태스크를 처음 띄운 루프에 묶어 두므로
    두 번째 클라이언트로 같은 엔진을 부르면 상한이 발화하기도 전에 다른 루프에서 스트림을
    기다렸다는 오류로 끊긴다 (Codex sol 리뷰 P2). 엔진을 새로 만드는 길도 없다 - 모델이 두
    벌 올라가 8GB 인스턴스에서 OOM이다 (KAN-36의 c7i.xlarge).

    라우트는 요청마다 ``app.state.settings``를 읽으므로(``app.analyze``) 그 자리를 잠시
    바꾸는 것으로 충분하고, 임시 디렉터리도 그대로라 잔존 검사가 같은 곳을 본다.
    """
    original = client.app.state.settings
    client.app.state.settings = replace(original, analysis_timeout_seconds=seconds)
    try:
        yield
    finally:
        client.app.state.settings = original
