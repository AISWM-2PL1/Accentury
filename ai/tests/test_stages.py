"""단계별 소요 시간 수첩과 표본 버퍼 (KAN-204).

여기서 지키는 것은 둘이다. **모르는 이름과 이상한 값이 CloudWatch까지 가지 않는 것** - 단계
이름이 곧 차원 값이고 조합 하나가 월 0.30달러라, 전달본의 오타 하나가 지표와 요금을 늘린다.
그리고 **끊긴 요청에서도 끝난 단계는 남는 것** - 시간 초과가 어느 단계에서 났는지가 이 티켓의
인수 조건이다.
"""

from __future__ import annotations

import pytest

from app.stages import COLD, WARM, StageMetrics, StageRecord


def test_적힌_단계는_정해진_순서로_나온다():
    # 적은 순서가 아니라 파이프라인 순서다 - 로그 한 줄을 눈으로 읽는 것이 첫 조사 수단이다
    record = StageRecord()
    record.put("total", 10130)
    record.put("lockWait", 3)
    record.put("model", 10123.4)

    assert record.as_log() == "lockWait:3,model:10123,total:10130"


def test_아무것도_적히지_않으면_없음이다():
    assert StageRecord().as_log() == "없음"


def test_모르는_단계_이름은_버린다():
    record = StageRecord()
    record.put("whisper", 1)

    assert not record


@pytest.mark.parametrize("value", [float("nan"), float("inf"), "12", True, None])
def test_유한한_수가_아닌_단계_시간은_버린다(value):
    # NaN은 float이라 타입 검사를 지나가고, bool은 int의 하위 타입이다
    record = StageRecord()
    record.put("model", value)

    assert not record


def test_음수는_0으로_접는다():
    # 우리 시계는 monotonic이라 음수가 없지만 전달본이 준 값은 우리 시계가 아니다.
    # CloudWatch는 음수도 받으므로 여기서 접지 않으면 그대로 백분위에 섞인다
    record = StageRecord()
    record.put("align", -5)

    assert record.as_log() == "align:0"


def test_전달본_단계는_안쪽_이름만_받는다():
    # model과 total은 어댑터가 실제로 잰 값이다 - 전달본이 그것까지 덮으면 잰 값이 사라진다
    record = StageRecord()
    record.put("model", 10123)
    record.merge({"transcribe": 8123.4, "align": 2010, "model": 1, "총합": 2})

    assert record.as_log() == "model:10123,transcribe:8123,align:2010"


def test_전달본이_아직_돌려주지_않으면_아무_일도_없다():
    # 지금 워커가 보내는 값이다 - 인터페이스만 열어 둔 상태다
    record = StageRecord()
    record.merge(None)

    assert not record


def test_dict가_아닌_단계_시간은_버린다():
    record = StageRecord()
    record.merge([("transcribe", 1)])

    assert not record


def test_콜드는_그_워커의_첫_채점이라는_뜻이다():
    record = StageRecord()
    assert record.warm == WARM

    record.mark_cold()
    assert record.warm == COLD


def test_표본을_계열별로_모아_한_줄씩_낸다():
    metrics = StageMetrics()
    첫째 = StageRecord()
    첫째.put("model", 10123.4)
    첫째.mark_cold()
    둘째 = StageRecord()
    둘째.put("model", 9876.4)
    둘째.put("lockWait", 2.4)
    metrics.record(첫째)
    metrics.record(둘째)

    # 셸이 그대로 Values=[...]에 넣는 모양이다 (ai-health-metric.sh)
    assert metrics.drain() == "lockWait warm 2\nmodel warm 9876\nmodel cold 10123\n"


def test_훑어_가면_버퍼가_빈다():
    # 소비자는 호스트 타이머 하나다 - 두 곳에서 읽으면 표본이 갈려 어느 쪽도 온전하지 않다
    metrics = StageMetrics()
    record = StageRecord()
    record.put("total", 5)
    metrics.record(record)

    assert metrics.drain() == "total warm 5\n"
    assert metrics.drain() == ""


def test_아무도_훑어_가지_않아도_표본이_상한에서_멎는다():
    # CloudWatch가 Values 하나에 받는 개수가 150이고, 그 위로는 메모리만 먹는다
    metrics = StageMetrics(max_samples=3)
    for ms in range(10):
        record = StageRecord()
        record.put("total", ms)
        metrics.record(record)

    assert metrics.drain() == "total warm 7,8,9\n"
