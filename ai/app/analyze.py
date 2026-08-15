"""``POST /internal/v0/analyze`` (API 명세서 §4.1) - KAN-27 스켈레톤.

이 엔드포인트에서 **완성된 것은 오디오의 수명 관리뿐**이다. 점수는 고정값 스텁이고,
실제 추론(F0 추출, guideF0 정렬, 점수 산출)은 KAN-22가 이 자리에 들어온다. 스텁이라도
경로는 실제와 같게 둔다 - 받은 오디오를 파일로 한 번 내려놓고, 응답을 만들고, 그 파일을
지운다. 그래야 KAN-22가 모델 호출만 갈아끼우면 되고, 지금 검증하는 "무잔존"이 그대로 남는다.
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
        try:
            # 추론에 상한을 건다 (Codex sol 리뷰 P1) - 호출자가 연결을 끊어도 서버 코루틴은
            # 자동으로 취소되지 않으므로, 멈춘 추론을 그대로 두면 임시파일 수명이 무한해진다.
            # 여기서 끊으면 with 블록이 파일을 지우고 GPU 슬롯도 돌아온다
            async with asyncio.timeout(settings.analysis_timeout_seconds):
                outcome = await _analyze_stub(path, item_id, settings)
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
    # 오디오 바이트도, 점수도 로그에 남기지 않는다 (§2.6, NFR-SC-07) - 크기와 추적 ID만이다
    log.info(
        "분석 종료 correlationId=%s itemId=%s bytes=%d status=%s ms=%d",
        correlation_id,
        item_id,
        size,
        outcome["status"],
        processing_ms,
    )

    if outcome["status"] == "FAILED":
        return JSONResponse(
            status_code=422,
            content={
                "status": "FAILED",
                "quality": {"code": outcome["qualityCode"]},
                "retryable": outcome["retryable"],
                "modelVersion": settings.stub_model_version,
                "scoreVersion": score_version,
                "processingMs": processing_ms,
            },
        )
    return JSONResponse(
        status_code=200,
        content={
            "status": "OK",
            "intonationScore": settings.stub_intonation_score,
            "confidence": 1.0,
            "quality": {"code": "OK"},
            "segments": [],
            # 세션이 고정한 점수 버전을 그대로 되돌려준다 - BE가 불일치를 계약 위반으로
            # 끊으므로(§5.4), 스텁이 제 버전을 지어내면 전 요청이 INTERNAL_ERROR가 된다
            "scoreVersion": score_version,
            "modelVersion": settings.stub_model_version,
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


async def _analyze_stub(path: Path, item_id: str, settings) -> dict:
    """KAN-22가 실제 추론으로 대체할 자리.

    지금은 파일을 실제로 한 번 읽어(모델이 그러듯) 고정 점수를 돌려준다.
    """
    if settings.stub_delay_ms:
        await asyncio.sleep(settings.stub_delay_ms / 1000)
    path.stat()
    if settings.stub_fail_item and item_id == settings.stub_fail_item:
        return {"status": "FAILED", "qualityCode": "AUDIO_TOO_QUIET", "retryable": True}
    return {"status": "OK"}
