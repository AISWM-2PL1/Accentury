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

#: 분석 1건의 상한 (Codex sol 리뷰 P1) - 추론이 멈춰도 임시파일이 무한정 남지 않게 하는 장치다.
#: BE의 읽기 타임아웃(85초, accentury.analysis.ai-timeout)보다 **짧아야 한다** (KAN-22) - AI가
#: 먼저 끊고 503을 돌려줘야 멈춘 추론이 워커를 붙들지 않는다. 반대면 BE는 포기했는데 워커는
#: 계속 돌고, 그 사이 BE의 다음 요청이 lock 앞에서 기다린다.
#:
#: 75초는 KAN-57의 c7i.xlarge 실측(bf16 + MFA align_one, 1건 P95 11.1초)으로 정했다 (KAN-172).
#: 이 상한은 :mod:`app.track1`의 단일 lock 대기와 워커 재적재 대기까지 포함하므로 정상 추론
#: 하나(11초)가 아니라 다음 둘을 덮어야 한다 - 롤링 배포 중 backend 태스크 최대 6개(상한 3 x
#: 200%)가 겹친 6 x 11초 = 67초, 워커가 죽은 뒤(OOM 또는 한 번의 취소) 재적재 31초 + 추론
#: 11초 = 42초. 그보다 짧으면(예: 25초나 40초) 이미 lock을 잡고 추론 중인 요청을 상한이 끊어
#: 멀쩡한 워커를 죽이고, 재전송이 재적재를 기다리다 또 끊겨 새 워커를 또 죽이는 연쇄가 된다
#: (Codex astra 리뷰 P1, 실제 어댑터로 재현).
#:
#: 두 시나리오를 각각 덮을 뿐 합(6 x 11.1 + 31 = 97.6초)은 덮지 않는다 - 배포가 겹친 채로 워커까지
#: 죽으면 뒤쪽 요청은 이 상한에 걸린다. 그때는 backend의 회로 차단기가 연속 5회 실패에서 열려
#: 호출 자체를 멈추므로(KAN-28) 연쇄가 자기수렴한다 - 합까지 덮으려고 값을 늘리지 않는 이유다.
#: 환경별로는 SSM이 덮어쓴다 (infra/modules/config).
DEFAULT_ANALYSIS_TIMEOUT_SECONDS = 75.0

#: 오디오 파트 상한 - BE가 §3.3에서 이미 1MB로 끊지만, 사설망이라고 무한정 받아 디스크를
#: 채우게 두지 않는다 (Codex sol 리뷰 P2). BE와 같은 값이라 정상 요청은 걸리지 않는다.
DEFAULT_MAX_AUDIO_BYTES = 1_048_576

#: 요청 본문 전체 상한. BE의 spring.servlet.multipart.max-request-size(2MB)와 같은 자리다 -
#: 오디오 파트 상한만으로는 파싱이 끝난 뒤에야 걸러진다 (Codex sol 리뷰 P2).
DEFAULT_MAX_REQUEST_BYTES = 2 * 1_048_576

#: 붙일 분석 엔진 (KAN-135). 기본은 실모델이고, 개발 기계용 가짜 엔진(``fake``)이 하나 더 있다
#: (:mod:`app.fake`, PR #87 리뷰 반영).
DEFAULT_ANALYSIS_ENGINE = "track1"

#: 가짜 엔진의 응답 지연 (ms) - 앱의 대기 화면이 실제로 그려지는지 볼 수 있을 만큼만 둔다.
DEFAULT_FAKE_DELAY_MS = 1500

#: 전달본 모듈이 있는 디렉터리 - 워커가 ``sys.path``에 넣는다 (KAN-159의 이미지 배치).
DEFAULT_TRACK1_SRC_DIR = Path("/app/src")

#: 가중치 적재의 상한. 넘기면 워밍업이 실패로 끝나 health가 STARTING에 머문다.
#: 08-30 실측 기준으로는 1분 안쪽이지만, 콜드 스타트의 디스크 읽기까지 감안해 넉넉히 둔다.
DEFAULT_TRACK1_LOAD_TIMEOUT_SECONDS = 600.0


@dataclass(frozen=True)
class Settings:
    """서버 1개의 설정.

    ``track1_*``는 :class:`app.track1.Track1Engine`만 읽는다. ``modelVersion``은 여기 없다 -
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
    #: 전달본 모듈이 있는 디렉터리 (KAN-22)
    track1_src_dir: Path = DEFAULT_TRACK1_SRC_DIR
    #: 참조 분포 폴더. ``None``이면 전달본의 기본값을 쓴다 - 이미지 안에서는 그 기본값이
    #: 같이 실린 참조라, 경로를 우리가 다시 적으면 모델 이미지를 갈아끼울 때 두 곳이 어긋난다
    track1_ref_dir: Path | None = None
    #: 서비스 문장 목록. ``None``이면 전달본의 기본값을 쓴다 (위와 같은 이유)
    track1_sentences: Path | None = None
    #: Whisper를 올릴 장치 - ``auto``면 전달본이 cuda, mps, cpu 순으로 고른다
    track1_device: str = "auto"
    #: 가중치 적재의 상한 (초)
    track1_load_timeout_seconds: float = DEFAULT_TRACK1_LOAD_TIMEOUT_SECONDS
    #: 가짜 엔진(:mod:`app.fake`)이 판정 실패를 내는 itemId - E2E의 실패 갈래 수단. ``None``이면 없다
    fake_fail_item: str | None = None
    #: 가짜 엔진의 응답 지연 (ms)
    fake_delay_ms: int = DEFAULT_FAKE_DELAY_MS
    #: backend와 나눠 갖는 내부 호출 시크릿 (KAN-36, :mod:`app.auth`). 비어 있으면 검사를
    #: 건너뛴다 - 로컬 개발 편의이고, 배포에서는 Terraform이 언제나 채운다
    internal_token: str | None = None
    #: 토큰이 없으면 기동을 거부한다 (KAN-36, 리뷰 반영). 배포 compose가 켠다 - SSM에서 토큰이 빠진
    #: 채 뜨면 검사를 건너뛰고(fail-open) health는 200이라 경보도 없기 때문이다. backend의
    #: DeploymentConfigGuard와 대칭인 fail-closed다
    internal_token_required: bool = False

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
            track1_src_dir=Path(
                source.get("ACCENTURY_AI_TRACK1_SRC_DIR", str(DEFAULT_TRACK1_SRC_DIR))
            ),
            track1_ref_dir=_optional_path(source.get("ACCENTURY_AI_TRACK1_REF_DIR")),
            track1_sentences=_optional_path(source.get("ACCENTURY_AI_TRACK1_SENTENCES")),
            track1_device=source.get("ACCENTURY_AI_TRACK1_DEVICE", "auto"),
            track1_load_timeout_seconds=float(
                source.get(
                    "ACCENTURY_AI_TRACK1_LOAD_TIMEOUT_SECONDS", DEFAULT_TRACK1_LOAD_TIMEOUT_SECONDS
                )
            ),
            fake_fail_item=source.get("ACCENTURY_AI_FAKE_FAIL_ITEM") or None,
            fake_delay_ms=int(source.get("ACCENTURY_AI_FAKE_DELAY_MS", DEFAULT_FAKE_DELAY_MS)),
            internal_token=source.get("ACCENTURY_AI_INTERNAL_TOKEN") or None,
            internal_token_required=_truthy(source.get("ACCENTURY_AI_INTERNAL_TOKEN_REQUIRED")),
        )


def _optional_path(value: str | None) -> Path | None:
    """비어 있으면 ``None`` - "전달본의 기본값을 쓴다"는 뜻이다."""
    return Path(value) if value else None


def _truthy(value: str | None) -> bool:
    """환경 변수의 불리언 - ``true``, ``1``, ``yes``(대소문자 무시)만 참이다."""
    return (value or "").strip().lower() in ("true", "1", "yes")
