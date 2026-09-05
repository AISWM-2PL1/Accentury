"""``POST /internal/v0/analyze``의 종료 경로별 무잔존 명세 (KAN-27 AC-1, §4.1).

점수 자체는 여기서 보지 않는다 - 엔진은 가짜다. 여기서 지키는 것은 "응답을 돌려준 뒤
서버에 오디오가 남지 않는다"와 응답 봉투가 §4.1 계약을 지킨다는 것 둘이고, 둘 다 엔진
종류와 무관해야 한다 (KAN-135). 엔진 자체의 계약은 tests/contract가 본다 (KAN-137).
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from tests.conftest import FAIL_ITEM, FAKE_MODEL_VERSION, FakeEngine, meta, post, residue


#: 본문을 직접 조립할 때 쓰는 경계 문자열 - httpx가 붙여 주는 것을 쓸 수 없는 경우가 있다
_BOUNDARY = "accentury-test-boundary"


def _multipart_body(audio: bytes, meta_json: str) -> bytes:
    """multipart 본문을 손으로 만든다 - Content-Length 없이 흘려보내기 위해서다."""
    head = (
        f"--{_BOUNDARY}\r\n"
        'Content-Disposition: form-data; name="audio"; filename="recording.wav"\r\n'
        "Content-Type: audio/wav\r\n\r\n"
    ).encode()
    tail = (
        f"\r\n--{_BOUNDARY}\r\n"
        'Content-Disposition: form-data; name="meta"\r\n\r\n'
        f"{meta_json}\r\n"
        f"--{_BOUNDARY}--\r\n"
    ).encode()
    return head + audio + tail


def test_성공_응답_뒤에_오디오가_남지_않는다(client, settings):
    response = post(client)

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "OK"
    # 여기서 볼 것은 봉투가 §4.3 스케일을 지키는지까지다
    assert isinstance(body["intonationScore"], int)
    assert 0 <= body["intonationScore"] <= 100
    # 설정이 아니라 엔진이 보고한 값이다 (KAN-135)
    assert body["modelVersion"] == FAKE_MODEL_VERSION
    # 세션이 고정한 점수 버전을 되돌려준다 - 다르면 BE가 계약 위반으로 끊는다 (§5.4)
    assert body["scoreVersion"] == "sv-0.3"
    assert residue(settings) == []


def test_판정_실패_뒤에도_오디오가_남지_않는다(client, settings):
    response = post(client, item_id=FAIL_ITEM)

    assert response.status_code == 422
    body = response.json()
    assert body["status"] == "FAILED"
    assert body["quality"]["code"] == "AUDIO_TOO_QUIET"
    assert body["retryable"] is True
    assert residue(settings) == []


def test_meta_형식_오류는_400이고_오디오를_저장하지_않는다(client, settings):
    response = post(client, meta_json="not-json")

    assert response.status_code == 400
    assert residue(settings) == []


def test_추론_중_예외가_나도_오디오가_남지_않는다(settings):
    # 예외 경로 - 종료 처리가 정상 반환에만 달려 있으면 여기서 파일이 남는다.
    # 터지는 쪽을 가짜 엔진으로 두는 것은 이 보장이 엔진 종류와 무관해야 하기 때문이다 (KAN-135)
    engine = FakeEngine(error=RuntimeError("추론 실패 시뮬레이션"))

    with TestClient(create_app(settings, engine=engine)) as client:
        with pytest.raises(RuntimeError):
            post(client)

    assert residue(settings) == []


def test_본문_상한을_넘으면_파싱_전에_413이다(tmp_path):
    # 파싱이 끝난 뒤에 재면 "받아 놓고 거절"이라 디스크가 먼저 찬다 (Codex sol 리뷰 P2)
    settings = Settings(temp_dir=tmp_path / "ai-tmp", max_request_bytes=64)

    with TestClient(create_app(settings, engine=FakeEngine())) as client:
        response = post(client)

        assert response.status_code == 413
        assert response.json()["status"] == "FAILED"
        assert residue(settings) == []


def test_길이를_선언하지_않아도_상한을_넘으면_413이다(tmp_path):
    # Content-Length 없이 흘려보내는 경로 - 흘러온 바이트를 세다 끊지만, 그 예외는
    # 폼 파싱 도중이라 FastAPI가 삼키고 400 "error parsing the body"로 바꾼다.
    # send 쪽에서 갈아끼우지 않으면 §4.1의 413 봉투가 영영 나가지 않는다 (Codex 리뷰)
    settings = Settings(temp_dir=tmp_path / "ai-tmp", max_request_bytes=64)
    body = _multipart_body(b"x" * 1024, meta())

    with TestClient(create_app(settings, engine=FakeEngine())) as client:
        response = client.post(
            "/internal/v0/analyze",
            # 이터레이터로 주면 httpx가 Content-Length 없이 chunked로 보낸다
            content=(body[at:at + 128] for at in range(0, len(body), 128)),
            headers={"Content-Type": f"multipart/form-data; boundary={_BOUNDARY}"},
        )

        assert response.status_code == 413
        assert response.json()["status"] == "FAILED"
        assert residue(settings) == []


def test_상한을_넘는_오디오는_413이고_남지_않는다(tmp_path):
    # BE가 §3.3에서 이미 끊지만, 사설망이라고 무한정 받아 디스크를 채우게 두지 않는다
    settings = Settings(temp_dir=tmp_path / "ai-tmp", max_audio_bytes=16)

    with TestClient(create_app(settings, engine=FakeEngine())) as client:
        response = client.post(
            "/internal/v0/analyze",
            files={"audio": ("recording.wav", b"x" * 1024, "audio/wav")},
            data={"meta": meta()},
        )

        assert response.status_code == 413
        assert residue(settings) == []


def test_분석이_예산을_넘기면_503이고_오디오가_남지_않는다(tmp_path):
    # 멈춘 추론을 그대로 두면 임시파일 수명이 무한해진다 (Codex sol 리뷰 P1).
    # 503은 BE가 일시 장애로 보고 재전송 예산 안에서 다시 시도하는 신호다 (§4.1)
    settings = Settings(temp_dir=tmp_path / "ai-tmp", analysis_timeout_seconds=0.01)

    with TestClient(create_app(settings, engine=FakeEngine(delay_seconds=0.5))) as client:
        response = post(client)

        assert response.status_code == 503
        assert residue(settings) == []


def test_메트릭이_잔존_파일_수를_돌려준다(client):
    body = client.get("/internal/v0/metrics").json()

    assert body["tempFiles"] == 0
    assert body["tempDeleteFailures"] == 0
    # 기동 시 한 번 훑는다 - kill 뒤 재시작한 프로세스가 곧바로 잔여물을 본다
    assert body["tempSweeps"] >= 1


def test_헬스체크가_뜬다(client):
    assert client.get("/internal/v0/health").json() == {"status": "UP"}
