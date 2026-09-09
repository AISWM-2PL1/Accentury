"""추론 단계별 소요 시간 (KAN-204).

문항당 처리 시간이 어느 단계에 쓰였는지가 로그에도 지표에도 없었다. 그 값이 없으면 GPU 전환
(KAN-57), MFA 상주화, 인스턴스 상향(KAN-36)의 기대 효과가 전부 추정으로 남는다. 여기 있는 것은
그 값을 모으는 자리 둘이다.

- :class:`StageRecord` - 요청 1건이 들고 다니는 수첩이다. 라우트가 만들어
  :class:`app.engine.AnalysisRequest`에 실어 보내고 어댑터가 채운다. 요청마다 새 객체라
  동시 요청이 서로의 값을 덮지 않는다.
- :class:`StageMetrics` - 프로세스가 들고 있는 최근 표본 버퍼다. 호스트 타이머가 1분마다
  훑어 가서 CloudWatch에 올린다 (KAN-38의 지표 경로를 그대로 쓴다).

## 왜 집계하지 않고 표본을 그대로 두는가

CloudWatch의 백분위는 **관측값이 낱개로 올라와야** 계산된다. 평균이나 합만 올리면 p50과 p95는
영영 나오지 않는다 - backend 쪽 Micrometer가 분당 집계값 하나만 올려 "가장 나쁜 태스크의 P95"로
타협한 것이 같은 자리다 (docs/wiki/observability.md). 그래서 여기서는 접지 않고 표본을 그대로
쌓아 두고, 호스트 타이머가 ``Values``로 올린다.

## 왜 이름을 좁히는가

단계 이름이 곧 CloudWatch 차원 값이고, 지표 이름 x 차원 조합 하나가 월 0.30달러다. 전달본이
모르는 이름을 하나 보낼 때마다 조합이 늘어 요금이 상한 없이 자란다 - 세션 ID를 태그로 쓰지 않는
것과 같은 이유다. 그래서 :data:`STAGES`에 없는 이름은 버린다.
"""

from __future__ import annotations

import logging
import math
from collections import deque
from collections.abc import Mapping
from typing import Any

log = logging.getLogger(__name__)

#: 전달본(``Track1Scorer.score``) **안쪽** 단계 - 어댑터에서는 잴 수 없다 (KAN-204 요구사항).
#:
#: 워커 결과 JSON의 ``stageMs``로 받는다 (:meth:`StageRecord.merge`). 전달본이 그 값을 돌려주기
#: 전까지 이 다섯은 비어 있고, 어댑터가 재는 바깥 구간만 올라간다.
MODEL_STAGES = (
    "transcribe",  # 전사 (Whisper)
    "gate",        # 내용 게이트 판정
    "align",       # 정렬 (MFA)
    "f0",          # F0 추출
    "scoring",     # 거리와 채점
)

#: 기록 순서 겸 허용 목록. 로그 한 줄과 대시보드의 읽는 순서가 이 순서다.
STAGES = (
    "lockWait",    # 단일 lock 대기 - 앞 요청의 추론이 끝나기를 기다린 시간이다
    "workerLoad",  # 워커 적재와 재적재 대기 - 적재가 실제로 일어난 요청에만 있다
    "model",       # 전달본 score() 호출 전체 - 어댑터가 잴 수 있는 가장 안쪽이다
    *MODEL_STAGES,
    "total",       # 엔진 호출 전체 - 라우트가 잰다 (합계)
)

_KNOWN = frozenset(STAGES)

#: ``warm`` 차원의 값. 콜드는 그 워커의 첫 채점이라는 뜻이다 (:meth:`StageRecord.mark_cold`).
WARM = "warm"
COLD = "cold"

#: 한 계열이 들고 있는 표본의 상한.
#:
#: CloudWatch PutMetricData가 ``Values`` 하나에 받는 개수가 150이다. 더 쌓아 봐야 API가 거절하므로
#: 여기서 미리 끊고 오래된 것부터 버린다. 워커가 1개이고 추론이 10초대라 1분에 도는 문항은 많아야
#: 6건이므로(KAN-172 실측) 정상 부하에서는 닿지 않는 값이다 - 아무도 훑어 가지 않는 개발 기계에서
#: 메모리가 무한정 자라지 않게 하는 것이 이 상한의 실제 목적이다.
MAX_SAMPLES = 150


class StageRecord:
    """요청 1건의 단계별 소요 시간과 콜드/웜 구분."""

    __slots__ = ("_ms", "_cold")

    def __init__(self) -> None:
        self._ms: dict[str, float] = {}
        self._cold = False

    def put(self, stage: str, ms: float) -> None:
        """단계 하나의 소요 시간(ms)을 적는다.

        모르는 이름과 수가 아닌 값은 버린다. 지표는 최선 노력이라 여기서 예외를 올리면 계측이
        멀쩡한 분석 요청을 500으로 죽이는 셈이 된다 - 그것이 계측이 없는 것보다 나쁘다.
        """
        if stage not in _KNOWN:
            log.warning("모르는 단계 이름을 버린다 stage=%r", stage)
            return
        # bool은 int의 하위 타입이라 따로 막는다 - True가 1ms로 통과하면 안 된다
        if isinstance(ms, bool) or not isinstance(ms, (int, float)) or not math.isfinite(ms):
            log.warning("단계 시간이 유한한 수가 아니다 stage=%s type=%s", stage, type(ms).__name__)
            return
        # 음수는 있을 수 없지만(monotonic 시계다) 전달본이 준 값은 우리 시계가 아니다.
        # CloudWatch는 음수도 받으므로 여기서 접지 않으면 그대로 백분위에 섞인다
        self._ms[stage] = max(float(ms), 0.0)

    def merge(self, values: Any) -> None:
        """전달본이 돌려준 단계 시간 dict를 받는다.

        ``{"transcribe": 8123.4, "align": 2010.0, ...}`` 모양이고 값은 ms다 (KAN-204에서 정한
        인터페이스, 워커 결과 JSON의 ``stageMs``). 어댑터 몫의 이름은 받지 않는다 - 전달본이
        ``model``이나 ``total``까지 덮으면 어댑터가 실제로 잰 값이 사라진다.

        전달본이 아직 이 값을 돌려주지 않으므로 지금은 ``None``이 들어온다. 그때는 아무 일도
        하지 않는다 - 인터페이스만 먼저 열어 둔 자리다.
        """
        if values is None:
            return
        if not isinstance(values, Mapping):
            log.warning("전달본의 단계 시간이 dict가 아니다 type=%s", type(values).__name__)
            return
        for stage, ms in values.items():
            if stage not in MODEL_STAGES:
                log.warning("전달본이 모르는 단계 이름을 보냈다 stage=%r", stage)
                continue
            self.put(stage, ms)

    def mark_cold(self) -> None:
        """이 요청이 그 워커의 첫 채점이라고 적는다.

        가중치는 워밍업에서 이미 올라와 있어도 전달본 안쪽의 지연 초기화(torch 컴파일, MFA 첫
        실행)가 첫 채점에서 한 번 일어난다. staging 첫 호출 22.9초와 그 뒤 10초대의 차이가
        그것이고(KAN-172 실측), 그 차이를 지표에서 가르는 것이 ``warm`` 차원이다.
        """
        self._cold = True

    @property
    def warm(self) -> str:
        return COLD if self._cold else WARM

    def items(self) -> tuple[tuple[str, float], ...]:
        """적힌 단계를 :data:`STAGES` 순서로 돌려준다."""
        return tuple((stage, self._ms[stage]) for stage in STAGES if stage in self._ms)

    def as_log(self) -> str:
        """로그 한 줄에 실을 표기 - ``lockWait:3,model:10123,total:10130``."""
        return ",".join(f"{stage}:{round(ms)}" for stage, ms in self.items()) or "없음"

    def __bool__(self) -> bool:
        return bool(self._ms)


class StageMetrics:
    """최근 표본 버퍼 - 호스트 타이머가 훑어 간다.

    소비자는 하나다 (``infra/modules/ai-host/ai-health-metric.sh``, 1분 주기). 훑어 가면 버퍼가
    비므로 **그 회차의 put-metric-data가 실패하면 그 표본은 사라진다.** 재시도 큐를 두지 않는
    이유는 지표가 최선 노력이기 때문이다 - 못 올린 1분을 살리려고 메모리에 쌓으면 CloudWatch가
    오래 막혔을 때 추론 프로세스가 그 대가를 치른다.
    """

    def __init__(self, max_samples: int = MAX_SAMPLES) -> None:
        self._max = max_samples
        self._series: dict[tuple[str, str], deque[float]] = {}

    def record(self, record: StageRecord) -> None:
        """요청 1건의 수첩을 계열별 표본으로 옮긴다."""
        warm = record.warm
        for stage, ms in record.items():
            series = self._series.get((stage, warm))
            if series is None:
                series = self._series[(stage, warm)] = deque(maxlen=self._max)
            series.append(ms)

    def drain(self) -> str:
        """쌓인 표본을 한 줄씩 내고 버퍼를 비운다.

        모양은 ``<단계> <콜드/웜> <ms,ms,...>``이고 값은 반올림한 정수다. JSON이 아닌 이유는
        소비자가 jq 없이 sed와 read로 값을 뽑는 셸 스크립트이고, 이 줄이 그대로
        ``Values=[...]``가 되기 때문이다 - 옮겨 적는 코드가 없으면 어긋날 자리도 없다.
        """
        lines = [
            f"{stage} {warm} " + ",".join(str(round(ms)) for ms in series)
            for stage in STAGES
            for warm in (WARM, COLD)
            if (series := self._series.pop((stage, warm), None))
        ]
        return "".join(f"{line}\n" for line in lines)
