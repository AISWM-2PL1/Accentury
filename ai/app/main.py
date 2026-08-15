"""FastAPI AI 분석 서버 (KAN-27 스켈레톤).

이 서버는 BE만 호출할 수 있는 사설망 서비스다 (§1.1, §4, NFR-SC-04) - 퍼블릭 인터넷에
노출하지 않는다. KAN-27 범위는 **원본 음성이 이 서버에 남지 않는 것**이고, 실제 추론과
``/internal/v0/models``는 KAN-22가 채운다.
"""

from __future__ import annotations

import asyncio
import logging
import tempfile
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI, Request

from app import analyze
from app.config import Settings
from app.limits import MaxBodySizeMiddleware
from app.tempstore import VoiceTempStore

log = logging.getLogger(__name__)


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved = settings or Settings.from_env()
    store = VoiceTempStore(resolved.temp_dir, resolved.temp_retention_seconds)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
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
        log.info("AI 분석 서버 기동 tempDir=%s", store.directory)
        try:
            yield
        finally:
            sweeper.cancel()
            with suppress(asyncio.CancelledError):
                await sweeper
            tempfile.tempdir = previous_tempdir

    app = FastAPI(title="Accentury AI", version="0.1.0", lifespan=lifespan)
    # 본문 상한은 multipart 파싱 전에 걸어야 의미가 있다 (Codex sol 리뷰 P2)
    app.add_middleware(MaxBodySizeMiddleware, max_bytes=resolved.max_request_bytes)
    app.state.settings = resolved
    app.state.temp_store = store
    app.include_router(analyze.router)

    @app.get("/internal/v0/health")
    async def health() -> dict[str, str]:
        """워밍업 상태 (§4.2) - 스켈레톤은 프로세스 생존만 알린다. 모델 상태는 KAN-22."""
        return {"status": "UP"}

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
