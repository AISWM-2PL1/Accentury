"""스텁 점수 분산의 명세 (KAN-136).

고정 75점으로는 종합 점수가 50.0~83.3에만 놓인다 - 억양이 상수라 종합
``(억양x2 + 단어)/3``이 단어 점수(0, 20, 40, 60, 80, 100)에만 좌우되기 때문이다. 5등급 중
WANNABE, HONORARY, NATIVE 셋만 나오고 OUTSIDER와 TRAVELER는 **구조적으로** 도달할 수
없어서, 결과 화면 5종(KAN-29)과 공유 카드 5종(KAN-30)을 데모할 방법이 없었다.

여기서 지키는 것은 넷이다.

1. **결정성** - 같은 correlationId는 언제나 같은 점수다. BE의 재전송 멱등(§4.1)과 E2E
   재현성(KAN-138)이 여기 걸려 있다. 프로세스를 다시 띄워도 같아야 하므로 알려진
   값으로 못 박는다.
2. **분포** - 0~100을 고르게 덮는다. 문항 5개 원점수의 평균이 곧 억양 점수라
   (BE ``ScoreAggregator``), 한쪽으로 쏠리면 세션 점수도 같이 쏠린다.
3. **도달 가능성** - 세션을 반복하면 5등급이 전부 관측된다.
4. **기존 동작 보존** - 고정값 모드와 실패 스텁(``STUB_FAIL_ITEM``)은 그대로다.

이 파일은 :class:`app.engine.StubEngine`과 운명을 같이한다 - 실모델(KAN-22)이 들어오면
스텁과 함께 지워진다.
"""

from __future__ import annotations

import collections

import pytest
from fastapi.testclient import TestClient

from app.config import DEFAULT_STUB_SCORE_MODE, Settings
from app.engine import StubEngine, create_engine
from app.main import create_app
from tests.conftest import FAIL_ITEM, post

#: sv-0.3 집계식 (BE ``ScoreAggregator``, 명세서 §4.3). AI는 이 식을 실행하지 않는다 -
#: 정본은 BE의 ``score-versions/sv-0.3.json``이고, 여기 옮겨 둔 것은 "스텁이 내는 원점수로
#: 5등급에 닿을 수 있는가"를 AI 저장소 안에서 계산해 보기 위한 사본이다. BE가 가중치나
#: 경계를 바꾸면 이 테스트도 같이 고쳐야 한다.
INTONATION_WEIGHT = 2
VOCABULARY_WEIGHT = 1
#: 등급 경계 - 높은 쪽부터 본다. 경계값은 상위 등급이다 (KAN-21).
TIER_BOUNDARIES = (
    (80, "NATIVE"),
    (60, "HONORARY"),
    (40, "WANNABE"),
    (20, "TRAVELER"),
    (0, "OUTSIDER"),
)
#: 음성 문항 수 (§5.1의 음성 5 + 어휘 5)
VOICE_ITEMS = 5
#: 어휘 5문항이 만들 수 있는 단어 점수 - 정답 수 x 100 / 5다. 이 값은 스텁이 아니라
#: 응시자가 정한다
VOCABULARY_SCORES = (0, 20, 40, 60, 80, 100)


def round_half_up(numerator: int, denominator: int) -> int:
    """BE ``ScoreAggregator.roundHalfUp``과 같은 사사오입 - 부동소수점을 쓰지 않는다."""
    return (2 * numerator + denominator) // (2 * denominator)


def tier_of(overall: int) -> str:
    for boundary, code in TIER_BOUNDARIES:
        if overall >= boundary:
            return code
    raise AssertionError(f"등급 경계 밖의 종합 점수다: {overall}")


def aggregate(intonation_scores: list[int], vocabulary_score: int) -> tuple[int, str]:
    """세션 하나의 종합 점수와 등급 (§4.3)."""
    intonation = round_half_up(sum(intonation_scores), len(intonation_scores))
    overall = round_half_up(
        intonation * INTONATION_WEIGHT + vocabulary_score * VOCABULARY_WEIGHT,
        INTONATION_WEIGHT + VOCABULARY_WEIGHT,
    )
    return overall, tier_of(overall)


def session_scores(index: int) -> list[int]:
    """세션 하나가 받는 음성 5문항의 원점수.

    앱은 업로드마다 새 UUID를 발급하므로(``UploadClient``) 한 세션 안에서도 문항마다
    correlationId가 다르다 - 그 독립성을 그대로 재현한다.
    """
    return [StubEngine.hashed_score(f"c_sim{index}-{item}") for item in range(VOICE_ITEMS)]


def hashed_client(tmp_path, **overrides) -> TestClient:
    settings = Settings(temp_dir=tmp_path / "ai-tmp", stub_delay_ms=0, **overrides)
    return TestClient(create_app(settings))


def test_기본_설정은_분산_모드다():
    # 배포가 기본값으로 뜨면 곧바로 5등급이 나와야 한다 - 고정이 기본이면 설정을
    # 빠뜨린 환경이 다시 등급 셋만 내면서 아무 신호도 남기지 않는다
    assert Settings().stub_score_mode == DEFAULT_STUB_SCORE_MODE == StubEngine.SCORE_MODE_HASHED
    assert Settings.from_env({}).stub_score_mode == StubEngine.SCORE_MODE_HASHED
    assert (
        Settings.from_env({"ACCENTURY_AI_STUB_SCORE_MODE": "fixed"}).stub_score_mode
        == StubEngine.SCORE_MODE_FIXED
    )


def test_같은_correlationId는_같은_점수를_돌려준다(tmp_path):
    """AC - BE 재전송 멱등(§4.1). 재전송은 같은 correlationId로 온다."""
    with hashed_client(tmp_path) as client:
        first = post(client).json()
        second = post(client).json()

    assert first["intonationScore"] == second["intonationScore"]
    # 프로세스를 다시 띄워도 같은 값이어야 한다 - 파이썬 내장 hash()는 문자열 시드를
    # 매 프로세스 새로 뽑으므로(PYTHONHASHSEED) 이 못이 박히지 않는다. blake2b가
    # 아닌 것으로 바꾸는 순간 이 줄이 먼저 깨진다
    assert first["intonationScore"] == StubEngine.hashed_score("c_test") == 91


def test_헤더로만_온_추적_ID도_점수의_씨앗이_된다(tmp_path):
    """씨앗의 출처는 라우트가 정한 추적 ID 하나다 (§2.2 - 헤더 우선, 없으면 meta).

    엔진이 meta에서 따로 뽑으면, 헤더만 보내는 호출자에게 씨앗이 빈 문자열이 되어 모든
    요청이 같은 점수를 받는다 - 없애려던 고정 점수가 조용히 돌아오고, 로그는 헤더에서 뽑은
    ID를 찍으니 원인도 안 보인다. 지금 BE가 양쪽에 같은 값을 넣어 우연히 맞는 상태를
    계약으로 오해하지 않기 위한 못이다.
    """
    with hashed_client(tmp_path) as client:
        본문 = post(client, correlation_id="c_header_only", meta_correlation_id=None).json()

    assert 본문["intonationScore"] == StubEngine.hashed_score("c_header_only")
    # 빈 씨앗으로 접혔다면 헤더가 무엇이든 같은 값이 나온다
    assert 본문["intonationScore"] != StubEngine.hashed_score("")


def test_추적_ID가_아무_데도_없으면_빈_씨앗으로_접는다(tmp_path):
    """헤더도 meta도 없거나, meta의 값이 문자열이 아닌 경우다.

    둘 다 BE 계약 위반이지만(§4.1 meta 필수) 500으로 갚을 일은 아니다 - BE는 500을 일시
    장애로 보고 같은 요청을 재전송 예산이 마를 때까지 반복한다. 빈 씨앗도 해시는 결정적이라
    멱등은 지켜진다.
    """
    with hashed_client(tmp_path) as client:
        아무것도_없음 = post(client, correlation_id=None, meta_correlation_id=None)
        타입_어긋남 = post(client, correlation_id=None, meta_correlation_id=3)

    assert 아무것도_없음.status_code == 200
    assert 아무것도_없음.json()["intonationScore"] == StubEngine.hashed_score("")
    assert 타입_어긋남.status_code == 200
    assert 타입_어긋남.json()["intonationScore"] == StubEngine.hashed_score("")


def test_다른_correlationId는_다른_점수를_받는다():
    # 결정적이라는 말이 "늘 같은 값"이 되면 고정 모드와 다를 게 없다
    ids = [f"c_{index}" for index in range(200)]

    scores = {StubEngine.hashed_score(correlation_id) for correlation_id in ids}

    assert len(scores) > 50


def test_점수가_0부터_100까지_고르게_덮는다():
    """AC - 분포. 문항 평균이 억양 점수라, 덮는 폭이 좁으면 세션 점수도 좁아진다."""
    scores = [StubEngine.hashed_score(f"c_{index}") for index in range(20_000)]

    # 101개 값이 하나도 빠지지 않는다 - 끝값(0, 100)이 나오는지까지 본다
    assert set(scores) == set(range(StubEngine.MAX_SCORE + 1))
    # 쏠림도 본다. 기대 도수는 20000/101 = 약 198이고, 균등하다면 어떤 값도 그 절반
    # 아래나 두 배 위로는 가지 않는다 (이항분포 표준편차가 약 14라 아주 헐거운 상한이다)
    counts = collections.Counter(scores)
    assert min(counts.values()) > 99
    assert max(counts.values()) < 396


def test_반복_응시로_5등급이_전부_관측된다():
    """AC-1 - 서로 다른 세션을 반복하면 5등급이 모두 도달 가능하다.

    correlationId가 문항마다 다르므로 억양 점수는 독립 5개의 평균이고, 그만큼 중심으로
    몰린다(표준편차 약 12.9). 그래서 극단 두 등급은 단어 점수가 함께 극단일 때 열린다 -
    어휘를 다 틀린 응시자가 OUTSIDER를, 다 맞힌 응시자가 NATIVE를 본다. 아래 반복은
    난수가 아니라 고정된 correlationId 목록이라 결과가 매번 같다.
    """
    observed: dict[str, tuple[int, int, int]] = {}

    for index in range(200):
        scores = session_scores(index)
        for vocabulary in VOCABULARY_SCORES:
            overall, tier = aggregate(scores, vocabulary)
            observed.setdefault(tier, (round_half_up(sum(scores), VOICE_ITEMS), vocabulary, overall))

    assert set(observed) == {code for _, code in TIER_BOUNDARIES}


def test_고정_모드는_기존_75점을_유지한다(tmp_path):
    """AC - 회귀 테스트와 계약 테스트가 검산할 기준값 (KAN-27의 동작)."""
    with hashed_client(tmp_path, stub_score_mode=StubEngine.SCORE_MODE_FIXED) as client:
        body = post(client).json()

        # correlationId가 달라도 흔들리지 않는 것까지가 고정 모드다 - 분산 모드였다면
        # 두 값이 갈린다 (c_test는 91, c_other는 다른 값이다)
        다른_추적 = post(client, correlation_id="c_other").json()

    assert body["intonationScore"] == 75
    assert 다른_추적["intonationScore"] == 75
    assert StubEngine.hashed_score("c_test") != StubEngine.hashed_score("c_other")


def test_실패_스텁은_분산_모드에서도_동작한다(tmp_path):
    """AC - STUB_FAIL_ITEM 보존. 점수 산출보다 앞에 있어야 하는 분기다."""
    with hashed_client(tmp_path, stub_fail_item=FAIL_ITEM) as client:
        실패 = post(client, item_id=FAIL_ITEM)
        정상 = post(client, item_id="v1")

    assert 실패.status_code == 422
    assert 실패.json()["quality"]["code"] == "AUDIO_TOO_QUIET"
    assert 실패.json()["retryable"] is True
    assert 정상.status_code == 200


def test_모르는_점수_모드는_기동을_세운다():
    # 오타를 고정 모드로 접어 두면, 분산을 켰다고 믿는 환경이 점수 하나만 내보낸다
    with pytest.raises(ValueError, match="알 수 없는 스텁 점수 모드"):
        create_engine(Settings(stub_score_mode="random"))


def test_고정_점수가_범위_밖이면_기동을_세운다():
    # 요청마다 AnalysisOutcome이 터지는 500이 되고, BE는 그것을 일시 장애로 보고
    # 재전송 예산이 마를 때까지 같은 실패를 반복한다 - 기동에서 드러나야 한다
    with pytest.raises(ValueError, match="스텁 억양 점수가 0~100 밖이다"):
        create_engine(Settings(stub_intonation_score=750))
    with pytest.raises(ValueError, match="스텁 억양 점수가 0~100 밖이다"):
        create_engine(Settings(stub_intonation_score=-1))
