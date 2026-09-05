"""엔진 계약 적합성 스위트 (KAN-137).

**이 스위트의 통과가 실모델(KAN-22)의 인수 조건이다.** 엔진이 무엇이든 여기 있는 항목은
전부 같은 답을 내야 한다 - 어느 하나가 깨지면 BE 쪽에서 무슨 일이 벌어지는지를 항목마다
주석에 적어 두었다. 계약이 추상적인 규칙이 아니라 BE의 구체적인 고장이기 때문이다.

엔진마다 다른 것은 :data:`tests.contract.conftest.ENGINE_PROFILES` 표에 있고, 이 파일의
본문은 엔진 이름을 모른다 - 엔진을 갈아끼울 때 여기는 고치지 않는다 (KAN-137 AC).

| 항목 | 근거 |
| --- | --- |
| 응답 봉투가 §4.1과 일치 | BE ``RestAiAnalysisClient``가 필드로 역직렬화한다 |
| 억양 점수 0~100 | §4.3 스케일, BE가 문항 20점으로 환산한다 |
| 반복 분석 오차 ±2점 | KAN-19 AC |
| 예산 초과 시 503 | §4.1, 임시파일 수명 상한 (KAN-27) |
| 입력 상한 초과 시 413 | §3.3과 같은 1MB |
| 응답 뒤 무잔존 | KAN-27 AC-1, NFR-PR-03 |
| scoreVersion 에코백 | §5.4 불일치 가드의 전제 |
| meta 형식 오류에 400 | 계약 위반은 BE 버그다 |
| scriptKey 없는 meta | KAN-182 계약, 엔진별 선언 |
"""

from __future__ import annotations

import pytest

from app.engine import JUDGED_QUALITY_CODES
from tests.contract.conftest import (
    SCORE_VERSION,
    EngineProfile,
    analyze,
    contract_meta,
    residue,
)

#: 성공 응답(200)의 필드 (§4.1). 정확히 이 집합이어야 한다 - 빠지면 BE가 계약 위반으로
#: 끊고, 더해지면 명세와 구현이 갈린 것이다.
OK_FIELDS = {
    "status",
    "intonationScore",
    "confidence",
    "quality",
    "segments",
    "scoreVersion",
    "modelVersion",
    "processingMs",
}

#: 판정 실패(422)의 필드 (§4.1) - 점수 자리는 없고 ``retryable``이 있다.
FAILED_FIELDS = {"status", "quality", "retryable", "modelVersion", "scoreVersion", "processingMs"}

#: §3.3이 정한 오디오 파트 상한 (1MB). BE가 앱 업로드를 이 값으로 끊고 AI도 같은 값이라
#: 정상 요청은 걸리지 않는다. **계약값이라 설정에서 읽지 않는다** - 설정이 어긋났다는
#: 사실 자체가 드러나야 한다.
CONTRACT_MAX_AUDIO_BYTES = 1_048_576


def _ok(response) -> dict:
    """성공 응답 하나를 꺼낸다 - 아니면 원인을 짚어 준다.

    실모델을 합성 오디오로 돌리면 판정 실패(422)가 나올 수 있고, 그것을 그냥 "200이
    아니다"로만 보고하면 계약이 깨진 것으로 오해하게 된다.
    """
    if response.status_code == 422:
        pytest.fail(
            "성공 경로에서 판정 실패(422)가 났다: "
            f"{response.json().get('quality')}. 실모델이면 --contract-audio에 실제 발화 "
            "WAV를 준다 (기본 픽스처는 합성 사인파다)"
        )
    assert response.status_code == 200, response.text
    return response.json()


def _score(response) -> int:
    return _ok(response)["intonationScore"]


def test_성공_응답이_명세_4_1_봉투다(client, settings, audio, profile: EngineProfile):
    # 필드가 빠지거나 타입이 어긋나면 BE는 성공 응답을 계약 위반으로 끊고 **그 거절을
    # 회로 차단기의 실패로 센다** - 엔진 하나의 버그가 서비스 전체의 회로를 여는 형태로
    # 번지는 자리다 (RestAiAnalysisClient.completed)
    body = _ok(analyze(client, audio))

    assert set(body) == OK_FIELDS, f"§4.1 봉투와 필드가 다르다: {sorted(set(body) ^ OK_FIELDS)}"
    assert body["status"] == "OK"
    # bool은 int의 하위 타입이라 따로 막는다 - True가 점수 1로 통과하면 안 된다
    assert isinstance(body["intonationScore"], int) and not isinstance(body["intonationScore"], bool)
    assert isinstance(body["confidence"], (int, float)) and not isinstance(body["confidence"], bool)
    assert isinstance(body["quality"], dict) and isinstance(body["quality"]["code"], str)
    assert body["quality"]["code"]
    assert isinstance(body["segments"], list)
    assert all(isinstance(segment, dict) for segment in body["segments"])
    assert isinstance(body["processingMs"], int) and body["processingMs"] >= 0
    # modelVersion이 비면 BE가 성공 응답을 끊는다 - 엔진이 자기 정체를 보고하는 값이고
    # 설정으로 덮어쓰지 않는다 (KAN-135)
    assert isinstance(body["modelVersion"], str) and body["modelVersion"]
    if profile.model_version is not None:
        assert body["modelVersion"] == profile.model_version

    assert residue(settings) == []


def test_억양_점수가_0_100_범위다(client, settings, audio):
    # §4.3 스케일이다. BE ScoreAggregator가 이 값을 문항 20점으로 환산하므로, 범위를
    # 벗어난 값은 등급 경계를 통째로 흔든다
    for index in range(3):
        score = _score(analyze(client, audio, correlation_id=f"c_range_{index}"))

        assert 0 <= score <= 100, f"억양 점수가 0~100 밖이다: {score}"

    assert residue(settings) == []


def test_동일_입력_반복_분석_오차가_2점_이내다(client, settings, audio):
    # KAN-19 AC. 같은 사람이 같은 녹음을 다시 보내 점수가 흔들리면 재응시(KAN-107)에서
    # 등급이 뒤집히고, BE의 재전송(KAN-24)도 시도마다 다른 점수를 저장하게 된다
    scores = [
        _score(analyze(client, audio, correlation_id="c_repeat")) for _ in range(3)
    ]

    assert max(scores) - min(scores) <= 2, f"반복 분석 오차가 2점을 넘는다: {scores}"

    assert residue(settings) == []


def test_같은_오디오면_추적_ID가_달라도_같은_점수다(
    client, settings, audio, profile: EngineProfile
):
    # 점수는 오디오에서 나와야 하고 요청 메타에서 나오면 안 된다. 스텁은 correlationId를
    # 해시하므로(KAN-136) 성립할 수 없는 명제라 프로파일이 이 항목을 끈다
    if not profile.score_depends_on_audio:
        pytest.skip(f"{profile.name} 엔진은 오디오로 점수를 내지 않는다 (프로파일 선언)")

    first = _score(analyze(client, audio, correlation_id="c_seed_a"))
    second = _score(analyze(client, audio, correlation_id="c_seed_b"))

    assert abs(first - second) <= 2, f"추적 ID만 바꿨는데 점수가 달라졌다: {first}, {second}"

    assert residue(settings) == []


def test_분석이_예산을_넘기면_503이다(slow_client, slow_settings, audio):
    # 503은 BE가 일시 장애로 보고 재전송 예산 안에서 다시 시도하는 신호다 (§4.1).
    #
    # 이 항목은 상한이 걸리는지만 보는 것이 아니라 **엔진이 이벤트 루프를 막지 않는지**를
    # 본다 (app.engine.AnalysisEngine.analyze의 계약 1). 동기 추론을 async def 안에서
    # 그대로 돌리면 asyncio.timeout이 끼어들 틈이 없어 여기가 200으로 떨어지고, 그때는
    # 임시파일 수명과 서버 전체의 응답성이 그 추론에 묶인다
    response = analyze(slow_client, audio, correlation_id="c_timeout")

    assert response.status_code == 503, response.text
    assert response.json()["status"] == "FAILED"
    assert residue(slow_settings) == []
    # **취소가 실제로 엔진에 닿았는지는 이 스위트가 보지 못한다** (계약 2, Codex sol 리뷰 P1).
    # asyncio.to_thread로 넘긴 추론은 503 뒤에도 계속 돌지만 HTTP 밖에서는 관측되지
    # 않는다. 엔진이 "진행 중 추론 수"를 보고하는 훅을 두면(KAN-22) 그때 항목을 더한다.
    # 여기서 확인할 수 있는 데까지는 확인한다 - 끊긴 뒤에도 서버가 계속 요청을 받는다
    assert slow_client.get("/internal/v0/health").json()["status"] == "UP"


def test_오디오_상한을_넘으면_413이다(client, settings):
    # BE가 §3.3에서 이미 끊지만, 사설망이라고 무한정 받아 디스크를 채우게 두지 않는다.
    # 엔진에 닿기 전에 끊는 자리라 엔진 종류와 무관하게 같은 답이어야 한다.
    #
    # 상한을 설정에서 유도하지 않는다 (Codex sol 리뷰 P2) - 그러면 설정이 512KB든 10MB든
    # "설정값 + 1"은 늘 413이라 어긋난 설정이 그대로 통과한다. §3.3이 정한 1MB를 직접
    # 쓰고, 서버가 그 값으로 떠 있는지도 함께 본다
    assert settings.max_audio_bytes == CONTRACT_MAX_AUDIO_BYTES, (
        f"오디오 상한이 §3.3의 1MB가 아니다: {settings.max_audio_bytes} "
        "(ACCENTURY_AI_MAX_AUDIO_BYTES를 확인한다)"
    )
    oversized = b"\x00" * (CONTRACT_MAX_AUDIO_BYTES + 1)

    response = analyze(client, oversized, correlation_id="c_oversized")

    assert response.status_code == 413, response.text
    assert response.json()["status"] == "FAILED"
    assert residue(settings) == []


@pytest.mark.parametrize(
    "broken_meta",
    # meta 파트 자체가 없는 요청은 여기 넣지 않는다 - FastAPI의 폼 검증이 먼저 422로
    # 끊는 자리라 이 라우트의 계약이 아니고, BE는 meta를 늘 채워 보낸다
    ["not-json", "[]", '"문자열"', " ", "{"],
    ids=["json이_아님", "배열", "문자열", "공백만", "잘린_객체"],
)
def test_meta_형식_오류는_400이다(client, settings, audio, broken_meta: str):
    # 계약 위반은 BE 버그다 - 오디오를 읽기도 전에 끊는다. 5xx로 내면 BE가 일시 장애로
    # 읽고 같은 잘못된 요청을 재전송 예산이 마를 때까지 반복한다
    response = analyze(client, audio, meta_json=broken_meta)

    assert response.status_code == 400, response.text
    assert response.json()["status"] == "FAILED"
    assert residue(settings) == []


def test_scoreVersion을_그대로_에코백한다(client, settings, audio):
    # 세션이 고정한 점수 버전이다. 엔진이 제 버전을 지어내면 BE가 §5.4 불일치로 끊어
    # 전 요청이 INTERNAL_ERROR가 된다 - 받은 것을 그대로 되돌리는 것이 계약이다
    body = _ok(
        analyze(
            client,
            audio,
            meta_json=contract_meta(correlation_id="c_echo", score_version="sv-9.9"),
        )
    )

    assert body["scoreVersion"] == "sv-9.9"
    assert residue(settings) == []


def test_판정_실패가_2_4_계약을_지킨다(client, settings, audio, profile: EngineProfile):
    # 요청은 정상인데 점수를 낼 수 없는 경우다 (§4.1의 422). BE는 §2.4 ErrorCode에 없는
    # 이름을 받으면 계약 위반으로 끊고 그 문항은 재시도 없이 죽는다 - 서술적인 코드를
    # 지어내면 사용자가 문항을 잃는다
    if profile.judged_failure_item is None:
        pytest.skip(f"{profile.name} 엔진은 판정 실패를 유도할 문항이 없다 (프로파일 미선언)")

    response = analyze(
        client, audio, item_id=profile.judged_failure_item, correlation_id="c_judged"
    )

    assert response.status_code == 422, response.text
    body = response.json()
    assert set(body) == FAILED_FIELDS, f"§4.1 봉투와 필드가 다르다: {sorted(set(body) ^ FAILED_FIELDS)}"
    assert body["status"] == "FAILED"
    assert body["quality"]["code"] in JUDGED_QUALITY_CODES
    # 문자열 "yes"는 JSON으로 멀쩡히 나가고 BE의 Boolean 역직렬화에서 터진다
    assert isinstance(body["retryable"], bool)
    assert body["scoreVersion"] == SCORE_VERSION
    assert residue(settings) == []


def test_scriptKey가_없는_meta를_프로파일대로_다룬다(
    client, settings, audio, profile: EngineProfile
):
    # 실모델은 scriptKey로 문장을 찾는다 (KAN-182 계약). 정의에 scriptKey가 없으면 BE가
    # 필드를 생략해 보내므로, 그 meta에 엔진이 무엇을 하는지가 계약의 일부다.
    # 스텁은 무시하고(회귀 없음) 실모델의 거절 방식은 KAN-22가 정한다
    expected = profile.missing_script_key
    if expected is None:
        pytest.skip(
            f"{profile.name} 엔진의 scriptKey 없는 meta 처리가 아직 정해지지 않았다 "
            "(KAN-22가 정하면 ENGINE_PROFILES의 missing_script_key를 채운다)"
        )

    response = analyze(
        client, audio, meta_json=contract_meta(correlation_id="c_no_script", script_key=None)
    )

    if expected == "ignored":
        body = _ok(response)
        assert 0 <= body["intonationScore"] <= 100
    else:
        # 정의된 거절 - 비재전송 FAILED(422)이거나 400이다. 재전송 가능으로 내면 BE가
        # 예산이 마를 때까지 같은 요청을 반복한다 (정의가 바뀌지 않는 한 결과도 같다)
        assert response.status_code in (400, 422), response.text
        body = response.json()
        assert body["status"] == "FAILED"
        if response.status_code == 422:
            assert body["retryable"] is False
            assert body["quality"]["code"] in JUDGED_QUALITY_CODES

    assert residue(settings) == []


def test_모든_항목을_돌린_뒤에도_잔존_파일이_없다(client, settings):
    # 항목마다 디렉터리를 보지만 서버 자신의 계수도 함께 본다 - 삭제에 실패한 파일이
    # 있으면 tempDeleteFailures가 오른다 (KAN-38이 이 지표를 소비한다)
    metrics = client.get("/internal/v0/metrics").json()

    assert metrics["tempFiles"] == 0
    assert metrics["tempDeleteFailures"] == 0
    assert residue(settings) == []
