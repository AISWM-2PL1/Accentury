"""엔진 계약 적합성 스위트 (KAN-137).

**이 스위트의 통과가 실모델(KAN-22)의 인수 조건이다.** 엔진이 무엇이든 여기 있는 항목은
전부 같은 답을 내야 한다 - 어느 하나가 깨지면 BE 쪽에서 무슨 일이 벌어지는지를 항목마다
주석에 적어 두었다. 계약이 추상적인 규칙이 아니라 BE의 구체적인 고장이기 때문이다.

엔진이 하나가 되면서 프로파일 표는 접었다 (KAN-22) - 항목마다 무엇을 기대하는지가 이제
본문에 그대로 적혀 있다. 표를 되살릴 때는 엔진이 둘 이상이 되는 날이다.

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
    MODEL_VERSION_PREFIX,
    SCORE_VERSION,
    analyze,
    budget,
    contract_meta,
    default_audio,
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


def test_성공_응답이_명세_4_1_봉투다(client, settings, audio):
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
    # 적재 전의 자리표시자가 아니라 엔진이 실제로 올린 가중치의 버전이어야 한다 (KAN-135)
    assert body["modelVersion"].startswith(MODEL_VERSION_PREFIX), body["modelVersion"]

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


def test_같은_오디오면_추적_ID가_달라도_같은_점수다(client, settings, audio):
    # 점수는 오디오에서 나와야 하고 요청 메타에서 나오면 안 된다 - 추적 ID가 점수에
    # 섞이면 같은 녹음의 재전송(KAN-24)마다 다른 점수가 저장된다
    first = _score(analyze(client, audio, correlation_id="c_seed_a"))
    second = _score(analyze(client, audio, correlation_id="c_seed_b"))

    assert abs(first - second) <= 2, f"추적 ID만 바꿨는데 점수가 달라졌다: {first}, {second}"

    assert residue(settings) == []


def test_분석이_예산을_넘기면_503이다(client, settings, audio):
    # 503은 BE가 일시 장애로 보고 재전송 예산 안에서 다시 시도하는 신호다 (§4.1).
    #
    # 이 항목은 상한이 걸리는지만 보는 것이 아니라 **엔진이 이벤트 루프를 막지 않는지**를
    # 본다 (app.engine.AnalysisEngine.analyze의 계약 1). 동기 추론을 async def 안에서
    # 그대로 돌리면 asyncio.timeout이 끼어들 틈이 없어 여기가 200으로 떨어지고, 그때는
    # 임시파일 수명과 서버 전체의 응답성이 그 추론에 묶인다.
    #
    # **이 항목이 도는 동안 워커가 죽는다** - 취소가 실제로 닿는다는 것이 계약이기
    # 때문이다 (app.track1). 뒤이은 항목의 첫 요청은 재적재를 기다렸다가 정상으로 돌아온다
    with budget(client, 0.01):
        response = analyze(client, audio, correlation_id="c_timeout")

    assert response.status_code == 503, response.text
    assert response.json()["status"] == "FAILED"
    assert residue(settings) == []
    # 취소가 워커까지 닿았는지는 HTTP 밖이라 여기서 보지 못한다 - 그 검사는 어댑터의
    # 단위 테스트가 프로세스 pid로 한다 (tests/test_track1.py). 여기서 확인할 수 있는
    # 데까지는 확인한다: 끊긴 뒤에도 서버가 계속 요청을 받는다
    assert client.get("/internal/v0/health").json()["status"] == "UP"


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


def test_판정_실패가_2_4_계약을_지킨다(client, settings):
    # 요청은 정상인데 점수를 낼 수 없는 경우다 (§4.1의 422). BE는 §2.4 ErrorCode에 없는
    # 이름을 받으면 계약 위반으로 끊고 그 문항은 재시도 없이 죽는다 - 서술적인 코드를
    # 지어내면 사용자가 문항을 잃는다.
    #
    # 합성 사인파를 보내 유도한다 - 대본을 읽은 발화가 아니므로 내용 게이트(§4-2)가 잡는다.
    # ``--contract-audio``와 무관하게 늘 같은 입력이라 이 항목만은 재현이 보장된다
    response = analyze(client, default_audio(), correlation_id="c_judged")

    assert response.status_code == 422, response.text
    body = response.json()
    assert set(body) == FAILED_FIELDS, f"§4.1 봉투와 필드가 다르다: {sorted(set(body) ^ FAILED_FIELDS)}"
    assert body["status"] == "FAILED"
    assert body["quality"]["code"] in JUDGED_QUALITY_CODES
    # 문자열 "yes"는 JSON으로 멀쩡히 나가고 BE의 Boolean 역직렬화에서 터진다
    assert isinstance(body["retryable"], bool)
    assert body["scoreVersion"] == SCORE_VERSION
    assert residue(settings) == []


def test_scriptKey가_없는_meta는_비재전송으로_거절된다(client, settings, audio):
    # 실모델은 scriptKey로만 문장을 찾는다 (KAN-182 계약, 문항 번호로는 못 찾는다). 정의에
    # scriptKey가 없으면 BE가 필드를 생략해 보내므로, 그 meta에 엔진이 무엇을 하는지가
    # 계약의 일부다.
    #
    # 2026-09-05 결정: 비재전송 판정 실패(422)다. 무시하고 분석하면 대본 없이 채점한
    # 점수가 정상값처럼 나가고, 재전송 가능으로 내면 정의가 바뀌지 않는 한 결과가 같은
    # 요청을 BE가 예산이 마를 때까지 반복한다
    response = analyze(
        client, audio, meta_json=contract_meta(correlation_id="c_no_script", script_key=None)
    )

    assert response.status_code in (400, 422), response.text
    body = response.json()
    assert body["status"] == "FAILED"
    if response.status_code == 422:
        assert body["retryable"] is False
        assert body["quality"]["code"] in JUDGED_QUALITY_CODES

    assert residue(settings) == []


def test_모든_항목을_돌린_뒤에도_잔존_파일이_없다(client, settings):
    # 디렉터리를 직접 보는 것이 정본이다 - 이것이 비어 있으면 어느 종료 경로도 오디오를
    # 남기지 않았다는 뜻이다 (KAN-27 AC-1)
    assert residue(settings) == []

    # 서버 자신의 계수도 함께 본다 - 삭제나 훑기에 실패한 것이 있으면 여기가 오른다
    # (KAN-38이 이 지표를 소비한다).
    #
    # ``tempFiles``는 보지 않는다. 그 값은 **마지막 스윕 시점의 스냅샷**이고(app.tempstore),
    # 스윕은 진행 중인 요청의 임시파일과 정렬 작업 폴더도 잔존으로 센다 - 실모델은 분석
    # 1건이 수십 초라 5분마다 도는 스윕이 그 한가운데 떨어지면 이 값이 1이 된다. 그것은
    # 잔여물이 아니라 그 순간 살아 있던 요청이다 (2026-09-05 실모델 실행에서 확인).
    metrics = client.get("/internal/v0/metrics").json()

    assert metrics["tempDeleteFailures"] == 0
    assert metrics["tempScanFailures"] == 0
