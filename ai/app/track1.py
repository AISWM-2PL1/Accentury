"""트랙 1 실모델 어댑터 (KAN-22).

모델은 전달본 이미지 안에 있다 (ECR ``accentury/ai-model``, KAN-159). 이 파일은 그것을
:class:`app.engine.AnalysisEngine` 프로토콜에 맞춰 감싼 것뿐이고, 봉투 조립과 임시파일
수명, 추론 상한은 여전히 라우트가 쥐고 있다 (KAN-135).

## 왜 스레드가 아니라 별도 프로세스인가

엔진 계약 2번은 "취소가 실제로 닿아야 한다"이다 (:class:`app.engine.AnalysisEngine`).
전달본의 ``Track1Scorer.score``는 동기이고 안에서 Whisper 추론과 MFA 서브프로세스가
14~30초를 돈다 (KAN-159 실측). ``asyncio.to_thread``로 넘기면 라우트가 503을 내고
임시파일을 지운 뒤에도 스레드는 계속 돌며 이미 사라진 파일을 보고, 다음 요청은 그
스레드가 끝날 때까지 밀린다. 파이썬에는 스레드를 밖에서 멈추는 수단이 없으므로 취소가
닿는 유일한 길은 **프로세스를 죽이는 것**이다.

그래서 워커 프로세스 하나를 기동 시(``warm_up``) 띄워 모델을 한 번만 적재하고, 요청마다
파이프로 한 줄씩 주고받는다. 취소가 오면 워커를 프로세스 그룹째 죽인다 - MFA가 워커의
자식으로 도는 서브프로세스라 그룹째 죽이지 않으면 정렬 작업만 살아남는다.

**대가는 재적재다.** 죽인 뒤에는 다음 분석 전에 가중치를 다시 올려야 하고 그동안 요청은
기다린다. 그래서 ``ACCENTURY_AI_ANALYSIS_TIMEOUT_SECONDS``는 정상 추론이 걸리지 않을
만큼 넉넉해야 한다 (KAN-172의 재조정 대상). 상한을 P95 근처로 조이면 정상 요청이
서로의 재적재를 기다리는 형태로 무너진다.

## 프로토콜

부모 -> 자식 (stdin, 한 줄 JSON): ``{"audioPath": ..., "scriptKey": ...}``
자식 -> 부모 (stdout, 한 줄 JSON): 기동 직후 ``{"type": "ready", "modelVersion": ...}``,
그 뒤 요청마다 ``{"type": "result", ...}``.

자식은 fd 1을 stderr로 덮은 뒤 원래 stdout의 복제본으로만 프로토콜을 쓴다. 라이브러리가
표준출력에 한 줄이라도 찍으면(transformers의 진행 표시, MFA의 로그) 그것이 응답으로
읽혀 프로토콜이 통째로 어긋나기 때문이다.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
import shutil
import signal
import sys
import tempfile
from pathlib import Path
from typing import Any

from app.config import Settings
from app.engine import (
    JUDGED_QUALITY_CODES,
    QUALITY_OK,
    STATUS_OK,
    AnalysisOutcome,
    AnalysisRequest,
)

log = logging.getLogger(__name__)

#: 워커가 준비되기 전의 ``modelVersion``.
#:
#: 앱은 기동 시 :func:`app.engine.require_reportable_version`으로 이 값을 한 번 읽으므로
#: (가중치 적재 전이다) 자리가 비어 있으면 안 된다. **응답에는 실릴 수 없는 값이다** -
#: :meth:`Track1Engine.analyze`는 워커가 준비된 뒤에만 결과를 돌려주고, 그 시점에는 이미
#: 워커가 보고한 실제 버전으로 바뀌어 있다.
LOADING_MODEL_VERSION = "track1-loading"

#: 자식이 준비를 알리는 메시지 종류.
_READY = "ready"
#: 자식이 분석 1건의 결과를 알리는 메시지 종류.
_RESULT = "result"

#: 파이프 한 줄의 상한. 기본값(64KB)으로는 구간 피드백이 많은 문장에서 응답이 잘린다.
_PIPE_LIMIT_BYTES = 4 * 1024 * 1024

#: 모르는 ``scriptKey``에 자식이 붙이는 사유 (아래 :meth:`Track1Engine._outcome_of` 참고).
_UNKNOWN_SCRIPT_KEY = "unknown_script_key"
_ENGINE_ERROR = "error"

#: 전달본이 정렬 작업에 쓰는 임시 디렉터리의 접두사 (``serve.py``의 ``mfa_align_single``).
#:
#: 그 안에 **오디오 사본**(lab/u.wav)이 들어간다. 정상 경로는 ``with``가 지우지만 워커를
#: SIGKILL하면 통째로 남으므로, 죽인 뒤에 이 접두사를 우리가 지운다 (KAN-27 AC).
#: 임시 저장소는 디렉터리를 지우지 않고 잔존으로만 세므로(:mod:`app.tempstore`) 아무도
#: 치우지 않으면 그대로 남아 tempFiles 지표가 영영 0으로 돌아오지 않는다.
_WORKER_TEMP_PREFIX = "track1-"

#: MFA가 정렬 작업 폴더를 만드는 곳 (베이스 이미지의 ``MFA_ROOT_DIR``). 이 아래에 코퍼스
#: 작업 폴더가 남고 그 안에 오디오에서 뽑은 특징이 들어간다 - 요청마다 자식이 지운다.
_MFA_ROOT_ENV = "MFA_ROOT_DIR"
#: MFA 음향 모델이 있는 하위 폴더 - 위 정리에서 이것만 남긴다.
_MFA_KEEP = "pretrained_models"

#: 워커가 쓰는 캐시 디렉터리 이름 - 요청 데이터가 아니라 재사용하는 컴파일 캐시다.
#:
#: torch는 ``TMPDIR`` 아래에 ``torchinductor_<사용자>``를 만든다. 그 자리에 두면 두 가지가
#: 어긋난다. 청소 잡이 30분마다 지워 매번 다시 컴파일하게 되고(KAN-27의 임시 저장소는
#: 요청 오디오를 위한 곳이다), 임시 저장소가 디렉터리를 잔여물로 세어 ``tempFiles``가 영영
#: 0으로 돌아오지 않는다 - KAN-38의 잔존 경보가 그 값을 본다. 홈 아래로 뺀다.
_WORKER_CACHE_DIR = ".cache/accentury-ai"


class _WorkerGone(RuntimeError):
    """워커가 응답을 주지 못하고 사라졌다.

    취소로 우리가 죽인 경우가 아니라 밖에서 죽은 경우다 (호스트의 OOM 킬러, 전달본의
    치명적 오류 등). 요청 자체는 아직 정상이므로 :meth:`Track1Engine.analyze`가 새 워커로
    한 번 다시 시도한다.
    """


def _package_root() -> Path:
    """``app`` 패키지를 임포트할 수 있는 디렉터리 - 자식의 작업 디렉터리다."""
    return Path(__file__).resolve().parents[1]


def _worker_env(temp_dir: Path) -> dict[str, str]:
    """워커 프로세스의 환경 변수.

    ``TMPDIR``로 임시파일을 전용 디렉터리에 몬다 (KAN-27). 부모가 ``tempfile.tempdir``로
    돌려 둔 것은 부모 프로세스에만 걸리므로 자식에게는 환경 변수로 줘야 하고, 그러지 않으면
    전달본이 만드는 정렬 작업 폴더(오디오 사본이 들어간다)가 공용 ``/tmp``에 생겨 청소 잡도
    잔존 지표도 닿지 않는다.

    컴파일 캐시는 반대로 그 디렉터리 **밖**으로 뺀다 (:data:`_WORKER_CACHE_DIR`). 이미
    설정돼 있으면 그대로 둔다 - 배포가 정한 자리를 우리가 덮지 않는다.
    """
    cache = Path(os.environ.get("HOME", tempfile.gettempdir())) / _WORKER_CACHE_DIR
    env = {**os.environ, "TMPDIR": str(temp_dir)}
    for name, sub in (("TORCHINDUCTOR_CACHE_DIR", "torchinductor"), ("TRITON_CACHE_DIR", "triton")):
        env.setdefault(name, str(cache / sub))
    return env


async def _reap(process: asyncio.subprocess.Process, temp_dir: Path) -> None:
    """SIGKILL한 워커의 종료를 확인하고 그 워커가 남긴 것을 지운다.

    두 자리를 치운다. 전달본이 만든 정렬 작업 폴더(``temp_dir``의 ``track1-*``, 오디오
    사본이 든다)와 MFA가 ``MFA_ROOT_DIR``에 남긴 코퍼스 폴더(오디오에서 뽑은 특징이
    든다)다. 후자는 평소 자식이 요청 끝에 지우지만 **SIGKILL에는 그 코드가 돌지 못한다**
    (Codex sol 리뷰 P1) - 부모가 대신 지우지 않으면 다음 성공 요청 때까지, 재적재가 계속
    실패하면 무기한 남는다.

    순서가 중요하다 - 죽은 것을 확인한 뒤에 지워야 지우는 동안 워커가 같은 폴더에 다시
    쓰는 창이 없다.
    """
    try:
        await asyncio.wait_for(process.wait(), timeout=10)
    except (TimeoutError, ProcessLookupError):
        log.warning("워커가 시간 안에 사라지지 않았다 pid=%s", process.pid)
    await asyncio.to_thread(_purge_worker_temp, temp_dir)
    await asyncio.to_thread(_clear_mfa_workspace)


def _log_restart_failure(task: asyncio.Task[None]) -> None:
    """뒤에서 돈 재적재의 결과를 회수한다."""
    if task.cancelled():
        return
    error = task.exception()
    if error is not None:
        log.warning("워커 재적재 실패 - 다음 요청이 다시 시도한다 reason=%r", error)


def _purge_worker_temp(temp_dir: Path) -> None:
    """전달본이 남긴 정렬 작업 폴더를 지운다 (KAN-27).

    접두사로 좁힌다 - 같은 디렉터리에 라우트가 쥔 요청 오디오(``audio-*.wav``)가 함께 있고,
    그것의 수명은 라우트 몫이다.
    """
    try:
        leftovers = [
            entry for entry in temp_dir.iterdir()
            if entry.is_dir() and entry.name.startswith(_WORKER_TEMP_PREFIX)
        ]
    except OSError:
        return
    for entry in leftovers:
        # 경로를 로그에 남기지 않는다 - 건수만이다 (NFR-SC-07)
        shutil.rmtree(entry, ignore_errors=True)
    if leftovers:
        log.warning("워커가 남긴 정렬 작업 폴더를 지웠다 count=%d", len(leftovers))


class Track1Engine:
    """전달본 ``Track1Scorer``를 워커 프로세스 뒤에 두고 감싼 엔진.

    상속하지 않는다 - 라우트는 구조만 본다 (KAN-135).
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._model_version = LOADING_MODEL_VERSION
        self._process: asyncio.subprocess.Process | None = None
        # 시작은 한 번에 하나다. 워밍업과 요청이 동시에 워커를 띄우면 8GB 인스턴스에
        # 모델이 두 벌 올라가 그대로 OOM이다 (KAN-36의 c7i.xlarge)
        self._start_task: asyncio.Task[None] | None = None
        # 추론도 한 번에 하나다 - 워커가 한 줄씩 주고받는 구조이고, GPU 슬롯도 1이다
        self._inference_lock = asyncio.Lock()
        # 죽인 워커를 거두는 작업들 - 거두지 않으면 좀비가 남는다
        self._reaping: set[asyncio.Task[None]] = set()

    @property
    def model_version(self) -> str:
        return self._model_version

    async def warm_up(self) -> None:
        """가중치를 적재한다 (KAN-36 준비 상태 게이트).

        실패하면 예외가 그대로 올라가 health가 STARTING에 머문다 - 컨테이너가 unhealthy로
        남아 배포 파이프라인이 롤백한다.
        """
        await self._ensure_worker()

    async def close(self) -> None:
        """워커를 정리한다 - 앱 종료 시 lifespan이 부른다.

        부르지 않으면 워커가 살아남는다. 별도 세션으로 띄우기 때문에(``start_new_session``)
        부모가 죽어도 따라 죽지 않고, RSS 7GB대짜리 고아 프로세스가 호스트에 남는다.
        """
        # 적재 중이면 그것부터 접는다 - 남겨 두면 "종료 중에 올라온 워커"가 아무도 죽이지
        # 않는 채로 남고, 파이썬은 종료 시점에 미완 태스크를 경고로만 남긴다
        starting = self._start_task
        if starting is not None and not starting.done():
            starting.cancel()
            with contextlib.suppress(BaseException):
                await starting
        self._terminate_worker("앱 종료")
        if self._reaping:
            await asyncio.gather(*list(self._reaping), return_exceptions=True)

    async def analyze(self, request: AnalysisRequest) -> AnalysisOutcome:
        script_key = request.meta.get("scriptKey")
        # 문자열이 아닌 값은 없는 것과 같이 다룬다 - meta는 BE가 보낸 값 그대로라 타입이
        # 어긋날 수 있고, 그대로 넘기면 자식이 JSON 직렬화에서 터진다
        payload = {
            "audioPath": str(request.audio_path),
            "scriptKey": script_key if isinstance(script_key, str) else None,
        }
        async with self._inference_lock:
            await self._ensure_worker()
            try:
                reply = await self._exchange(payload)
            except asyncio.CancelledError:
                # 계약 2 - 취소가 실제로 닿는 유일한 수단이다. 여기서 죽이지 않으면 라우트가
                # 503을 내고 임시파일을 지운 뒤에도 워커는 사라진 파일을 계속 정렬한다
                self._terminate_worker("분석 취소")
                # 재적재를 곧바로 시작한다. 다음 요청이 그것을 자기 예산 안에서 기다리면
                # 적재(수십 초)와 추론이 한 예산에 겹쳐 또 상한에 걸리고, 그 취소가 다시
                # 워커를 죽인다 - 한 번의 초과가 계속 도는 형태로 굳는 자리다
                self._restart_in_background()
                raise
            except _WorkerGone as error:
                # 밖에서 죽은 워커다 (OOM 킬러 등). 한 번만 다시 시도한다 - 요청은 아직
                # 정상이고, 여기서 포기하면 500이 나가 BE의 재전송 예산이 깎인다.
                # 두 번째도 같으면 그대로 올려 500으로 끊는다 - 요청마다 죽는 워커에
                # 매번 재적재를 태우면 그것이 더 나쁘다
                log.warning("워커가 사라져 다시 띄운다 reason=%s", error)
                await self._ensure_worker()
                reply = await self._exchange(payload)
        return self._outcome_of(reply)

    # ── 워커 수명 ────────────────────────────────────────────────────────────

    async def _ensure_worker(self) -> None:
        """요청을 받을 수 있는 워커가 있게 만든다.

        여러 호출자가 동시에 들어와도 적재는 한 번이다. 먼저 온 쪽이 만든 시작 작업을
        나머지가 함께 기다린다.

        **적재 중이면 프로세스가 있어도 기다린다.** 취소 뒤에는 재적재가 뒤에서 돌고 있는데
        (:meth:`_restart_in_background`), 그 워커를 준비되기 전에 쓰면 준비 메시지를 기다리는
        코루틴과 이 요청이 같은 파이프를 동시에 읽는다 - asyncio가 그것을 거부해 요청이
        계약과 무관한 오류로 죽는다 (KAN-137 스위트가 잡은 자리).
        """
        starting = self._start_task
        if starting is not None and not starting.done():
            # shield로 감싼다 - 이 요청이 상한에 걸려 취소돼도 적재 자체는 이어져야 한다.
            # 취소마다 적재를 접으면 뒤이은 요청이 매번 처음부터 다시 올린다
            await asyncio.shield(starting)
            return
        if self._process is not None and self._process.returncode is None:
            return
        self._start_task = asyncio.create_task(self._start_worker())
        await asyncio.shield(self._start_task)

    async def _start_worker(self) -> None:
        spec = {
            "srcDir": str(self._settings.track1_src_dir),
            "refDir": str(self._settings.track1_ref_dir) if self._settings.track1_ref_dir else None,
            "sentences": (
                str(self._settings.track1_sentences) if self._settings.track1_sentences else None
            ),
            "device": self._settings.track1_device,
        }
        log.info("트랙 1 워커 기동 - 가중치 적재를 시작한다 spec=%s", spec)
        # 앞선 워커가 SIGKILL로 남긴 정렬 작업 폴더를 먼저 치운다 - 그 안에 오디오 사본이 있다
        _purge_worker_temp(self._settings.temp_dir)
        process = await asyncio.create_subprocess_exec(
            sys.executable,
            "-m",
            __name__,
            json.dumps(spec),
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            # stderr는 파이프로 받지 않는다 - Whisper와 MFA가 쏟는 양이 많아 아무도 읽지
            # 않는 파이프가 차면 워커가 그 자리에서 멈춘다. 컨테이너 로그로 그대로 흘린다
            stderr=None,
            # 별도 세션이라 프로세스 그룹째 죽일 수 있다 - MFA가 워커의 자식이다
            start_new_session=True,
            cwd=str(_package_root()),
            # 자식의 임시파일 자리와 캐시 자리를 함께 정한다 (아래 :func:`_worker_env`)
            env=_worker_env(self._settings.temp_dir),
            limit=_PIPE_LIMIT_BYTES,
        )
        try:
            async with asyncio.timeout(self._settings.track1_load_timeout_seconds):
                message = await self._read_message(process)
        except BaseException:
            # 취소든 상한이든 임포트 실패든 결과는 같다 - 반쯤 올라온 워커를 남기지 않는다
            self._kill(process, "가중치 적재 실패")
            raise
        if message.get("type") != _READY or not message.get("modelVersion"):
            self._kill(process, "워커가 준비를 알리지 않았다")
            raise RuntimeError(f"트랙 1 워커의 준비 메시지가 계약과 다르다: {message}")
        # **여기서야 공개한다.** 준비 전에 :attr:`_process`에 넣으면 적재를 기다리는 이
        # 코루틴과 요청 코루틴이 같은 stdout을 동시에 읽는다 (위 :meth:`_ensure_worker` 주석)
        self._process = process
        self._model_version = str(message["modelVersion"])
        log.info("트랙 1 워커 준비 완료 modelVersion=%s", self._model_version)

    def _terminate_worker(self, reason: str) -> None:
        """지금 쓰는 워커를 죽이고 자리를 비운다."""
        process = self._process
        self._process = None
        self._kill(process, reason)

    def _kill(self, process: asyncio.subprocess.Process | None, reason: str) -> None:
        """프로세스 하나를 그룹째 죽인다 - 동기 함수인 것이 중요하다.

        취소 처리 안에서 부르므로 여기서 ``await``하면 두 번째 취소가 끼어들어 죽이다 만
        상태로 빠져나갈 수 있다.
        """
        if process is None or process.returncode is not None:
            return
        log.warning("트랙 1 워커를 종료한다 reason=%s pid=%s", reason, process.pid)
        try:
            # 프로세스 그룹째 SIGKILL이다. 정중한 종료를 기다릴 수 없다 - 취소 시점의
            # 워커는 추론이나 MFA 정렬 한가운데이고, 그 자리에서 SIGTERM을 받아 정리하는
            # 경로가 전달본에 없다. 그룹째인 이유는 MFA가 워커의 자식이기 때문이다
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
        except (ProcessLookupError, PermissionError) as error:
            log.warning("워커 그룹 종료 실패 reason=%s - 프로세스만 죽인다", type(error).__name__)
            try:
                process.kill()
            except ProcessLookupError:
                return
        self._reap_later(process)

    def _restart_in_background(self) -> None:
        """죽인 워커를 뒤에서 다시 올린다 - 적재를 다음 요청의 예산에서 빼기 위해서다.

        실패는 여기서 삼킨다. 다음 요청의 :meth:`_ensure_worker`가 어차피 다시 만들고,
        그 요청은 실패를 응답으로 받는다 - 아무도 기다리지 않는 이 작업이 예외로 끝나면
        파이썬이 "회수되지 않은 예외" 경고만 남긴다.
        """
        if self._start_task is not None and not self._start_task.done():
            return
        try:
            task = asyncio.get_running_loop().create_task(self._start_worker())
        except RuntimeError:
            return
        self._start_task = task
        task.add_done_callback(_log_restart_failure)

    def _reap_later(self, process: asyncio.subprocess.Process) -> None:
        """죽인 워커를 뒤에서 거두고 잔여물을 치운다 - 거두지 않으면 좀비가 남는다."""
        try:
            task = asyncio.get_running_loop().create_task(
                _reap(process, self._settings.temp_dir)
            )
        except RuntimeError:  # 루프 밖(인터프리터 종료 등)에서는 거둘 사람이 없다
            return
        self._reaping.add(task)
        task.add_done_callback(self._reaping.discard)

    # ── 프로토콜 ────────────────────────────────────────────────────────────

    async def _exchange(self, payload: dict[str, Any]) -> dict[str, Any]:
        process = self._process
        if process is None or process.stdin is None:
            raise _WorkerGone("트랙 1 워커가 없다")
        try:
            process.stdin.write(json.dumps(payload).encode("utf-8") + b"\n")
            await process.stdin.drain()
        except (BrokenPipeError, ConnectionResetError) as error:
            self._terminate_worker("요청을 보내지 못했다")
            raise _WorkerGone(f"워커에 요청을 보내지 못했다: {type(error).__name__}") from error
        message = await self._read_message(process)
        if message.get("type") != _RESULT:
            raise RuntimeError(f"트랙 1 워커의 응답이 계약과 다르다: {message.get('type')!r}")
        return message

    async def _read_message(self, process: asyncio.subprocess.Process) -> dict[str, Any]:
        """자식이 보낸 한 줄을 읽는다.

        빈 줄(EOF)은 워커가 죽었다는 뜻이다. 적재 중이면 워밍업 실패이고(health는
        STARTING에 머문다), 요청 중이면 :meth:`analyze`가 새 워커로 한 번 다시 시도한다.
        """
        assert process.stdout is not None
        line = await process.stdout.readline()
        if not line:
            code = process.returncode
            if process is self._process:
                self._process = None
            self._kill(process, "워커가 응답 전에 종료됐다")
            raise _WorkerGone(f"트랙 1 워커가 응답 없이 종료됐다 (exit={code})")
        return json.loads(line)

    # ── 결과 변환 ────────────────────────────────────────────────────────────

    def _outcome_of(self, reply: dict[str, Any]) -> AnalysisOutcome:
        """자식이 준 봉투 재료를 :class:`AnalysisOutcome`으로 옮긴다.

        축과 계산은 전달본 그대로이고 여기서는 이름만 바꿔 담는다 (KAN-159의 ``serve.py``).
        """
        if not reply.get("ok"):
            if reply.get("kind") == _UNKNOWN_SCRIPT_KEY:
                # 정의에 scriptKey가 없거나 서비스 문장이 아닌 키다 (KAN-182 계약).
                # **재전송으로 풀리지 않는다** - 같은 정의로 다시 보내면 결과도 같으므로
                # retryable을 켜면 BE가 예산이 마를 때까지 같은 요청을 반복한다 (2026-09-05 결정)
                log.warning("서비스 문장이 아닌 scriptKey - 비재전송으로 끊는다")
                return AnalysisOutcome.failure(quality_code="ANALYSIS_MISREAD", retryable=False)
            # 그 밖의 실패는 모델 내부 오류다 - 500으로 올려 BE의 재전송에 맡긴다.
            # 판정 실패(422)로 접으면 사용자가 고칠 수 없는 이유로 문항을 잃는다.
            # 자식이 보내는 message는 예외의 **종류**뿐이다 (발화 내용이 섞이지 않는다)
            raise RuntimeError(f"트랙 1 추론 실패: {reply.get('message', '알 수 없음')}")

        envelope = reply["envelope"]
        if envelope.get("status") != STATUS_OK:
            code = (envelope.get("quality") or {}).get("code")
            if code not in JUDGED_QUALITY_CODES:
                # 모르는 코드를 그대로 실으면 BE가 계약 위반으로 끊고 그 문항은 재시도 없이
                # 죽는다 (§2.4). 조용히 바꿔 담지 않고 신호를 남긴다
                raise RuntimeError(f"모델이 §2.4에 없는 품질 코드를 냈다: {code!r}")
            return AnalysisOutcome.failure(quality_code=code, retryable=bool(envelope["retryable"]))
        return AnalysisOutcome.ok(
            intonation_score=int(envelope["intonationScore"]),
            confidence=float(envelope["confidence"]),
            quality_code=envelope.get("quality", {}).get("code") or QUALITY_OK,
            segments=envelope.get("segments") or (),
        )


# ── 워커 프로세스 ────────────────────────────────────────────────────────────


def _worker_main(argv: list[str]) -> int:
    """자식 프로세스의 진입점 (``python -m app.track1 '<spec>'``).

    부모의 임포트 경로를 그대로 쓰지만 FastAPI는 건드리지 않는다 - 여기서 하는 일은
    전달본을 적재하고 한 줄씩 채점하는 것뿐이다.
    """
    # fd 1을 stderr로 덮는다. 전달본이 쓰는 라이브러리(transformers, MFA)가 표준출력에
    # 찍는 한 줄이 그대로 응답으로 읽히면 프로토콜이 통째로 어긋난다
    channel_fd = os.dup(1)
    os.dup2(2, 1)
    channel = os.fdopen(channel_fd, "w", encoding="utf-8", buffering=1)
    sys.stdout = sys.stderr

    spec = json.loads(argv[1])
    # 전달본의 모듈 경로 - 이미지 안에서는 /app/src다 (KAN-159의 이미지 배치)
    sys.path.insert(0, spec["srcDir"])
    from scoring.serve import Track1Scorer  # noqa: PLC0415 - 자식에서만 필요한 무거운 임포트

    kwargs: dict[str, Any] = {"whisper_device": spec.get("device") or "auto"}
    # 주지 않은 값은 전달본의 기본값을 그대로 쓴다 - 이미지 안에서는 그 기본값이 곧
    # 같이 실린 참조와 문장 목록이라, 경로를 우리가 다시 적어 두면 이미지를 갈아끼울 때
    # 두 곳이 어긋난다 (모델 해시 태그가 참조와 목록을 함께 옮긴다)
    if spec.get("refDir"):
        kwargs["ref_dir"] = Path(spec["refDir"])
    if spec.get("sentences"):
        kwargs["sentences"] = Path(spec["sentences"])
    scorer = Track1Scorer(**kwargs)

    _send(channel, {"type": _READY, "modelVersion": scorer.model_version})

    # ``for line in sys.stdin``을 쓰지 않는다 - 이터레이터는 앞질러 읽어 버퍼가 찰 때까지
    # 기다릴 수 있고, 그러면 요청 하나를 보낸 부모가 응답을 영영 못 받는다
    while True:
        line = sys.stdin.readline()
        if not line:  # 부모가 파이프를 닫았다
            return 0
        line = line.strip()
        if not line:
            continue
        request = json.loads(line)
        try:
            envelope = scorer.score(request["audioPath"], request["scriptKey"])
        except KeyError:
            # 서비스 문장이 아닌 키 - 전달본이 입력 자체를 계약 밖으로 보고 던지는 유일한 예외다
            _send(channel, {"type": _RESULT, "ok": False, "kind": _UNKNOWN_SCRIPT_KEY})
        except Exception as error:  # noqa: BLE001 - 어떤 실패든 부모가 500으로 옮긴다
            # **예외 메시지를 싣지 않는다** (§2.6, NFR-SC-07). 전사(사용자가 말한 내용)가
            # 예외 문자열에 실릴 수 있고, 부모는 이 값을 예외로 다시 던져 스택트레이스와
            # 함께 컨테이너 로그에 남긴다 - 그 경로로 발화 내용이 로그에 고이는 자리다
            # (Codex sol 리뷰 P1). 종류만으로도 어느 단계에서 깨졌는지는 좁혀진다
            _send(
                channel,
                {
                    "type": _RESULT,
                    "ok": False,
                    "kind": _ENGINE_ERROR,
                    "message": type(error).__name__,
                },
            )
        else:
            # 응답보다 먼저 지운다 - 부모가 이 줄을 읽는 순간 라우트는 응답을 만들 수 있고,
            # "응답 반환 후 남지 않는다"(KAN-27 AC)가 그 순간 성립해야 한다 (Codex sol 리뷰 P2).
            # finally의 같은 호출은 실패 경로를 위한 그물로 남는다
            _clear_mfa_workspace()
            try:
                _send(channel, {"type": _RESULT, "ok": True, "envelope": envelope})
            except (TypeError, ValueError) as error:
                # 봉투에 JSON으로 나갈 수 없는 값이 있다 (NaN, 알 수 없는 객체). 여기서 잡지
                # 않으면 이 예외가 워커를 죽여, 부모에게는 "워커가 응답 없이 사라졌다"로만
                # 보인다 - 원인이 로그 어디에도 남지 않는 종류의 실패다
                _send(
                    channel,
                    {
                        "type": _RESULT,
                        "ok": False,
                        "kind": _ENGINE_ERROR,
                        # 여기서도 메시지 본문은 싣지 않는다 - 직렬화 오류 문자열에 값이 실린다
                        "message": f"봉투를 직렬화할 수 없다: {type(error).__name__}",
                    },
                )
        finally:
            _clear_mfa_workspace()
    return 0


def _clear_mfa_workspace() -> None:
    """MFA가 남긴 코퍼스 작업 폴더를 지운다 (KAN-27).

    MFA는 ``MFA_ROOT_DIR`` 아래에 정렬 작업 폴더를 만들고 거기에 오디오에서 뽑은 특징을
    남긴다. ``--clean``은 다음 실행이 시작될 때 지우는 것이라 그사이에는 남아 있고, 응답을
    돌려준 뒤에는 아무것도 남지 않아야 한다는 것이 이 티켓의 인수 조건이다. 음향 모델
    (``pretrained_models``)만 남긴다.
    """
    root = Path(os.environ.get(_MFA_ROOT_ENV, "/opt/mfa"))
    try:
        entries = list(root.iterdir())
    except OSError:
        return
    for entry in entries:
        if entry.name == _MFA_KEEP:
            continue
        if entry.is_dir():
            shutil.rmtree(entry, ignore_errors=True)
        else:
            try:
                entry.unlink()
            except OSError:
                pass


def _send(channel: Any, message: dict[str, Any]) -> None:
    """한 줄 JSON을 부모에게 보낸다.

    ``allow_nan``은 부모 쪽 검사(:meth:`app.engine.AnalysisOutcome._require_serializable`)와
    같은 False로 맞춘다 - NaN은 JSON 표준이 아니라 봉투를 만드는 시점에 어차피 막힌다.
    여기서 걸러야 실패가 "이 문항의 결과에 NaN이 있다"로 남는다.
    """
    channel.write(json.dumps(message, ensure_ascii=False, allow_nan=False, default=_jsonable) + "\n")
    channel.flush()


def _jsonable(value: Any) -> Any:
    """``json``이 모르는 값을 파이썬 기본형으로 접는다.

    전달본의 구간 피드백은 numpy 위에서 만들어진다 - ``numpy.float32``와 ``numpy.int64``는
    파이썬 ``float``/``int``의 하위 타입이 아니라 ``json.dumps``가 그대로 거절한다
    (KAN-135가 예고한 자리). 스칼라는 ``item()``, 배열은 ``tolist()``로 접는다.
    """
    for method in ("item", "tolist"):
        converter = getattr(value, method, None)
        if callable(converter):
            return converter()
    raise TypeError(f"직렬화할 수 없는 값이다: {type(value).__name__}")


if __name__ == "__main__":
    raise SystemExit(_worker_main(sys.argv))
