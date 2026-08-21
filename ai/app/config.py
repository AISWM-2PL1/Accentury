"""AI 분석 서버 설정 (KAN-27 스켈레톤).

환경 변수로만 받는다 - 프라이빗 서브넷의 컨테이너로 뜨는 서버라(§1.1, NFR-SC-04)
설정 파일을 이미지에 굽는 것보다 배포 환경이 주입하는 쪽이 맞다.
"""

from __future__ import annotations

import os
import tempfile
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

#: 임시파일 전용 디렉터리의 기본 위치. 프로세스가 만드는 모든 임시파일이 여기 모인다.
DEFAULT_TEMP_DIR = Path(tempfile.gettempdir()) / "accentury-ai-tmp"

#: 잔존 임시파일 삭제 기준 (KAN-27 - 30분). BE의 accentury.upload.temp-retention과 같은 값이다.
DEFAULT_TEMP_RETENTION_SECONDS = 30 * 60

#: 청소 잡 주기. 보존 기간보다 촘촘해야 "30분 이상 잔존"이 곧바로 정리된다.
DEFAULT_SWEEP_INTERVAL_SECONDS = 5 * 60

#: 분석 1건의 상한 (Codex sol 리뷰 P1). BE의 읽기 타임아웃(10초, accentury.analysis.ai-timeout)이
#: 먼저 끊고 재전송하므로 이 값은 그보다 넉넉한 뒷단 방어선이다 - 추론이 멈춰도 임시파일이
#: 무한정 남지 않게 하는 것이 목적이다. 실제 추론 예산은 KAN-22가 모델을 붙이며 조정한다.
DEFAULT_ANALYSIS_TIMEOUT_SECONDS = 30.0

#: 오디오 파트 상한 - BE가 §3.3에서 이미 1MB로 끊지만, 사설망이라고 무한정 받아 디스크를
#: 채우게 두지 않는다 (Codex sol 리뷰 P2). BE와 같은 값이라 정상 요청은 걸리지 않는다.
DEFAULT_MAX_AUDIO_BYTES = 1_048_576

#: 요청 본문 전체 상한. BE의 spring.servlet.multipart.max-request-size(2MB)와 같은 자리다 -
#: 오디오 파트 상한만으로는 파싱이 끝난 뒤에야 걸러진다 (Codex sol 리뷰 P2).
DEFAULT_MAX_REQUEST_BYTES = 2 * 1_048_576

#: 붙일 분석 엔진 (KAN-135). 실모델(KAN-22)이 들어오면 고를 이름이 하나 더 생긴다.
DEFAULT_ANALYSIS_ENGINE = "stub"

#: 스텁이 점수를 내는 방식 (KAN-136). 기본이 분산인 이유는 고정 75점이 종합 점수를
#: 50.0~83.3에 가두어, 5등급 중 셋만 나오기 때문이다 - 결과 화면(KAN-29)과 공유 카드
#: (KAN-30)의 나머지 둘을 데모할 방법이 없다. 고를 수 있는 값은
#: :attr:`app.engine.StubEngine.SCORE_MODES`가 정본이다.
DEFAULT_STUB_SCORE_MODE = "hashed"


@dataclass(frozen=True)
class Settings:
    """서버 1개의 설정.

    스텁 값(``stub_*``)은 :class:`app.engine.StubEngine`만 읽는다 - 실모델로 갈아끼우면
    (KAN-22) 스텁 클래스와 함께 사라지는 값들이다. ``modelVersion``은 여기 없다.
    설정이 아니라 엔진이 스스로 보고하는 값이기 때문이다 (KAN-135).
    """

    temp_dir: Path = DEFAULT_TEMP_DIR
    temp_retention_seconds: float = DEFAULT_TEMP_RETENTION_SECONDS
    sweep_interval_seconds: float = DEFAULT_SWEEP_INTERVAL_SECONDS
    #: 분석 1건의 상한. 임시파일 수명을 유한하게 묶는 장치다 (KAN-27, Codex sol 리뷰 P1)
    analysis_timeout_seconds: float = DEFAULT_ANALYSIS_TIMEOUT_SECONDS
    #: 받아들이는 오디오 파트의 상한 (§3.3과 같은 1MB)
    max_audio_bytes: int = DEFAULT_MAX_AUDIO_BYTES
    #: 요청 본문 전체의 상한 - multipart 파싱 전에 끊는다 (audio + meta + 오버헤드)
    max_request_bytes: int = DEFAULT_MAX_REQUEST_BYTES
    #: 붙일 엔진의 이름 - :func:`app.engine.create_engine`이 읽는다 (KAN-135)
    analysis_engine: str = DEFAULT_ANALYSIS_ENGINE
    #: 스텁 점수 산출 방식 - ``hashed``면 correlationId를 해시해 0~100을 고르게 덮고,
    #: ``fixed``면 아래 :attr:`stub_intonation_score`를 그대로 낸다 (KAN-136)
    stub_score_mode: str = DEFAULT_STUB_SCORE_MODE
    #: 고정 모드의 억양 점수 - 0~100 스케일 유지, 문항 20점 환산은 BE가 한다 (§4.3).
    #: ``hashed`` 모드에서는 읽지 않는다
    stub_intonation_score: int = 75
    #: 추론 지연 흉내 - 앱과 BE의 대기 화면, 폴링 간격(§5.3)을 실제에 가깝게 시험하기 위한 값
    stub_delay_ms: int = 1500
    #: 이 itemId면 판정 실패(422)를 돌려준다 - 실패 종료 경로 시험용
    stub_fail_item: str | None = None

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "Settings":
        source = os.environ if env is None else env
        return cls(
            temp_dir=Path(source.get("ACCENTURY_AI_TEMP_DIR", str(DEFAULT_TEMP_DIR))),
            temp_retention_seconds=float(
                source.get("ACCENTURY_AI_TEMP_RETENTION_SECONDS", DEFAULT_TEMP_RETENTION_SECONDS)
            ),
            sweep_interval_seconds=float(
                source.get("ACCENTURY_AI_SWEEP_INTERVAL_SECONDS", DEFAULT_SWEEP_INTERVAL_SECONDS)
            ),
            analysis_timeout_seconds=float(
                source.get(
                    "ACCENTURY_AI_ANALYSIS_TIMEOUT_SECONDS", DEFAULT_ANALYSIS_TIMEOUT_SECONDS
                )
            ),
            max_audio_bytes=int(
                source.get("ACCENTURY_AI_MAX_AUDIO_BYTES", DEFAULT_MAX_AUDIO_BYTES)
            ),
            max_request_bytes=int(
                source.get("ACCENTURY_AI_MAX_REQUEST_BYTES", DEFAULT_MAX_REQUEST_BYTES)
            ),
            analysis_engine=source.get("ACCENTURY_AI_ANALYSIS_ENGINE", DEFAULT_ANALYSIS_ENGINE),
            stub_score_mode=source.get("ACCENTURY_AI_STUB_SCORE_MODE", DEFAULT_STUB_SCORE_MODE),
            stub_intonation_score=int(source.get("ACCENTURY_AI_STUB_SCORE", 75)),
            stub_delay_ms=int(source.get("ACCENTURY_AI_STUB_DELAY_MS", 1500)),
            stub_fail_item=source.get("ACCENTURY_AI_STUB_FAIL_ITEM") or None,
        )
