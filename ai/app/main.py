"""FastAPI AI 분석 서버.

이 서버는 BE만 호출할 수 있는 사설망 서비스다 (§1.1, §4, NFR-SC-04) - 퍼블릭 인터넷에
노출하지 않는다. KAN-27 범위는 **원본 음성이 이 서버에 남지 않는 것**이고, 실제 추론과
``/internal/v0/models``는 KAN-22가 채운다. 추론은 :mod:`app.engine`의 어댑터 뒤에 있어
기동 시 한 번 고르면 라우트는 그것이 무엇인지 모른다 (KAN-135).
"""

from __future__ import annotations

import asyncio
import logging
import tempfile
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app import analyze
from app.auth import HEALTH_PATH, InternalTokenMiddleware
from app.config import Settings
from app.engine import AnalysisEngine, create_engine, require_reportable_version
from app.limits import MaxBodySizeMiddleware
from app.tempstore import VoiceTempStore

log = logging.getLogger(__name__)


def create_app(settings: Settings | None = None, engine: AnalysisEngine | None = None) -> FastAPI:
    """앱 1개를 만든다.

    ``engine``을 주면 설정이 지정한 엔진 대신 그것을 꽂는다 - 테스트가 가짜 엔진을
    밀어 넣는 자리다 (KAN-135 AC). 운영 경로는 언제나 설정에서 고른다.
    """
    resolved = settings or Settings.from_env()
    # 엔진은 기동 시 한 번만 만든다 - 실모델은 가중치 적재가 붙으므로 요청마다 만들 수 없다.
    # 주입된 엔진도 같은 검사를 지난다 - 기동 시 걸러야 첫 요청에서 BE 회로가 열리지 않는다
    resolved_engine = require_reportable_version(
        engine if engine is not None else create_engine(resolved)
    )
    store = VoiceTempStore(resolved.temp_dir, resolved.temp_retention_seconds)

    @asynccontextmanager
    async def lifespan(started: FastAPI):
        store.prepare()
        # 프로세스가 만드는 임시파일 전부를 전용 디렉터리로 몬다 - 웹 프레임워크가 큰
        # 업로드를 디스크로 스풀할 때도 공용 임시 디렉터리로 새지 않고, 따라서 청소 잡과
        # 권한 제한이 빠짐없이 닿는다 (KAN-27)
        previous_tempdir = tempfile.tempdir
        tempfile.tempdir = str(store.directory)
        # 기동 직후 남아 있는 것은 앞선 프로세스의 잔여물뿐이라 나이를 보지 않고 전부 지운다.
        # 전제는 "디렉터리 하나를 프로세스 하나가 전용으로 쓴다"이다 - uvicorn --workers로
        # 여러 프로세스를 한 디렉터리에 띄우면 이 정리가 형제 워커의 처리 중 파일을 지운다.
        # 이 서버는 GPU 때문에 프로세스 1개로 뜨는 것이 전제이고(KAN-36), 다중 워커가 필요해지면
        # 워커마다 ACCENTURY_AI_TEMP_DIR를 나눠 준다 (Codex sol 리뷰 P2 - 배포 제약으로 처리)
        # 디렉터리 훑기와 삭제는 블로킹 I/O다 - 잔여물이 많거나 볼륨이 느리면 이벤트
        # 루프가 그동안 멈춰 진행 중인 분석 요청이 전부 밀린다 (Codex 리뷰)
        await asyncio.to_thread(store.purge_leftovers)
        sweeper = asyncio.create_task(_sweep_forever(store, resolved.sweep_interval_seconds))
        try:
            # 준비 상태 게이트 (KAN-36). 엔진이 워밍업을 선언하면(가중치 적재 등, KAN-22) 그것이
            # 끝난 뒤에야 health가 UP이 된다 - 그 전까지 backend의 회로 복구 프로브와 compose
            # healthcheck는 503 STARTING을 보고 요청을 보내지 않는다. 스텁은 워밍업이 없다
            warm_up = getattr(resolved_engine, "warm_up", None)
            if callable(warm_up):
                await warm_up()
            started.state.ready = True
            if resolved.internal_token is None:
                # 배포에서는 있을 수 없는 상태다 - Terraform이 언제나 넣는다. 로컬 개발만 여기를 지난다
                log.warning("내부 호출 토큰이 없다 - 모든 호출을 받는다 (ACCENTURY_AI_INTERNAL_TOKEN, KAN-36)")
            log.info(
                "AI 분석 서버 기동 tempDir=%s engine=%s modelVersion=%s",
                store.directory,
                type(resolved_engine).__name__,
                resolved_engine.model_version,
            )
            yield
        finally:
            started.state.ready = False
            sweeper.cancel()
            with suppress(asyncio.CancelledError):
                await sweeper
            tempfile.tempdir = previous_tempdir

    app = FastAPI(title="Accentury AI", version="0.1.0", lifespan=lifespan)
    # 미들웨어는 나중에 더한 것이 바깥이다. 본문 상한이 가장 바깥에서 먼저 돌아야 인증 실패
    # 응답 전에 읽어 버리는 본문의 양이 유한하다 (app.auth) - 그래서 인증을 먼저 더한다
    app.add_middleware(InternalTokenMiddleware, token=resolved.internal_token)
    # 본문 상한은 multipart 파싱 전에 걸어야 의미가 있다 (Codex sol 리뷰 P2)
    app.add_middleware(MaxBodySizeMiddleware, max_bytes=resolved.max_request_bytes)
    app.state.settings = resolved
    app.state.temp_store = store
    app.state.engine = resolved_engine
    # lifespan이 워밍업을 마치기 전까지 False - health가 503을 낸다 (KAN-36 준비 상태 게이트)
    app.state.ready = False
    app.include_router(analyze.router)

    @app.get(HEALTH_PATH)
    async def health(request: Request) -> JSONResponse:
        """워밍업 상태 (§4.2). 토큰 없이 열려 있다 (app.auth).

        프로세스가 떴어도 lifespan의 워밍업이 끝나기 전에는 503 ``STARTING``이다 - backend는
        200 + ``UP``만 살아 있는 것으로 읽는다 (``RestAiAnalysisClient.healthy``). 모델 버전과
        ``/internal/v0/models``는 KAN-22.
        """
        if not request.app.state.ready:
            return JSONResponse(status_code=503, content={"status": "STARTING"})
        return JSONResponse(content={"status": "UP"})

    @app.get("/internal/v0/metrics")
    async def metrics(request: Request) -> dict[str, float | int]:
        """잔존 임시파일 수와 최장 잔존 시간 (KAN-27 AC, KAN-38이 소비)."""
        return request.app.state.temp_store.metrics()

    return app


async def _sweep_forever(store: VoiceTempStore, interval_seconds: float) -> None:
    """청소 잡 루프.

    스윕 하나가 실패해도 루프를 세우지 않는다 - 여기서 예외가 새면 남은 잔여물을 아무도
    지우지 않는다.

    파일시스템 작업은 :func:`asyncio.to_thread`로 넘긴다 - 코루틴에서 그대로 돌리면
    스윕이 끝날 때까지 이벤트 루프가 멈춰, 진행 중인 분석 요청이 BE의 읽기 타임아웃
    (10초)까지 밀리고 청소와 무관한 이유로 재전송 예산이 깎인다 (Codex 리뷰).
    """
    while True:
        await asyncio.sleep(interval_seconds)
        try:
            # 청소 전에 디렉터리부터 되살린다 - 없어진 채로 두면 mkstemp가 실패해 분석이
            # 전부 죽는다 (BE의 VoiceTempDirectory.ensureExists와 같은 자리다)
            await asyncio.to_thread(store.ensure_exists)
            await asyncio.to_thread(store.sweep)
        except Exception as error:  # noqa: BLE001 - 청소 잡은 어떤 실패로도 멈추지 않는다
            # 스택트레이스 대신 예외 종류만 남긴다 - 예외 메시지에 파일 경로가 실린다
            log.warning(
                "임시파일 청소 잡 실패 - 다음 주기에 다시 시도한다 reason=%s",
                type(error).__name__,
            )


app = create_app()
