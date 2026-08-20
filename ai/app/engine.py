"""분석 엔진 어댑터 경계 (KAN-135).

라우트가 아는 것은 :class:`AnalysisEngine` 하나다 - 스텁이 붙어 있든 실모델(KAN-22)이
붙어 있든 ``POST /internal/v0/analyze``의 코드는 같다. 갈아끼우기가 목적의 절반이고,
나머지 절반은 **보장이 엔진 종류와 무관하게 걸리는 것**이다. 임시파일 수명(KAN-27),
추론 상한, 오디오 파트 상한(413), §4.1 응답 봉투 조립은 전부 이 경계 바깥(라우트와
공용 계층)에 남는다. 엔진이 하는 일은 "디스크에 놓인 오디오 하나를 보고 점수를 말한다"
하나뿐이다.

실모델이 들어올 때 이 파일에서 사라지는 것은 :class:`StubEngine`뿐이고, 프로토콜과
라우트는 그대로 남는다.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal, Protocol

from app.config import Settings

#: 엔진 이름 - :func:`create_engine`이 고르는 값이다. 실모델은 KAN-22가 하나 더 만든다.
STUB_ENGINE = "stub"

STATUS_OK: Literal["OK"] = "OK"
STATUS_FAILED: Literal["FAILED"] = "FAILED"

#: 문제 없음을 뜻하는 품질 코드 (§2.4). 상태 문자열과 글자가 같을 뿐 다른 축이다.
QUALITY_OK = "OK"

#: 품질 코드의 상한 - BE의 analysis_job.quality_code가 varchar(40)이다. 성공 응답의
#: 코드는 BE가 검사 없이 그대로 저장하고 사용자에게도 내보내므로 여기서 끊는다.
MAX_QUALITY_CODE_LENGTH = 40

#: 판정 실패(422)로 낼 수 있는 §2.4 코드.
#:
#: BE는 ``ErrorCode`` enum에 없는 이름을 받으면 계약 위반으로 끊고, 그 문항은 재시도
#: 없이 죽는다 (``RestAiAnalysisClient``의 422 분기 - 미검증 문자열을 DB와 사용자에게
#: 흘리지 않기 위해서다). 서술적인 코드를 지어내면 사용자가 문항을 잃으므로, 이름을
#: 여기서 좁힌다. BE가 §2.4에 판정 코드를 더하면 이 집합에도 더한다.
JUDGED_QUALITY_CODES = frozenset(
    {
        "AUDIO_TOO_QUIET",
        "AUDIO_TOO_LONG",
        "AUDIO_FORMAT_UNSUPPORTED",
        "ANALYSIS_MISREAD",
    }
)


def _text(value: Any) -> str:
    """meta의 문자열 필드를 안전하게 꺼낸다 - 없거나 타입이 다르면 빈 문자열이다."""
    return value if isinstance(value, str) else ""


@dataclass(frozen=True)
class AnalysisRequest:
    """엔진에 넘기는 입력.

    오디오는 바이트가 아니라 **경로**로 넘긴다 - 추론 라이브러리(librosa, torchaudio 등)가
    파일을 받는 데다, 파일 수명을 라우트가 쥐고 있어야 어떤 엔진이 와도 무잔존이 지켜지기
    때문이다 (KAN-27). 엔진은 이 파일을 읽기만 하고 지우거나 옮기지 않는다.

    ``meta``는 BE가 보낸 meta 파트(§3.3, §4.1)를 파싱한 그대로다. 실모델이 필요로 하는
    필드(``durationMs``, ``clientQuality`` 등)를 라우트가 미리 골라내지 않는다 - 골라내면
    새 필드를 쓸 때마다 라우트를 고쳐야 한다.
    """

    audio_path: Path
    meta: Mapping[str, Any]

    @property
    def item_id(self) -> str:
        return _text(self.meta.get("itemId"))

    @property
    def correlation_id(self) -> str:
        return _text(self.meta.get("correlationId"))


@dataclass(frozen=True)
class AnalysisOutcome:
    """엔진 1회 실행의 결과.

    §4.1 봉투의 **재료**이지 봉투가 아니다 - ``scoreVersion``, ``processingMs``처럼 엔진이
    알 바 아닌 값은 여기 없고, 라우트가 채운다. ``modelVersion``도 엔진이 자기 정체로
    보고하는 값이라(:attr:`AnalysisEngine.model_version`) 실행 결과에 싣지 않는다.
    """

    status: Literal["OK", "FAILED"]
    #: 0~100 스케일 (§4.3). 문항 20점 환산과 종합 집계는 BE가 한다.
    intonation_score: int | None = None
    confidence: float | None = None
    #: §4.1 quality.code - 성공이면 ``OK``, 실패면 실패 사유다.
    quality_code: str = QUALITY_OK
    #: 실패가 재시도로 풀릴 수 있는지 (§4.1). 성공 응답에는 실리지 않는다.
    retryable: bool = False
    #: §4.1 segments - 스텁은 비우고 실모델(KAN-22)이 채운다.
    segments: Sequence[Mapping[str, Any]] = ()

    def __post_init__(self) -> None:
        """엔진이 낸 값이 §4.1 봉투로 나가도 되는지 여기서 끊는다.

        검사를 라우트가 아니라 결과 객체가 하는 이유는, 편의 생성자(:meth:`ok`,
        :meth:`failure`)를 거치지 않고 dataclass를 직접 만든 엔진도 같은 검사를 지나게
        하기 위해서다 - 통과하지 못한 값은 아예 존재하지 못한다.

        BE는 성공 응답의 점수가 없거나 0~100 밖이면 계약 위반으로 끊고, **그 거절을
        회로 차단기의 실패로 센다** (``RestAiAnalysisClient.completed``). 검사를 두지
        않으면 엔진 버그 하나가 BE 회로를 여는 형태로 번진다.

        상태 문자열도 여기서 좁힌다. 자유 문자열을 허용하면 ``"failed"``(소문자) 같은
        값이 실패 분기를 비껴가 200 봉투로 나가고, 점수는 비어 있게 된다.
        """
        # 시퀀스를 먼저 굳힌다 - 제너레이터를 받으면 검사가 다 소비해 라우트에는 빈 것이
        # 남고, segments가 조용히 사라진 200이 나간다. 500보다 나쁘다 - 아무도 모른다.
        # 굳혀 두면 검증을 통과한 뒤 원본 리스트를 변형해도 봉투가 흔들리지 않는다
        try:
            object.__setattr__(self, "segments", tuple(self.segments))
        except TypeError as error:
            raise ValueError(f"segments가 시퀀스가 아니다: {error}") from error
        if self.status not in (STATUS_OK, STATUS_FAILED):
            raise ValueError(f"알 수 없는 분석 상태: {self.status!r}")
        # 품질 코드는 성공 경로에서 BE가 검사 없이 DB(varchar(40))와 사용자 응답까지
        # 흘려보낸다 - 422 경로만 §2.4 목록으로 거르므로, 200 쪽은 여기가 유일한 관문이다
        if not isinstance(self.quality_code, str) or not self.quality_code:
            raise ValueError(f"품질 코드가 비었다: {self.quality_code!r}")
        if len(self.quality_code) > MAX_QUALITY_CODE_LENGTH:
            raise ValueError(
                f"품질 코드가 {MAX_QUALITY_CODE_LENGTH}자를 넘는다: {len(self.quality_code)}자"
            )
        if self.status == STATUS_FAILED:
            # 기본값 "OK"도, 오타도, 지어낸 서술적 코드도 BE에서는 같은 결말이다 -
            # 계약 위반으로 끊기고 회로 차단기의 실패로 계수되며 문항이 재시도 없이 죽는다
            if self.quality_code not in JUDGED_QUALITY_CODES:
                raise ValueError(
                    f"실패 사유가 §2.4 판정 코드가 아니다: {self.quality_code!r} "
                    f"(가능: {', '.join(sorted(JUDGED_QUALITY_CODES))})"
                )
            # 문자열 "yes"는 JSON으로 멀쩡히 나가고 BE의 Boolean 역직렬화에서 터진다 -
            # 직렬화 검사가 잡아 주지 못하는 종류라 타입을 따로 본다
            if not isinstance(self.retryable, bool):
                raise ValueError(f"retryable이 불리언이 아니다: {self.retryable!r}")
            self._require_serializable()
            return
        # bool은 int의 하위 타입이라 따로 막는다 - True가 점수 1로 통과하면 안 된다
        if not isinstance(self.intonation_score, int) or isinstance(self.intonation_score, bool):
            raise ValueError(f"성공 결과에는 정수 억양 점수가 있어야 한다: {self.intonation_score!r}")
        if not 0 <= self.intonation_score <= 100:
            raise ValueError(f"억양 점수가 0~100 밖이다: {self.intonation_score}")
        # 신뢰도의 범위는 보지 않는다 - BE가 읽지 않는 값이라(§4.1 주석) 과하게 좁히면
        # 멀쩡한 엔진이 막힌다. 자리가 비거나 타입이 어긋나는 것만 막는다
        if self.confidence is None:
            raise ValueError("성공 결과에는 신뢰도가 있어야 한다")
        if not isinstance(self.confidence, (int, float)) or isinstance(self.confidence, bool):
            raise ValueError(f"신뢰도가 수가 아니다: {self.confidence!r}")
        self._require_serializable()

    def _require_serializable(self) -> None:
        """봉투로 나갈 수 없는 값은 아예 존재하지 못하게 한다.

        필드마다 허용 타입을 열거하는 대신 실제로 한 번 직렬화해 본다. 검사가 필드
        목록을 따라가는 방식이면 새 필드가 늘 때마다 빠뜨리게 되고, 실제로 빠지는 쪽이
        하필 실모델이 채우는 자리다 - ``confidence``와 ``segments``의 자연스러운 출력은
        ``numpy.float32``, ``numpy.int64``이고, 이들은 파이썬 ``float``/``int``의 하위
        타입이 아니라 ``json.dumps``가 거절한다. 여기서 막지 않으면 응답을 만드는
        시점에 ``TypeError``로 터져 500이 되고, BE는 그것을 일시 장애로 보고 같은
        실패를 재전송 예산이 마를 때까지 반복한다.
        """
        for index, segment in enumerate(self.segments):
            if not isinstance(segment, Mapping):
                raise ValueError(f"segments[{index}]가 매핑이 아니다: {type(segment).__name__}")
        try:
            # allow_nan은 렌더러(Starlette JSONResponse)와 같은 False로 맞춘다 - 기본값
            # True로 검사하면 NaN과 Inf가 여기를 통과한 뒤 렌더에서 터진다. 무음 프레임의
            # 0 나눗셈, log(0), 빈 F0 배열의 평균이 전부 NaN이고, NaN은 float이라
            # 타입 검사도 지나간다. 검사와 렌더의 규칙이 다르면 검사가 아니다
            json.dumps(
                {
                    "status": self.status,
                    "intonationScore": self.intonation_score,
                    "confidence": self.confidence,
                    "quality": {"code": self.quality_code},
                    "retryable": self.retryable,
                    "segments": list(self.segments),
                },
                allow_nan=False,
            )
        except (TypeError, ValueError) as error:
            raise ValueError(f"봉투로 직렬화할 수 없는 값이 있다: {error}") from error

    @property
    def failed(self) -> bool:
        return self.status == STATUS_FAILED

    @classmethod
    def ok(
        cls,
        #: 반올림한 **정수**로 낸다 - float은 거절된다 (BE가 정수 컬럼에 저장한다)
        intonation_score: int,
        confidence: float = 1.0,
        quality_code: str = QUALITY_OK,
        segments: Sequence[Mapping[str, Any]] = (),
    ) -> "AnalysisOutcome":
        return cls(
            status=STATUS_OK,
            intonation_score=intonation_score,
            confidence=confidence,
            quality_code=quality_code,
            segments=segments,
        )

    @classmethod
    def failure(cls, quality_code: str, retryable: bool) -> "AnalysisOutcome":
        """판정 실패 (§4.1의 422) - 요청 자체는 정상이나 점수를 낼 수 없는 경우다."""
        return cls(status=STATUS_FAILED, quality_code=quality_code, retryable=retryable)


class AnalysisEngine(Protocol):
    """오디오 1건을 점수로 바꾸는 것 하나만 책임진다.

    구현체는 이 프로토콜을 상속할 필요가 없다 - 구조만 맞으면 된다. 테스트의 가짜 엔진이
    바로 그 경우다 (KAN-135 AC).
    """

    @property
    def model_version(self) -> str:
        """이 엔진이 스스로 보고하는 버전.

        설정값이 아니다 (KAN-135) - 실모델은 자기가 실제로 적재한 가중치의 버전을 내고,
        스텁은 ``stub-0.1``을 낸다. 설정으로 덮어쓸 수 있게 두면 배포 환경이 부른 이름과
        실제 돌고 있는 것이 어긋나도 아무도 모른다.
        """

    async def analyze(self, request: AnalysisRequest) -> AnalysisOutcome:
        """오디오를 보고 결과를 돌려준다.

        상한(:attr:`Settings.analysis_timeout_seconds`)과 파일 삭제는 라우트가 걸지만,
        그 보장은 구현이 다음 둘을 지킬 때만 성립한다. 지키지 못하면 라우트가 아무리
        상한을 걸어도 소용이 없으므로, 편의가 아니라 **계약**이다.

        1. **이벤트 루프를 막지 않는다.** ``asyncio.timeout``은 ``await`` 지점에서만
           발화한다 - 동기 추론을 ``async def`` 안에서 그대로 돌리면 상한이 발화조차
           못 하고, 임시파일 수명과 서버 전체의 응답성이 그 추론에 묶인다. 블로킹
           추론은 ``asyncio.to_thread``나 전용 executor로 넘긴다.
        2. **취소가 실제로 닿아야 한다.** ``CancelledError``를 삼키지 않는 것으로는
           부족하다 - ``asyncio.to_thread``에 넘긴 작업은 취소되지 않아서, 라우트가
           503을 내고 임시파일을 지운 뒤에도 워커는 계속 돌며 이미 사라진 파일을 본다.
           GPU 슬롯도 그만큼 붙들린다. 중단이 실제로 닿는 수단(서브프로세스 종료,
           추론 라이브러리의 중단 훅 등)을 쓴다.

        계약을 지켰는지 검사하는 적합성 테스트는 KAN-137이 맡는다 - 여기서는 계약을
        명시하는 데까지다.
        """


class StubEngine:
    """실모델이 오기 전까지의 자리 지킴 (KAN-27에서 이어온 동작 그대로).

    **실모델 전환 시 이 클래스는 통째로 제거된다** (KAN-22). 지연 흉내, 고정 점수, 실패
    스텁은 전부 앱과 BE의 경로를 시험하기 위한 장치이고 어느 것도 분석 규칙이 아니다.
    점수 분포를 넓히는 일(KAN-136)도 이 클래스 안에서만 한다.
    """

    #: 스텁의 정체. 설정으로 바꾸지 않는다 - 이 값이 보이면 스텁이 돌고 있다는 뜻이어야 한다.
    MODEL_VERSION = "stub-0.1"

    def __init__(self, settings: Settings) -> None:
        self._delay_seconds = settings.stub_delay_ms / 1000
        self._intonation_score = settings.stub_intonation_score
        self._fail_item = settings.stub_fail_item

    @property
    def model_version(self) -> str:
        return self.MODEL_VERSION

    async def analyze(self, request: AnalysisRequest) -> AnalysisOutcome:
        if self._delay_seconds:
            await asyncio.sleep(self._delay_seconds)
        # 모델이 그러듯 파일을 실제로 한 번 만진다 - 라우트가 넘긴 경로가 살아 있는지까지 본다
        request.audio_path.stat()
        if self._fail_item and request.item_id == self._fail_item:
            return AnalysisOutcome.failure(quality_code="AUDIO_TOO_QUIET", retryable=True)
        return AnalysisOutcome.ok(intonation_score=self._intonation_score)


def require_reportable_version(engine: AnalysisEngine) -> AnalysisEngine:
    """엔진이 쓸 만한 ``modelVersion``을 보고하는지 기동 시 확인한다.

    BE는 ``modelVersion``이 비면 성공 응답을 계약 위반으로 끊는다
    (``RestAiAnalysisClient.completed``). 첫 요청에서 알게 되면 이미 회로가 열린 뒤다.
    """
    # 기동 시 1회만 읽는다 - 워밍업 뒤에 값이 바뀌는 엔진은 잡지 못한다. 봉투에 실리는
    # 값 중 엔진이 만드는 것은 AnalysisOutcome이 조립 전에 전부 검사하지만, modelVersion은
    # 여기(기동 시)에서만 본다. scoreVersion은 호출자 meta에서 온 값이라 검사 대상이
    # 아니다 - 명세가 받은 것을 그대로 되돌리라고 요구한다 (§5.4)
    version = engine.model_version
    if not isinstance(version, str) or not version:
        raise ValueError(
            f"엔진이 modelVersion을 보고하지 않는다: {type(engine).__name__} -> {version!r}"
        )
    return engine


def create_engine(settings: Settings) -> AnalysisEngine:
    """설정이 지정한 엔진을 만든다 (기동 시 1회).

    모르는 이름이면 기동을 세운다 - 스텁으로 조용히 흘러가면, 실모델을 띄웠다고 믿는
    환경이 고정 점수를 내보내면서 아무 신호도 남기지 않는다.
    """
    if settings.analysis_engine == STUB_ENGINE:
        return StubEngine(settings)
    raise ValueError(
        f"알 수 없는 분석 엔진: {settings.analysis_engine!r} (가능한 값: {STUB_ENGINE})"
    )
