"""엔진 계약 적합성 스위트의 픽스처와 엔진 프로파일 (KAN-137).

이 디렉터리의 테스트는 **엔진 종류를 모른다.** 스텁이 꽂혀 있든 실모델(KAN-22)이 꽂혀
있든 같은 본문이 돌고, 통과해야 하는 것도 같다. 그래서 여기 있는 검사가 실모델의
인수 조건이 된다 - 모델 자체는 다른 팀원이 만들지만 "서버에 붙였을 때 계약을 지키는가"의
판정은 이 스위트가 정본이다.

엔진마다 어쩔 수 없이 다른 것(느리게 만드는 방법, 판정 실패를 유도하는 문항 등)은 테스트
본문이 아니라 :data:`ENGINE_PROFILES` 표 한 줄에 모은다. 엔진을 갈아끼울 때 고치는 것은
이 표뿐이고 테스트 본문은 그대로다 (KAN-137 AC).

## 실행

기본값은 스텁이라 ``pytest``에 그냥 딸려 돈다. 실모델은 이름과 실오디오를 준다.

```bash
pytest tests/contract                                   # 스텁
pytest tests/contract --contract-engine=<이름> \
       --contract-audio=samples/1-5.wav                 # 실모델
```

**실모델로 돌릴 때 두 가지를 반드시 맞춘다.**

1. ``--contract-audio``에 실제 발화 WAV를 준다. 기본 픽스처는 합성 사인파라 실모델이
   판정 실패(422)를 낼 수 있고, 그러면 성공 경로 항목이 통째로 오탐한다.
2. ``ACCENTURY_AI_ANALYSIS_TIMEOUT_SECONDS``를 KAN-172 재조정값으로 올린다. 기본 30초와
   08-30 실측(14~30초)이 겹쳐 정상 분석이 503으로 끊기면 성공 경로 항목이 오탐한다.
   설정은 :meth:`app.config.Settings.from_env`로 읽으므로 환경 변수를 그대로 쓴다.
"""

from __future__ import annotations

import io
import json
import math
import time
import wave
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.engine import STUB_ENGINE, StubEngine
from app.main import create_app

#: 스위트가 쓰는 추적 ID의 기본값 (§2.2).
CORRELATION_ID = "c_contract"

#: 정의가 실모델에 넘기는 문장 참조 키 (KAN-182 계약, "1|5" 형식). 스텁은 무시한다.
SCRIPT_KEY = "1|5"

#: 세션이 고정한 점수 버전 - 응답에 그대로 에코백돼야 한다 (§5.4).
SCORE_VERSION = "sv-0.3"

#: 스텁 프로파일이 판정 실패(422)를 유도할 때 쓰는 문항.
STUB_FAIL_ITEM = "v-contract-fail"

#: 준비 상태(health UP)를 기다리는 상한. 스텁은 즉시 지나가고, 실모델은 가중치 적재
#: (KAN-22의 ``warm_up``)가 끝날 때까지 걸린다.
READY_TIMEOUT_SECONDS = 300.0


@dataclass(frozen=True)
class EngineProfile:
    """엔진 하나를 이 스위트에 태우기 위해 알아야 하는 것 전부.

    **테스트가 묻는 것이 아니라 엔진이 선언하는 것**만 여기 있다. 엔진을 더할 때 채우는
    표 한 줄이고, 채우지 않은 항목이 있으면 해당 검사는 사유와 함께 건너뛴다 - 조용히
    통과시키지 않는다.
    """

    #: :data:`app.config.Settings.analysis_engine`에 넣을 이름.
    name: str
    #: 정상 경로용 설정 덮어쓰기 - 스텁은 지연 흉내를 끈다.
    settings: dict[str, Any] = field(default_factory=dict)
    #: 분석을 예산 밖으로 밀어내는 설정 (503 항목). 실모델은 추론 자체가 예산보다 길어
    #: 덮어쓸 것이 없다 - 빈 표면 상한만 줄이면 된다.
    slow_settings: dict[str, Any] = field(default_factory=dict)
    #: 이 엔진이 보고해야 하는 ``modelVersion``. ``None``이면 "비어 있지 않다"까지만 본다.
    model_version: str | None = None
    #: 점수가 오디오에서 나오는가. 스텁은 해시라 ``False``이고, 그 경우 "같은 오디오면
    #: 추적 ID가 달라도 같은 점수" 항목을 건너뛴다 - 스텁에는 성립할 수 없는 명제다.
    score_depends_on_audio: bool = False
    #: 판정 실패(422)를 유도할 itemId. ``None``이면 그 항목을 건너뛴다.
    judged_failure_item: str | None = None
    #: ``scriptKey``가 없는 meta에 이 엔진이 하는 일 (KAN-182 계약).
    #:
    #: - ``"ignored"`` - 없어도 정상 분석한다 (스텁).
    #: - ``"rejected"`` - 정의된 거절 응답을 낸다 (비재전송 422 또는 400).
    #: - ``None`` - 아직 정하지 않았다. KAN-22가 정하면 채운다.
    missing_script_key: str | None = None


#: 엔진 이름 -> 프로파일. 실모델(KAN-22)은 여기 한 줄을 더한다.
ENGINE_PROFILES: dict[str, EngineProfile] = {
    STUB_ENGINE: EngineProfile(
        name=STUB_ENGINE,
        # 지연 흉내는 앱과 BE의 대기 화면을 시험하는 장치라 계약과 무관하다 - 스위트가
        # 거기에 시간을 쓰지 않게 끈다. 판정 실패 문항은 422 경로를 열기 위한 것이고,
        # 정상 요청의 itemId와 겹치지 않는 이름이라 다른 항목에 영향이 없다
        settings={"stub_delay_ms": 0, "stub_fail_item": STUB_FAIL_ITEM},
        # 스텁은 즉답이라 상한만 줄여서는 절대 초과하지 않는다 - 지연을 함께 켠다
        slow_settings={"stub_delay_ms": 500},
        model_version=StubEngine.MODEL_VERSION,
        score_depends_on_audio=False,
        judged_failure_item=STUB_FAIL_ITEM,
        missing_script_key="ignored",
    ),
}


def default_audio() -> bytes:
    """기본 오디오 픽스처 - 16kHz / Mono / 16-bit PCM WAV 1초 (§4.1 입력 규격).

    사인파다. 실모델이 점수를 낼 만한 발화가 아니므로 실모델 실행에는
    ``--contract-audio``로 실제 녹음을 준다 - 모듈 주석 참고. 스텁은 오디오를 한 바이트도
    보지 않으므로(해시 점수) 규격만 맞으면 된다.
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


def _contract_settings(profile: EngineProfile, temp_dir: Path, **overrides: Any) -> Settings:
    """스위트가 띄울 서버의 설정.

    환경 변수를 바탕에 깔고(실모델은 타임아웃 등을 거기서 받는다) 스위트가 반드시 쥐어야
    하는 것만 덮어쓴다. 내부 토큰은 끈다 - 인증은 이 스위트의 대상이 아니고(KAN-36에
    전용 테스트가 있다), 개발자 셸에 토큰이 떠 있으면 전 항목이 401로 죽는다.
    """
    base = Settings.from_env()
    return replace(
        base,
        temp_dir=temp_dir,
        analysis_engine=profile.name,
        internal_token=None,
        internal_token_required=False,
        **{**profile.settings, **overrides},
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
def profile(pytestconfig) -> EngineProfile:
    """이번 실행이 검사할 엔진의 프로파일.

    이름은 ``--contract-engine``이 우선이고, 없으면 환경 변수(``ACCENTURY_AI_ANALYSIS_ENGINE``)를
    따른다 - 서버를 띄울 때 고르는 방식과 같은 자리다.
    """
    name = pytestconfig.getoption("--contract-engine") or Settings.from_env().analysis_engine
    if name not in ENGINE_PROFILES:
        pytest.fail(
            f"프로파일이 없는 엔진이다: {name!r} "
            f"(등록된 이름: {', '.join(sorted(ENGINE_PROFILES))}). "
            "tests/contract/conftest.py의 ENGINE_PROFILES에 한 줄을 더한다"
        )
    return ENGINE_PROFILES[name]


@pytest.fixture(scope="session")
def audio(pytestconfig) -> bytes:
    """요청에 실을 오디오 - ``--contract-audio``가 없으면 합성 WAV다."""
    given = pytestconfig.getoption("--contract-audio")
    return Path(given).read_bytes() if given else default_audio()


@pytest.fixture(scope="session")
def settings(profile: EngineProfile, tmp_path_factory) -> Settings:
    return _contract_settings(profile, tmp_path_factory.mktemp("contract-tmp"))


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


@pytest.fixture(scope="session")
def slow_settings(profile: EngineProfile, tmp_path_factory) -> Settings:
    """분석이 반드시 예산을 넘도록 만든 설정 (503 항목).

    상한을 10ms로 줄인다 - 실모델의 추론은 어떤 값을 잡아도 이보다 길고, 스텁은
    프로파일이 함께 켜는 지연이 넘긴다.
    """
    return _contract_settings(
        profile,
        tmp_path_factory.mktemp("contract-slow-tmp"),
        analysis_timeout_seconds=0.01,
        **profile.slow_settings,
    )


class _SharedEngine:
    """이미 적재된 엔진을 두 번째 앱에 그대로 물린다.

    503 항목은 상한이 다른 서버가 필요하지만, 그 서버가 엔진을 새로 만들면 **모델이 두
    벌 올라간다** - 실모델은 RSS 7GB대(KAN-172의 08-30 실측)라 8GB짜리 AI 인스턴스
    (c7i.xlarge, KAN-36)에서 정상 서버와 겹치면 그대로 OOM이다 (Codex sol 리뷰 P1).

    ``warm_up``을 일부러 두지 않는다 - 감싼 엔진은 이미 워밍업이 끝났고, 두 번째 앱이
    이름을 보고 다시 부르면 적재를 반복하게 된다 (앱은 이름으로만 찾는다).
    """

    def __init__(self, engine: Any) -> None:
        self._engine = engine

    @property
    def model_version(self) -> str:
        return self._engine.model_version

    async def analyze(self, request: Any) -> Any:
        return await self._engine.analyze(request)


@pytest.fixture(scope="session")
def slow_client(
    slow_settings: Settings, client: TestClient, profile: EngineProfile
) -> TestClient:
    """예산 초과(503) 항목이 쓰는 서버.

    엔진을 새로 만드는 것은 **프로파일이 엔진 자체를 느리게 만들 때뿐**이다. 스텁이
    그렇다 - 즉답이라 지연 흉내를 켜야 예산을 넘긴다. 실모델은 추론이 이미 예산보다
    길어 ``slow_settings``가 비고, 그러면 정상 서버가 적재한 엔진을 그대로 물려 모델이
    한 벌만 올라간다 (Codex sol 리뷰 P1 - 두 벌이면 8GB 인스턴스에서 OOM이다).
    """
    engine = None if profile.slow_settings else _SharedEngine(client.app.state.engine)
    with TestClient(create_app(slow_settings, engine=engine)) as test_client:
        _await_ready(test_client)
        yield test_client
