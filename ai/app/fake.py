"""개발 기계용 가짜 엔진 - 실모델 없이 스택을 띄우는 유일한 길 (KAN-22, PR #87 리뷰 반영).

실모델(:mod:`app.track1`)은 베이스 이미지의 전달본이 있어야만 돌고, 그 이미지는 linux/amd64에
RSS 7GB대라 개발 기계와 CI 러너에서 뜨지 않는다. 스텁을 지운 것은 2026-09-05의 팀 결정이지만
그 결과 앱과 웹의 로컬 완주 확인(에뮬레이터, ``web/e2e``의 ``full-run``, ``retake``)이 돌릴
스택을 잃었다. 이 엔진은 그 자리만 메운다 - ``ACCENTURY_AI_ANALYSIS_ENGINE=fake``로 명시해야
켜지고, 배포 compose가 켜는 ``ACCENTURY_AI_INTERNAL_TOKEN_REQUIRED=true``와는 양립하지 않아
기동이 거부된다. "여기서 되던 것이 배포에서 처음 실패하는 자리"를 늘리지 않으려는 장치다.

**추론이 아니다.** 오디오는 파일이 있는지만 만지고 한 바이트도 보지 않는다. 점수는
``correlationId``의 해시라 같은 요청은 언제나 같은 점수이고(BE 재전송 멱등, E2E 재현성),
0~100을 고르게 덮어 5등급이 전부 관측된다 (결과 화면을 등급마다 볼 수 있어야 한다).
"""

from __future__ import annotations

import asyncio
import hashlib

from app.config import Settings
from app.engine import AnalysisOutcome, AnalysisRequest

#: 가짜 엔진의 정체. 설정으로 바꾸지 않는다 - 이 값이 응답에 보이면 가짜가 돌고 있다는 뜻이어야 한다.
FAKE_MODEL_VERSION = "fake-0.1"

#: 억양 원점수의 상한 (§4.3의 0~100 스케일). 해시를 이 폭에 접는다.
_MAX_SCORE = 100


class FakeEngine:
    """:class:`app.engine.AnalysisEngine` 프로토콜만 맞춘다 - 상속하지 않는다 (KAN-135)."""

    def __init__(self, settings: Settings) -> None:
        if settings.internal_token_required:
            # 배포 compose(infra/modules/ai-host)가 켜는 값이다. 가짜 엔진이 그 환경에서 뜨면 사용자가
            # 해시 점수를 받는다 - 기동을 세워 배포 파이프라인이 롤백하게 한다
            raise ValueError(
                "가짜 엔진은 배포 환경에서 뜰 수 없다 - ACCENTURY_AI_INTERNAL_TOKEN_REQUIRED가 켜져 있다"
            )
        self._delay_seconds = settings.fake_delay_ms / 1000
        self._fail_item = settings.fake_fail_item

    @property
    def model_version(self) -> str:
        return FAKE_MODEL_VERSION

    @staticmethod
    def hashed_score(correlation_id: str) -> int:
        """correlationId 하나를 0~100의 억양 원점수로 접는다.

        해시는 ``blake2b``다 - 파이썬 내장 ``hash()``는 프로세스마다 시드가 달라 서버를 다시
        띄우면 같은 요청이 다른 점수를 낸다. 101로 나눈 나머지라 0~100 어느 값도 같은 확률이다.
        한 세션의 다섯 요청에 같은 ``X-Correlation-Id``를 고정하면 다섯 문항이 같은 점수가 되어
        세션 점수를 원하는 등급으로 끌 수 있다 - 특정 등급 화면을 재현하는 수단이다.
        """
        digest = hashlib.blake2b(correlation_id.encode("utf-8"), digest_size=8).digest()
        return int.from_bytes(digest, "big") % (_MAX_SCORE + 1)

    async def analyze(self, request: AnalysisRequest) -> AnalysisOutcome:
        if self._delay_seconds:
            await asyncio.sleep(self._delay_seconds)
        # 모델이 그러듯 파일을 실제로 한 번 만진다 - 라우트가 넘긴 경로가 살아 있는지까지 본다
        request.audio_path.stat()
        if self._fail_item and request.item_id == self._fail_item:
            return AnalysisOutcome.failure(quality_code="AUDIO_TOO_QUIET", retryable=True)
        return AnalysisOutcome.ok(intonation_score=self.hashed_score(request.correlation_id))
