"""``POST /internal/v0/analyze`` (API 명세서 §4.1).

이 엔드포인트에서 완성된 것은 **오디오의 수명 관리와 응답 봉투**다. 점수를 만드는 일은
:mod:`app.engine`의 어댑터 뒤에 있다. 실모델(전사, 정렬, 참조 거리, 점수)이 엔진 구현
하나로 들어올 때 이 파일은 한 줄도 고치지 않았다 (KAN-135, KAN-22).

경로는 엔진 종류와 무관하게 같다 - 받은 오디오를 파일로 한 번 내려놓고, 엔진에 상한을
걸어 넘기고, 응답을 만들고, 그 파일을 지운다. "무잔존", 추론 상한, 오디오 상한(413)이
이 자리에 남아 있어야 어떤 엔진이 와도 같은 보장이 걸린다 (KAN-27, KAN-135).
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, File, Form, Request, UploadFile
from fastapi.responses import JSONResponse

from app.engine import AnalysisOutcome, AnalysisRequest

log = logging.getLogger(__name__)

router = APIRouter()

ANALYZE_PATH = "/internal/v0/analyze"

#: 업로드를 임시파일로 옮길 때의 조각 크기
_CHUNK_BYTES = 64 * 1024

#: BE가 전파하는 추적 헤더 (§2.2) - 로그의 유일한 식별자다
CORRELATION_HEADER = "X-Correlation-Id"


@router.post(ANALYZE_PATH)
async def analyze(
    request: Request,
    audio: Annotated[UploadFile, File()],
    meta: Annotated[str, Form()],
) -> JSONResponse:
    """음성 1건을 분석한다 (BE 전용, 사설망).
    응답을 돌려준 뒤 이 서버에는 오디오가 남지 않는다 (§4.1, NFR-PR-03).
    """
    settings = request.app.state.settings
    store = request.app.state.temp_store
    engine = request.app.state.engine

    try:
        parsed = json.loads(meta)
        if not isinstance(parsed, dict):
            raise ValueError("meta는 JSON 객체여야 한다")
    except ValueError:
        # 계약 위반은 BE 버그다 - 오디오를 읽기 전에 끊는다
        return JSONResponse(status_code=400, content={"status": "FAILED", "detail": "meta 형식 오류"})

    correlation_id = request.headers.get(CORRELATION_HEADER) or parsed.get("correlationId", "")
    item_id = parsed.get("itemId", "")
    score_version = parsed.get("scoreVersion", "")

    started = time.monotonic()

    # 여기서부터 원본 음성이 디스크에 있다 - 어떤 경로로 빠져나가도 with 블록이 지운다.
    # 통째로 읽지 않고 조각내어 흘려보낸다 - 불변 bytes는 덮어쓸 수 없으니(모듈 주석)
    # 메모리에 올라오는 양과 시간을 줄이는 것이 유일한 통제 수단이다 (Codex sol 리뷰 P1)
    with store.temp_file() as path:
        size = await _spool(audio, path, settings.max_audio_bytes)
        if size is None:
            # 상한을 넘긴 요청 - BE가 §3.3에서 이미 끊지만 사설망이라고 무한정 받지 않는다
            # (Codex sol 리뷰 P2). 여기 오는 것은 BE 버그이므로 비재시도 4xx다.
            # 받기 전에 끊는 방어선은 본문 상한 미들웨어(app.limits)이고, 이 검사는
            # 파싱이 끝난 뒤의 파트 단위 확인이다 (Codex 리뷰 - _spool 주석 참고)
            log.warning("오디오 상한 초과 correlationId=%s itemId=%s", correlation_id, item_id)
            return JSONResponse(
                status_code=413, content={"status": "FAILED", "detail": "오디오 상한 초과"}
            )
        # 엔진 호출 구간만 따로 잰다 - 상한이 감싸는 것도 이 구간이다. 요청 전체(started)와
        # 견주면 멀티파트를 임시파일로 옮기는 시간까지 섞여, 예산 안에 끝난 엔진이 위반으로
        # 보인다 (KAN-135 리뷰 P2-B)
        engine_started = time.monotonic()
        try:
            # 추론에 상한을 건다 (Codex sol 리뷰 P1) - 호출자가 연결을 끊어도 서버 코루틴은
            # 자동으로 취소되지 않으므로, 멈춘 추론을 그대로 두면 임시파일 수명이 무한해진다.
            # 여기서 끊으면 with 블록이 파일을 지운다. 다만 **엔진 쪽 작업까지 멈춘다는
            # 보장은 없다** - asyncio.to_thread로 넘긴 추론은 취소되지 않아 워커가 계속
            # 돌고, 그 워커가 보는 오디오 파일은 이미 지워진 뒤다. GPU 슬롯도 그만큼
            # 붙들린다. 그래서 "취소가 실제로 닿을 것"이 엔진 쪽 계약이다
            # (app.engine.AnalysisEngine.analyze)
            async with asyncio.timeout(settings.analysis_timeout_seconds):
                # 추적 ID는 라우트가 정한 것 하나만 쓴다 - 엔진이 meta에서 따로 뽑으면
                # 헤더만 보내는 호출자에게 로그의 ID와 엔진이 본 ID가 갈린다 (§2.2)
                outcome = await engine.analyze(
                    AnalysisRequest(
                        audio_path=path, meta=parsed, correlation_id=correlation_id
                    )
                )
            # 프로토콜은 구조만 보므로 반환 타입은 런타임에 강제되지 않고, CI에도 타입
            # 체커가 없다. 속성 이름만 같은 객체를 돌려주면 AnalysisOutcome의 검사가
            # 통째로 우회돼 점수 999짜리 200이 그대로 나간다 - BE는 그것을 계약 위반으로
            # 끊고 재전송 없이 종결하므로(INTERNAL_ERROR) 사용자가 문항을 잃는다.
            # 500으로 끊으면 BE가 일시 장애로 보고 다시 시도한다
            if not isinstance(outcome, AnalysisOutcome):
                raise TypeError(
                    f"엔진이 AnalysisOutcome이 아닌 것을 돌려줬다: {type(outcome).__name__}"
                )
            engine_ms = round((time.monotonic() - engine_started) * 1000)
        except TimeoutError:
            processing_ms = round((time.monotonic() - started) * 1000)
            log.warning(
                "분석 시간 초과 correlationId=%s itemId=%s bytes=%d ms=%d",
                correlation_id,
                item_id,
                size,
                processing_ms,
            )
            # 5xx는 BE가 일시 장애로 보고 재전송 예산 안에서 다시 시도한다 (§4.1) -
            # 추론에 GPU를 이미 썼으므로 과부하 셰딩(429)이 아니다
            return JSONResponse(
                status_code=503,
                content={"status": "FAILED", "detail": "분석 시간 초과", "processingMs": processing_ms},
            )

    processing_ms = round((time.monotonic() - started) * 1000)
    # 예산을 넘겼는데 상한이 발화하지 않았다면, 엔진이 await 없이 돌아 asyncio.timeout이
    # 끼어들 틈을 주지 않은 것이다 (계약 위반, app.engine.AnalysisEngine.analyze). 원인까지
    # 단정하지는 않는다 - 여기서 확실한 것은 "예산을 넘겼는데 끊기지 않았다"까지다.
    # 끊지는 못해도 신호는 남긴다. 루프 차단 자체의 계측과 계약 강제는 KAN-137이다
    limit_ms = settings.analysis_timeout_seconds * 1000
    if engine_ms > limit_ms:
        log.error(
            "엔진이 예산을 넘겼는데 상한이 발화하지 않았다 "
            "correlationId=%s itemId=%s engineMs=%d limitMs=%d",
            correlation_id,
            item_id,
            engine_ms,
            round(limit_ms),
        )
    # 오디오 바이트도, 점수도 로그에 남기지 않는다 (§2.6, NFR-SC-07) - 크기와 추적 ID만이다
    log.info(
        "분석 종료 correlationId=%s itemId=%s bytes=%d status=%s ms=%d",
        correlation_id,
        item_id,
        size,
        outcome.status,
        processing_ms,
    )

    # 봉투 조립은 엔진 밖이다 (KAN-135) - scoreVersion과 processingMs는 엔진이 알 바가
    # 아니고, modelVersion은 설정이 아니라 엔진이 자기 정체로 보고한 값을 그대로 싣는다
    if outcome.failed:
        return JSONResponse(
            status_code=422,
            content={
                "status": "FAILED",
                "quality": {"code": outcome.quality_code},
                "retryable": outcome.retryable,
                "modelVersion": engine.model_version,
                "scoreVersion": score_version,
                "processingMs": processing_ms,
            },
        )
    return JSONResponse(
        status_code=200,
        content={
            "status": outcome.status,
            "intonationScore": outcome.intonation_score,
            "confidence": outcome.confidence,
            "quality": {"code": outcome.quality_code},
            "segments": list(outcome.segments),
            # 세션이 고정한 점수 버전을 그대로 되돌려준다 - BE가 불일치를 계약 위반으로
            # 끊으므로(§5.4), 엔진이 제 버전을 지어내면 전 요청이 INTERNAL_ERROR가 된다
            "scoreVersion": score_version,
            "modelVersion": engine.model_version,
            "processingMs": processing_ms,
        },
    )


async def _spool(upload: UploadFile, path: Path, max_bytes: int) -> int | None:
    """업로드를 조각내어 임시파일로 옮기고 바이트 수를 돌려준다.

    ``mkstemp``가 만든 파일을 여는 것이라 권한(0600)은 그대로 유지된다.

    상한을 넘기면 더 쓰지 않고 ``None``을 돌려준다. 다만 이것은 **파싱이 끝난 뒤의
    파트 단위 확인**이지 "받기 전에 끊는" 방어가 아니다 (Codex 리뷰) - 이 함수가 도는
    시점에는 Starlette가 이미 파트 전체를 버퍼링한 뒤라, 여기서 줄일 수 있는 것은
    디스크에 옮겨 적는 양뿐이다. 받기 전에 끊는 자리는 :mod:`app.limits`의 본문 상한
    미들웨어이고, 그쪽이 요청 전체(2MB)를 먼저 막는다.
    """
    total = 0
    with path.open("wb") as file:
        while chunk := await upload.read(_CHUNK_BYTES):
            total += len(chunk)
            if total > max_bytes:
                return None
            file.write(chunk)
    return total
