#!/usr/bin/env python3
"""Fargate 태스크 종료와 오토스케일링 내결함성 실증용 부하 생성기 (KAN-169).

    scripts/load_resilience.py --base-url https://staging.accentury.app \
        --admin-token "$(aws ssm get-parameter --with-decryption \
            --name /accentury/staging/ACCENTURY_ADMIN_TOKEN --query Parameter.Value --output text)" \
        --get-rps 25 --examinees 4 --duration 1200 \
        --events scale-out.events.jsonl --report scale-out.report.json

두 종류의 부하를 같이 건다.

* **정의 조회 GET** (``--get-rps``): CloudFront 경유 ``GET /v0/tests/{testVersion}``을 초당 N건.
  오토스케일링 목표 추적(``ALBRequestCountPerTarget``, KAN-168)을 밀어 올리는 가벼운 요청이다.
* **응시자** (``--examinees``): 세션 생성, 음성 5문항 업로드(202를 받으면 추론을 기다리지 않고 다음
  문항으로 - 앱의 "다음" 버튼과 같다), 일괄 상태 폴링, 실패 문항 재업로드(재녹음), 완료 확정까지를
  응시자 수만큼의 스레드가 쉬지 않고 반복한다. 태스크를 강제 종료하는 순간에 PROCESSING인
  ``AnalysisJob``이 있어야 종료 내결함성 실증이 성립하기 때문이다 (티켓 Requirements).

모든 응답의 상태 코드를 종류별로 세고(``--report-interval``마다 한 줄, 끝나면 JSON 요약), 2xx가
아닌 응답과 연결 실패, 문항 실패와 재업로드 성공은 시각과 함께 이벤트 파일(``--events``)에
한 줄씩 남긴다. "강제 종료 구간에서 5xx 0건"과 "진행 중이던 분석이 완료되거나 실패로 격리되고
재녹음이 성공한다"는 AC를 이 두 파일로 판정한다.

요청 제한(명세서 §2.5)을 스스로 지킨다. IP당 분당 세션 생성 30건과 업로드 30건이라 기본값은 그
아래(``--sessions-per-minute`` 20, ``--uploads-per-minute`` 28)이고 토큰 버킷 용량이 1이라 어느
60초 창에서도 21건과 29건을 넘지 않는다. 그래도 만나는 429는 물러섰다가 다시 시도하되 횟수로
센다. 정의 조회에는 IP 제한이 없다 (KAN-168 실측이 초당 25건으로 돌았다). 상한은 태스크(인스턴스)별이라
태스크가 늘면 실효 상한도 늘지만 기본값은 태스크 1개 기준으로 잡는다.

세션은 ``--admin-token``으로 합성 트래픽 표시를 한다 (KAN-138). 표시 없이 staging이나 prod를
두드리면 익명 집계(완주율, 등급 분포)가 영구히 오염되므로 로컬이 아니면 토큰 없이 돌지 않는다.

HTTP와 오디오 조립은 ``e2e_smoke.py``의 것을 그대로 쓴다 - 표준 라이브러리만 쓰고 파일을
복사해 어디서든 돌릴 수 있다는 배포 방식도 같다 (같은 디렉터리에 두 파일이 있어야 한다).
"""

from __future__ import annotations

import argparse
import collections
import datetime
import json
import os
import signal
import sys
import threading
import time
import urllib.parse
import uuid
from typing import Any, Dict, List, Optional, Sequence

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import e2e_smoke as smoke  # noqa: E402

#: 이벤트와 요약의 시각은 로컬 시간대(KST) ISO 8601이다 - CloudWatch 경보 이력, 스케일링 활동,
#: 컨테이너 로그(KST로 읽는다)와 초 단위로 맞춰 보기 위해서다.
LOCAL_TZ = datetime.datetime.now().astimezone().tzinfo


def now_iso() -> str:
    return datetime.datetime.now(LOCAL_TZ).isoformat(timespec="milliseconds")


def clock() -> str:
    return datetime.datetime.now(LOCAL_TZ).strftime("%H:%M:%S")


# ---------------------------------------------------------------------------
# 속도 제한과 집계
# ---------------------------------------------------------------------------


class TokenBucket:
    """초당 ``rate``건, 최대 ``burst``건까지 몰아 보내는 토큰 버킷. 스레드가 여럿이어도 합이 rate다."""

    def __init__(self, rate: float, burst: float) -> None:
        self.rate = rate
        self.capacity = max(1.0, burst)
        self.tokens = self.capacity
        self.updated = time.monotonic()
        self.lock = threading.Lock()

    def acquire(self, stop: threading.Event) -> bool:
        """토큰 하나를 받을 때까지 기다린다. 중단 신호가 오면 False다."""
        while not stop.is_set():
            with self.lock:
                now = time.monotonic()
                self.tokens = min(self.capacity, self.tokens + (now - self.updated) * self.rate)
                self.updated = now
                if self.tokens >= 1:
                    self.tokens -= 1
                    return True
                wait = (1 - self.tokens) / self.rate
            stop.wait(min(wait, 0.5))
        return False


class Tally:
    """(종류, 상태) 건수와 이벤트를 모으는 스레드 안전 집계. 상태는 HTTP 코드거나 ``ERR``(연결 실패)다."""

    def __init__(self, events_path: Optional[str]) -> None:
        self.lock = threading.Lock()
        self.total: Dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
        self.window: Dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
        self.latency_ms: Dict[str, List[float]] = collections.defaultdict(list)
        self.gauges: Dict[str, int] = {}
        self.event_counts: collections.Counter = collections.Counter()
        self.events_file = open(events_path, "a", encoding="utf-8") if events_path else None

    def record(self, kind: str, status: Any, latency_ms: float) -> None:
        key = str(status)
        with self.lock:
            self.total[kind][key] += 1
            self.window[kind][key] += 1
            self.latency_ms[kind].append(latency_ms)

    def gauge(self, name: str, value: int) -> None:
        with self.lock:
            self.gauges[name] = value

    def event(self, name: str, **fields: Any) -> None:
        """시각이 붙은 이벤트 한 줄. 2xx 아닌 응답, 문항 실패, 재업로드 결과, 세션 결과가 여기 남는다."""
        record = {"time": now_iso(), "event": name}
        record.update(fields)
        with self.lock:
            self.event_counts[name] += 1
            if self.events_file:
                self.events_file.write(json.dumps(record, ensure_ascii=False) + "\n")
                self.events_file.flush()

    def snapshot_window(self) -> Dict[str, collections.Counter]:
        with self.lock:
            window = self.window
            self.window = collections.defaultdict(collections.Counter)
            return window

    def summary(self) -> Dict[str, Any]:
        with self.lock:
            counts = {kind: dict(sorted(counter.items())) for kind, counter in self.total.items()}
            latency = {}
            for kind, values in self.latency_ms.items():
                ordered = sorted(values)
                if ordered:
                    latency[kind] = {
                        "count": len(ordered),
                        "p50_ms": round(ordered[len(ordered) // 2], 1),
                        "p95_ms": round(ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))], 1),
                        "max_ms": round(ordered[-1], 1),
                    }
            five_xx = sum(
                n for counter in self.total.values() for status, n in counter.items()
                if status.startswith("5")
            )
            errors = sum(counter.get("ERR", 0) for counter in self.total.values())
            return {
                "status_counts": counts,
                "latency": latency,
                "events": dict(self.event_counts),
                "http_5xx_total": five_xx,
                "connection_errors_total": errors,
            }

    def close(self) -> None:
        if self.events_file:
            self.events_file.close()


def is_failure_status(status: Any) -> bool:
    return status == "ERR" or int(status) >= 400


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------


class LoadClient(smoke.Client):
    """429를 스스로 물러서지 않는 클라이언트 - 물러서기는 워커가 하고, 429도 건수로 남겨야 한다."""

    def request(  # type: ignore[override]
        self,
        method: str,
        path: str,
        *,
        headers: Optional[Dict[str, str]] = None,
        body: Optional[bytes] = None,
        content_type: Optional[str] = None,
        correlation_id: Optional[str] = None,
    ) -> smoke.Response:
        sent: Dict[str, str] = {"Accept": "application/json"}
        if content_type:
            sent["Content-Type"] = content_type
        sent[smoke.CORRELATION_HEADER] = correlation_id or smoke.new_correlation_id()
        if headers:
            sent.update(headers)
        return self._send(method, path, sent, body)


class Load:
    """워커들이 공유하는 것 - 클라이언트, 집계, 속도 제한, 중단 신호."""

    def __init__(self, args: argparse.Namespace, tally: Tally) -> None:
        self.args = args
        self.tally = tally
        self.client = LoadClient(args.base_url, args.request_timeout, verbose=False)
        self.stop = threading.Event()
        self.get_bucket = TokenBucket(args.get_rps, burst=max(1.0, args.get_rps))
        # 버킷 용량은 1이다. 용량 n이면 어느 60초 창에서든 최대 n + 분당 속도 건이 나가므로, 기본값
        # (세션 20, 업로드 28)에 용량 1을 더한 21건과 29건이 backend의 고정 창 상한 30 아래에 든다.
        # 용량 5였을 때는 첫 1분에 33건이 가능해 backend 429가 섞였다 (Codex 리뷰 P2).
        self.session_bucket = TokenBucket(args.sessions_per_minute / 60.0, burst=1)
        self.upload_bucket = TokenBucket(args.uploads_per_minute / 60.0, burst=1)
        self.audio = smoke.wav_bytes()
        self.definitions: Dict[str, smoke.Definition] = {}
        self.definition_lock = threading.Lock()
        self.processing: Dict[int, int] = {}
        self.processing_lock = threading.Lock()

    def call(
        self,
        kind: str,
        method: str,
        path: str,
        *,
        headers: Optional[Dict[str, str]] = None,
        body: Optional[bytes] = None,
        content_type: Optional[str] = None,
        correlation_id: Optional[str] = None,
        worker: str = "",
    ) -> Optional[smoke.Response]:
        """요청 하나를 보내고 결과를 센다. 연결 실패는 ``ERR``로 세고 None을 돌려준다."""
        started = time.monotonic()
        try:
            response = self.client.request(
                method, path, headers=headers, body=body, content_type=content_type,
                correlation_id=correlation_id,
            )
        except smoke.SmokeFailure as error:
            elapsed = (time.monotonic() - started) * 1000
            self.tally.record(kind, "ERR", elapsed)
            self.tally.event("http_error", kind=kind, method=method, path=path, status="ERR",
                             detail=str(error)[:200], worker=worker, latency_ms=round(elapsed, 1))
            return None
        elapsed = (time.monotonic() - started) * 1000
        self.tally.record(kind, response.status, elapsed)
        if is_failure_status(response.status):
            self.tally.event(
                "http_error", kind=kind, method=method, path=path, status=response.status,
                code=response.error_code(), detail=response.body[:200].decode("utf-8", "replace"),
                worker=worker, latency_ms=round(elapsed, 1),
            )
        return response

    def set_processing(self, worker_id: int, count: int) -> None:
        with self.processing_lock:
            self.processing[worker_id] = count
            self.tally.gauge("processing_seen", sum(self.processing.values()))


# ---------------------------------------------------------------------------
# 워커
# ---------------------------------------------------------------------------


def get_worker(load: Load, worker_id: int) -> None:
    """정의 조회를 토큰 버킷 속도로 계속 보낸다. 조회 경로는 세션 없이도 열려 있다 (§3.2)."""
    path = "/v0/tests/" + urllib.parse.quote(load.args.test_version)
    name = "get-%d" % worker_id
    while load.get_bucket.acquire(load.stop):
        load.call("get", "GET", path, worker=name)


def create_session(load: Load, worker: str) -> Optional[smoke.Session]:
    headers = {smoke.ADMIN_TOKEN_HEADER: load.args.admin_token} if load.args.admin_token else None
    response = load.call(
        "session", "POST", "/v0/sessions", headers=headers,
        body=json.dumps({"client": {"platform": "WEB", "appVersion": "load"}}).encode("utf-8"),
        content_type="application/json", worker=worker,
    )
    if response is None or response.status != 201:
        return None
    body = response.json()
    return smoke.Session(body["sessionId"], body["sessionToken"], body["testVersion"], body["scoreVersion"])


def definition_for(load: Load, session: smoke.Session) -> smoke.Definition:
    """테스트 정의는 버전당 한 번만 읽는다 (계약 검사는 e2e_smoke의 것을 그대로 쓴다)."""
    with load.definition_lock:
        cached = load.definitions.get(session.test_version)
        if cached is None:
            cached = smoke.fetch_definition(load.client, session)
            load.definitions[session.test_version] = cached
        return cached


#: 업로드가 503 ANALYSIS_UNAVAILABLE로 끝났을 때의 표시. 서버는 이 경우에도 작업을 RETRYABLE_FAILED로
#: 남기고 503을 준다 (VoiceUploadService) - 상태 조회에 실패 문항으로 나타나므로 재업로드 대상으로 추적한다.
UPLOAD_REJECTED = {"status": "REJECTED", "attempt": None}


def upload(load: Load, session: smoke.Session, item_id: str, worker: str) -> Optional[Dict[str, Any]]:
    """음성 1건 업로드 (§3.3). 429는 안내대로 물러섰다가 새 키로 다시 올린다.

    돌아오는 값은 202 응답 본문, 503 ANALYSIS_UNAVAILABLE이면 :data:`UPLOAD_REJECTED`, 그 밖의
    실패(연결 실패, 4xx, 429 재시도 소진)는 None이다.
    """
    content_type, body = smoke.multipart_body(load.audio, smoke.upload_meta(), item_id + ".wav")
    path = "/v0/sessions/%s/voice-items/%s/recording" % (
        urllib.parse.quote(session.session_id), urllib.parse.quote(item_id))
    for _attempt in range(smoke.MAX_RATE_LIMIT_RETRIES + 1):
        if not load.upload_bucket.acquire(load.stop):
            return None
        headers = dict(session.auth())
        headers["Idempotency-Key"] = "load-" + uuid.uuid4().hex
        response = load.call(
            "upload", "POST", path, headers=headers, body=body, content_type=content_type,
            correlation_id="load-" + uuid.uuid4().hex, worker=worker,
        )
        if response is None:
            return None
        if response.status == 202:
            return response.json()
        if response.status == 503 and response.error_code() == "ANALYSIS_UNAVAILABLE":
            return dict(UPLOAD_REJECTED)
        if response.status == 429 and response.error_code() == "RATE_LIMITED":
            load.stop.wait(load.client._retry_after_ms(response) / 1000)
            continue
        return None
    return None


def poll_until_settled(
    load: Load, session: smoke.Session, voice_ids: Sequence[str], worker_id: int, worker: str,
) -> Optional[Dict[str, Dict[str, Any]]]:
    """일괄 상태 조회(§3.4)를 pollAfterMs에 맞춰 돌며 모든 음성 문항이 정착하기를 기다린다.

    돌아오는 값은 itemId -> 항목이고, 상한(``--analysis-timeout``)을 넘기면 None이다. 폴링 응답
    하나마다 PROCESSING 건수를 게이지로 남긴다 - 강제 종료 시점에 진행 중 분석이 있었다는 증거다.
    """
    path = "/v0/sessions/%s/analyses" % urllib.parse.quote(session.session_id)
    deadline = time.monotonic() + load.args.analysis_timeout
    wait_ms = 800
    while not load.stop.is_set():
        if load.stop.wait(wait_ms / 1000):
            break
        response = load.call("poll", "GET", path, headers=session.auth(), worker=worker)
        if response is None or response.status != 200:
            if response is not None and response.status == 429:
                wait_ms = load.client._retry_after_ms(response)
            elif response is not None and response.status in (401, 403, 404, 410):
                return None  # 세션이 만료됐거나 사라졌다 - 더 물어봐야 같은 답이다.
            else:
                wait_ms = 2000
            if time.monotonic() > deadline:
                return None
            continue
        body = response.json()
        items = body.get("items") or []
        by_item = {item.get("itemId"): item for item in items}
        load.set_processing(worker_id, sum(1 for item in items if item.get("status") == "PROCESSING"))
        settled = [
            item_id for item_id in voice_ids
            if by_item.get(item_id, {}).get("status") in ("COMPLETED", "RETRYABLE_FAILED", "FAILED")
        ]
        if len(settled) == len(voice_ids):
            load.set_processing(worker_id, 0)
            return by_item
        if time.monotonic() > deadline:
            load.set_processing(worker_id, 0)
            return by_item
        poll_after = body.get("pollAfterMs")
        wait_ms = poll_after if isinstance(poll_after, int) and poll_after > 0 else 800
    return None


def submit_vocab(load: Load, session: smoke.Session, definition: smoke.Definition, worker: str) -> int:
    """어휘 5문항을 첫 선택지로 제출한다 (§3.5). 완료 확정은 10문항 전부의 제출을 요구한다. 수락 건수."""
    accepted = 0
    for item in definition.vocab_items:
        path = "/v0/sessions/%s/vocab-items/%s/answer" % (
            urllib.parse.quote(session.session_id), urllib.parse.quote(item["itemId"]))
        headers = dict(session.auth())
        headers["Idempotency-Key"] = "load-" + uuid.uuid4().hex
        response = load.call(
            "vocab", "POST", path, headers=headers,
            body=json.dumps({"choiceId": item["choices"][0]["choiceId"]}).encode("utf-8"),
            content_type="application/json", worker=worker,
        )
        if response is not None and response.status == 200:
            accepted += 1
    return accepted


def complete_session(load: Load, session: smoke.Session, worker: str) -> bool:
    path = "/v0/sessions/%s/complete" % urllib.parse.quote(session.session_id)
    headers = dict(session.auth())
    headers["Idempotency-Key"] = "load-complete-" + uuid.uuid4().hex
    deadline = time.monotonic() + load.args.analysis_timeout
    while not load.stop.is_set():
        response = load.call("complete", "POST", path, headers=headers, body=b"", worker=worker)
        if response is None or response.status != 200:
            return False
        body = response.json()
        if body.get("status") == "READY":
            return True
        if time.monotonic() > deadline:
            return False
        poll_after = body.get("pollAfterMs")
        load.stop.wait((poll_after if isinstance(poll_after, int) and poll_after > 0 else 800) / 1000)
    return False


def examinee_worker(load: Load, worker_id: int) -> None:
    """응시자 한 명의 흐름을 반복한다: 세션 -> 업로드 5건 -> 폴링 -> 재녹음 -> 완료."""
    name = "examinee-%d" % worker_id
    while not load.stop.is_set():
        if not load.session_bucket.acquire(load.stop):
            break
        started = time.monotonic()
        session = create_session(load, name)
        if session is None:
            load.stop.wait(3)
            continue
        try:
            definition = definition_for(load, session)
        except smoke.SmokeFailure as error:
            load.tally.event("definition_error", detail=str(error)[:200], worker=name)
            load.stop.wait(3)
            continue
        voice_ids = definition.voice_ids()

        # 이 세션이 시도한 문항 - 202로 접수된 것과 503으로 거절된 것(서버가 RETRYABLE_FAILED로 남긴다) 둘 다.
        # 상태 조회는 정의의 음성 문항 전부를 돌려주므로 판정은 이 목록 기준으로만 한다.
        tracked: Dict[str, Dict[str, Any]] = {}
        for item_id in voice_ids:
            body = upload(load, session, item_id, name)
            if body is not None:
                tracked[item_id] = body
        if not tracked:
            load.tally.event("session_done", session=session.session_id, outcome="no_upload", worker=name)
            continue
        # 어휘는 분석을 기다리는 동안 제출한다 - 앱도 음성 5문항 뒤 어휘 5문항 순서다.
        vocab_ok = submit_vocab(load, session, definition, name)

        outcome = "completed"
        reuploads = 0
        # 실패 문항은 재녹음(같은 문항에 새 시도)으로 되살린다. 문항당 시도 상한(§2.5) 안에서 최대 3회.
        for round_no in range(1, 4):
            statuses = poll_until_settled(load, session, list(tracked), worker_id, name)
            if statuses is None:
                # 중단 신호로 끊긴 세션은 시간 초과가 아니다 - 요약에서 구분한다.
                outcome = "interrupted" if load.stop.is_set() else "poll_timeout"
                break
            failed = {}
            unsettled = []
            for item_id, sent in tracked.items():
                item = statuses.get(item_id) or {}
                status = item.get("status")
                if status in ("RETRYABLE_FAILED", "FAILED"):
                    failed[item_id] = item
                elif sent["status"] == "REJECTED" and status != "COMPLETED":
                    # 503으로 거절된 문항이 상태 조회에 실패로 안 보여도(예: 조회가 다른 태스크로 갔는데
                    # 아직 반영 전) 재업로드 대상이다 - 앱도 503을 받으면 재녹음을 안내한다.
                    failed[item_id] = item
                elif status == "PROCESSING":
                    unsettled.append(item_id)
            if unsettled:
                outcome = "analysis_timeout"
                load.tally.event("analysis_timeout", session=session.session_id, items=unsettled, worker=name)
                break
            if not failed:
                break
            for item_id, item in failed.items():
                error = item.get("error") or {}
                load.tally.event(
                    "item_failed", session=session.session_id, item=item_id,
                    status=item.get("status") or tracked[item_id]["status"],
                    code=error.get("code"), retryable=error.get("retryable"),
                    attempt=tracked[item_id].get("attempt"), round=round_no, worker=name,
                )
            if round_no == 3:
                outcome = "failed_after_reupload"
                break
            tracked = {}
            for item_id in failed:
                body = upload(load, session, item_id, name)
                if body is not None:
                    tracked[item_id] = body
                    if body["status"] != "REJECTED":
                        reuploads += 1
                        load.tally.event("reupload_accepted", session=session.session_id, item=item_id,
                                         attempt=body.get("attempt"), worker=name)
            if not tracked:
                outcome = "reupload_rejected"
                break
        else:
            outcome = "failed_after_reupload"

        if outcome == "completed" and reuploads:
            load.tally.event("reupload_recovered", session=session.session_id, count=reuploads, worker=name)
        if outcome == "completed" and load.args.complete:
            if vocab_ok < len(definition.vocab_items) or not complete_session(load, session, name):
                outcome = "complete_failed"
        load.tally.event(
            "session_done", session=session.session_id, outcome=outcome, reuploads=reuploads,
            elapsed_s=round(time.monotonic() - started, 1), worker=name,
        )
        load.set_processing(worker_id, 0)


# ---------------------------------------------------------------------------
# 보고
# ---------------------------------------------------------------------------


KINDS = ("get", "session", "upload", "poll", "vocab", "complete")


def format_window(window: Dict[str, collections.Counter], tally: Tally) -> str:
    parts = []
    for kind in KINDS:
        counter = window.get(kind)
        if not counter:
            continue
        ok = sum(n for status, n in counter.items() if not is_failure_status(status))
        bad = ["%s=%d" % (status, n) for status, n in sorted(counter.items()) if is_failure_status(status)]
        parts.append("%s ok=%d%s" % (kind, ok, (" " + " ".join(bad)) if bad else ""))
    with tally.lock:
        processing = tally.gauges.get("processing_seen", 0)
        failed = tally.event_counts.get("item_failed", 0)
        recovered = tally.event_counts.get("reupload_recovered", 0)
        sessions = tally.event_counts.get("session_done", 0)
    parts.append("processing=%d" % processing)
    parts.append("item_failed=%d reupload_recovered=%d sessions=%d" % (failed, recovered, sessions))
    return " | ".join(parts)


def reporter(load: Load) -> None:
    interval = load.args.report_interval
    while not load.stop.wait(interval):
        window = load.tally.snapshot_window()
        print("%s | %s" % (clock(), format_window(window, load.tally)), flush=True)


def write_report(args: argparse.Namespace, tally: Tally, started_at: str) -> Dict[str, Any]:
    summary = tally.summary()
    summary.update({
        "label": args.label,
        "base_url": args.base_url,
        "started_at": started_at,
        "ended_at": now_iso(),
        "settings": {
            "get_rps": args.get_rps,
            "examinees": args.examinees,
            "sessions_per_minute": args.sessions_per_minute,
            "uploads_per_minute": args.uploads_per_minute,
            "duration_s": args.duration,
        },
    })
    if args.report:
        with open(args.report, "w", encoding="utf-8") as handle:
            json.dump(summary, handle, ensure_ascii=False, indent=2)
    return summary


# ---------------------------------------------------------------------------
# 진입
# ---------------------------------------------------------------------------


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", required=True, help="예: https://staging.accentury.app")
    parser.add_argument("--admin-token", default=os.environ.get("ACCENTURY_ADMIN_TOKEN"),
                        help="합성 트래픽 표시 토큰 (환경 변수 ACCENTURY_ADMIN_TOKEN). 로컬이 아니면 필수")
    parser.add_argument("--test-version", default="gn-2026.08.1", help="정의 조회 GET의 testVersion")
    parser.add_argument("--get-rps", type=float, default=25.0, help="정의 조회 초당 건수 (0이면 끔)")
    parser.add_argument("--get-workers", type=int, default=8, help="정의 조회 스레드 수 (합이 --get-rps)")
    parser.add_argument("--examinees", type=int, default=4, help="동시 응시자 스레드 수 (0이면 끔)")
    parser.add_argument("--sessions-per-minute", type=float, default=20.0, help="IP 상한 30 아래")
    parser.add_argument("--uploads-per-minute", type=float, default=28.0, help="IP 상한 30 아래")
    parser.add_argument("--complete", action=argparse.BooleanOptionalAction, default=True,
                        help="분석이 끝난 세션의 완료 확정까지 부른다")
    parser.add_argument("--duration", type=float, default=0, help="초. 0이면 Ctrl-C까지")
    parser.add_argument("--request-timeout", type=float, default=smoke.DEFAULT_REQUEST_TIMEOUT)
    parser.add_argument("--analysis-timeout", type=float, default=smoke.DEFAULT_ANALYSIS_TIMEOUT)
    parser.add_argument("--report-interval", type=float, default=10.0, help="진행 요약 출력 간격(초)")
    parser.add_argument("--events", help="이벤트 JSONL 경로 (2xx 아닌 응답, 문항 실패, 재업로드, 세션 결과)")
    parser.add_argument("--report", help="끝날 때 쓸 요약 JSON 경로")
    parser.add_argument("--label", default="", help="요약에 남길 시나리오 이름")
    args = parser.parse_args(argv)
    if not args.admin_token and not smoke.is_local(args.base_url):
        parser.error("로컬이 아닌 대상은 --admin-token(또는 ACCENTURY_ADMIN_TOKEN)이 필요하다 - "
                     "표시 없는 세션은 익명 집계를 오염시킨다 (KAN-138)")
    if args.get_rps <= 0:
        args.get_workers = 0
    return args


def main(argv: Sequence[str]) -> int:
    args = parse_args(argv)
    tally = Tally(args.events)
    load = Load(args, tally)
    started_at = now_iso()

    threads: List[threading.Thread] = []
    for index in range(args.get_workers):
        threads.append(threading.Thread(target=get_worker, args=(load, index), daemon=True))
    for index in range(args.examinees):
        threads.append(threading.Thread(target=examinee_worker, args=(load, index), daemon=True))
    threads.append(threading.Thread(target=reporter, args=(load,), daemon=True))

    def request_stop(_signum: int, _frame: Any) -> None:
        print("%s | 중단 신호 - 진행 중 요청을 마치고 요약한다" % clock(), flush=True)
        load.stop.set()

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    print("%s | 시작 %s get_rps=%s examinees=%d label=%s"
          % (clock(), args.base_url, args.get_rps, args.examinees, args.label or "-"), flush=True)
    tally.event("run_started", label=args.label, settings=vars(args) | {"admin_token": "***" if args.admin_token else None})
    for thread in threads:
        thread.start()

    if args.duration > 0:
        load.stop.wait(args.duration)
        load.stop.set()
    else:
        while not load.stop.is_set():
            load.stop.wait(1)

    for thread in threads:
        thread.join(timeout=args.request_timeout + 5)

    summary = write_report(args, tally, started_at)
    tally.event("run_ended", label=args.label, http_5xx_total=summary["http_5xx_total"],
                connection_errors_total=summary["connection_errors_total"])
    tally.close()
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
