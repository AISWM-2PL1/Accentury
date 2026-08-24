#!/usr/bin/env python3
"""전 구간 E2E 스모크 - 세션 생성부터 결과 조회까지 (KAN-138).

단위와 통합 테스트는 300건을 넘지만, 세션 생성부터 결과 조회까지를 **실제 HTTP로 순서대로**
통과시킨 적이 없고 BE와 AI 서버를 실제로 붙여 본 적도 없다 (BE 테스트는 AI를 목으로
대체한다). 이 스크립트가 그 자리를 메운다.

    scripts/e2e_smoke.py --base-url http://127.0.0.1:8080
    scripts/e2e_smoke.py --base-url https://api.staging.accentury.app
    scripts/e2e_smoke.py --base-url http://127.0.0.1:8080 \
        --fail-item v3 --recover-cmd 'docker compose up -d --no-deps --wait ai'

대상은 ``--base-url`` 하나로만 갈린다 - 로컬 compose, staging, prod에서 같은 스크립트가
같은 시나리오를 돈다 (티켓 Requirements).

특정 등급 재현
--------------
종합 점수는 억양과 단어 두 축으로 정해지므로(§4.3) 한 축만 고정해서는 5등급을 다 볼 수
없다. gn-2026.08.1에서 첫 선택지 전략은 단어 60점에 묶여 억양을 0으로 눌러도 종합이 20,
즉 여행객이 바닥이다. 두 손잡이를 같이 쓴다.

    --pin-intonation 0   --vocab-choice-index 2   # 종합 0   -> 외지인
    --pin-intonation 30  --vocab-choice-index 1   # 종합 33  -> 여행객
    --pin-intonation 60  --vocab-choice-index 1   # 종합 53  -> 사투리 호소인
    --pin-intonation 60  --vocab-choice-index 0   # 종합 60  -> 명예주민
    --pin-intonation 100 --vocab-choice-index 0   # 종합 87  -> 경남 토박이

표준 라이브러리만 쓴다. 스모크 한 번 돌리자고 배포 파이프라인(KAN-128) 러너에 pip install을
시키면 그 설치 실패가 곧 배포 게이트 실패가 되기 때문이다. 파일 하나를 복사해 어디서든
``python3 e2e_smoke.py``로 돌릴 수 있는 것이 이 스크립트의 배포 방식이다.

검산표를 스크립트가 들고 있는 이유
----------------------------------
등급 경계와 가중치(:data:`TIERS`, :data:`INTONATION_WEIGHT`)는 서버의 sv-0.3 seed
(``backend/src/main/resources/score-versions/sv-0.3.json``)에도 있는 값이다. 그것을 조회해
쓰면 검산이 아니라 서버가 자기 답을 자기 표로 채점하는 것이 된다 - 집계식이 통째로 틀려도
통과한다. 여기 적힌 표는 **독립 오라클**이고, 그래서 서버가 모르는 점수 버전을 내려주면
조용히 넘어가지 않고 멈춘다 (:func:`verify_result`).

sv-0.4로 경계가 재보정되면 이 표에 그 버전을 더한다 (KAN-21 로드맵).
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import http.client
import json
import math
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

# ---------------------------------------------------------------------------
# sv-0.3 집계식 (API 명세서 §4.3, KAN-21) - 서버와 독립인 검산 오라클
# ---------------------------------------------------------------------------

#: 이 스크립트가 검산할 수 있는 점수 버전. 세션이 다른 버전을 고정하면 멈춘다.
SUPPORTED_SCORE_VERSION = "sv-0.3"

INTONATION_WEIGHT = 2
VOCABULARY_WEIGHT = 1

#: (testVersion, 선택지 인덱스) -> 그 답안 전략의 단어 점수.
#:
#: 이 표가 필요한 이유는 단어 점수만은 응답에서 되짚을 수 없기 때문이다. 정답표는 서버 안에만
#: 있고 응답에는 정오가 실리지 않으므로(KAN-13), "20의 배수인가" 같은 형태 검사는 **정답표
#: 처리가 망가져 60이 40으로 나와도 통과한다** (Codex sol 리뷰 P2). 실제로 몇 점이 나와야
#: 하는지를 여기 적어 두어야 그 회귀가 걸린다.
#:
#: 값은 발행본(``V2__test_definition_publish.sql``)의 정답표에서 나온다. 발행 후 불변이므로
#: (§5.4) 이 숫자도 상수다 - gn-2026.08.1의 정답은 w1a, w2b, w3a, w4b, w5a라 첫 선택지는
#: 3개, 둘째 선택지는 2개, 셋째와 넷째는 0개를 맞힌다.
#:
#: 새 testVersion을 발행하면 여기 한 줄을 더한다. 모르는 버전을 만나면 조용히 넘어가지 않고
#: 멈춘다 - 급하면 ``--expect-vocabulary``로 넘긴다.
EXPECTED_VOCABULARY: Dict[Tuple[str, int], int] = {
    ("gn-2026.08.1", 0): 60,
    ("gn-2026.08.1", 1): 40,
    ("gn-2026.08.1", 2): 0,
    ("gn-2026.08.1", 3): 0,
}

#: (code, name, rank, minScore) - minScore는 하한(포함)이라 경계값(20/40/60/80)은
#: 상위 등급으로 간다 (KAN-21 AC). rank 오름차순이다.
TIERS: Tuple[Tuple[str, str, int, int], ...] = (
    ("OUTSIDER", "외지인", 1, 0),
    ("TRAVELER", "여행객", 2, 20),
    ("WANNABE", "사투리 호소인", 3, 40),
    ("HONORARY", "명예주민", 4, 60),
    ("NATIVE", "경남 토박이", 5, 80),
)

# ---------------------------------------------------------------------------
# 업로드 오디오 규격 (API 명세서 §3.3, VoiceUploadService)
# ---------------------------------------------------------------------------

SAMPLE_RATE = 16_000
CHANNELS = 1
BITS_PER_SAMPLE = 16

#: 생성할 녹음 길이. 문항 상한은 10초지만 스모크는 짧을수록 좋다 - 길이 자체를 시험하는
#: 자리가 아니고, 업로드 바이트가 그대로 AI까지 패스스루되기 때문이다.
AUDIO_DURATION_MS = 1_000

#: 사인파의 진폭. 무음이 아닌 것이 목적이다 - 지금 스텁은 오디오를 한 바이트도 보지 않지만,
#: 실모델(KAN-22)이 들어오면 무음은 AUDIO_TOO_QUIET로 떨어져 정상 시나리오가 실패한다.
AUDIO_AMPLITUDE = 0.3
AUDIO_TONE_HZ = 220.0

# ---------------------------------------------------------------------------
# 기본값
# ---------------------------------------------------------------------------

#: 요청 하나의 상한. BE의 AI 호출 타임아웃이 10초라 업로드 202가 그보다 늦게 오지는 않는다.
DEFAULT_REQUEST_TIMEOUT = 15.0

#: 세션 하나의 분석이 전부 끝나기를 기다리는 상한. BE의 processing-timeout(60초)과
#: queued-timeout(5분) 사이다 - 정상이면 스텁 지연 1.5초 x (5문항 / 워커 4) 수준이고,
#: 이 상한에 걸린다는 것은 분석이 밀렸거나 죽었다는 뜻이다.
DEFAULT_ANALYSIS_TIMEOUT = 180.0

#: 429를 만났을 때 물러설 최대 횟수. IP 분당 업로드 상한이 30이라 한 번의 창을 넘길 수
#: 있으면 충분하다.
MAX_RATE_LIMIT_RETRIES = 3

#: 429 봉투에 retryAfterMs도 Retry-After 헤더도 없을 때 기다릴 시간.
FALLBACK_RETRY_AFTER_MS = 2_000

#: AI 회로가 열려 업로드가 503으로 끊길 때 다시 시도할 횟수 (§3.3 - 새 키로 다시 올린다).
MAX_UNAVAILABLE_RETRIES = 3
UNAVAILABLE_BACKOFF_MS = 3_000

#: 복구 명령이 끝난 뒤 한 박자 쉬는 시간. 컨테이너가 healthy여도 BE 쪽에는 방금 사라진
#: 주소를 가리키는 커넥션과 DNS 캐시가 남아 있을 수 있다 - 그 구간에 올린 첫 건이 애먼
#: ANALYSIS_UNAVAILABLE로 죽는 것을 줄인다. 남는 경우는 :func:`upload_until_completed`가 받는다.
RECOVER_SETTLE_MS = 3_000

#: 재업로드로도 풀리는 일시 장애 코드 (§2.4). 판정 실패(AUDIO_TOO_QUIET 등)는 여기 없다 -
#: 같은 오디오에 같은 답이 오므로 다시 올려도 결과가 같다.
TRANSIENT_FAILURE_CODES = frozenset({"ANALYSIS_UNAVAILABLE", "ANALYSIS_TIMEOUT", "INTERNAL_ERROR"})


CORRELATION_HEADER = "X-Correlation-Id"

#: 합성 트래픽 표시와 관리자 조회가 함께 쓰는 헤더 (KAN-138, 명세서 §6).
ADMIN_TOKEN_HEADER = "X-Admin-Token"

#: 표시 없이 두드려도 통계가 오염되지 않는 대상 - 로컬 스택뿐이다.
LOCAL_HOSTS = frozenset({"localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0"})


def new_correlation_id() -> str:
    """요청 하나의 추적 ID. 앱(UploadClient)이 업로드마다 새로 발급하는 것을 그대로 따른다.

    CorrelationIdFilter가 받아들이는 형식([A-Za-z0-9._-]{1,64})을 지킨다 - 벗어나면 서버가
    버리고 새로 발급하므로, 스텁 점수 예측(:func:`hashed_score`)이 통째로 어긋난다.
    """
    return "e2e-" + uuid.uuid4().hex


def lower_headers(headers: Any) -> Dict[str, str]:
    """헤더 이름을 소문자로 접는다 - HTTP 헤더 이름은 대소문자를 구분하지 않는다."""
    return {name.lower(): value for name, value in headers.items()}


class SmokeFailure(Exception):
    """시나리오가 계약과 다르게 돌았다. 이 예외 하나로 스크립트가 실패로 끝난다."""


_started_at = time.monotonic()


def log(message: str) -> None:
    """진행 상황 한 줄. 파이프라인 로그에서 어느 단계가 느린지 보이도록 경과 시간을 붙인다."""
    print("[%7.2fs] %s" % (time.monotonic() - _started_at, message), flush=True)


def expect(condition: bool, message: str) -> None:
    if not condition:
        raise SmokeFailure(message)


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------


@dataclass
class Response:
    status: int
    headers: Dict[str, str]
    body: bytes
    method: str
    path: str

    def json(self) -> Any:
        try:
            return json.loads(self.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise SmokeFailure(
                "%s %s 응답이 JSON이 아니다 (status=%d): %s"
                % (self.method, self.path, self.status, self.body[:200])
            ) from error

    def error_code(self) -> Optional[str]:
        """오류 봉투(§2.3)의 code. 봉투가 아니면 None이다."""
        try:
            body = json.loads(self.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None
        return body.get("code") if isinstance(body, dict) else None

    def describe(self) -> str:
        return "%s %s -> %d %s" % (
            self.method,
            self.path,
            self.status,
            self.body[:300].decode("utf-8", "replace"),
        )


class Client:
    """BE 하나를 상대하는 최소 HTTP 클라이언트.

    요청 제한(429)과 분석 서버 일시 불가(503)만 스스로 물러섰다가 다시 시도한다 -
    둘 다 서버가 재시도하라고 명시한 상태이고(§2.5, §3.3), 스모크가 그 안내를 무시하면
    시나리오가 아니라 스크립트가 깨진다. 나머지 상태 코드는 그대로 호출부로 올려
    시나리오가 판단한다.
    """

    def __init__(self, base_url: str, timeout: float, verbose: bool) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.verbose = verbose

    def request(
        self,
        method: str,
        path: str,
        *,
        headers: Optional[Dict[str, str]] = None,
        body: Optional[bytes] = None,
        content_type: Optional[str] = None,
        correlation_id: Optional[str] = None,
    ) -> Response:
        sent: Dict[str, str] = {"Accept": "application/json"}
        if content_type:
            sent["Content-Type"] = content_type
        # 추적 ID는 언제나 보낸다 (§2.2) - 실패한 스모크의 서버 로그를 찾는 유일한 키다.
        sent[CORRELATION_HEADER] = correlation_id or new_correlation_id()
        if headers:
            sent.update(headers)

        attempts = 0
        while True:
            response = self._send(method, path, sent, body)
            if attempts >= MAX_RATE_LIMIT_RETRIES:
                return response
            if response.status != 429:
                return response
            code = response.error_code()
            if code != "RATE_LIMITED":
                # RATE_RETAKE_EXCEEDED는 시간이 지나도 풀리지 않는다 (§3.3).
                return response
            attempts += 1
            wait_ms = self._retry_after_ms(response)
            log("  429 RATE_LIMITED - %dms 뒤 재시도 (%d/%d)" % (wait_ms, attempts, MAX_RATE_LIMIT_RETRIES))
            time.sleep(wait_ms / 1000)

    def _send(
        self, method: str, path: str, headers: Dict[str, str], body: Optional[bytes]
    ) -> Response:
        url = self.base_url + path
        request = urllib.request.Request(url, data=body, method=method)
        for name, value in headers.items():
            request.add_header(name, value)
        if self.verbose:
            log("  -> %s %s" % (method, path))
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as raw:
                response = Response(raw.status, lower_headers(raw.headers), raw.read(), method, path)
        except urllib.error.HTTPError as error:
            # HTTPError는 예외이면서 응답이다 - 4xx와 5xx도 봉투를 읽어야 한다 (§2.3).
            response = Response(error.code, lower_headers(error.headers), error.read(), method, path)
        except (urllib.error.URLError, OSError, http.client.HTTPException) as error:
            raise SmokeFailure("%s %s 연결 실패: %s" % (method, path, error)) from error
        if self.verbose:
            log("  <- %d" % response.status)
        return response

    @staticmethod
    def _retry_after_ms(response: Response) -> int:
        try:
            envelope = json.loads(response.body.decode("utf-8"))
            if isinstance(envelope, dict) and isinstance(envelope.get("retryAfterMs"), int):
                return max(0, envelope["retryAfterMs"])
        except (UnicodeDecodeError, json.JSONDecodeError):
            pass
        header = response.headers.get("retry-after")
        if header:
            try:
                return max(0, int(float(header) * 1000))
            except ValueError:
                pass
        return FALLBACK_RETRY_AFTER_MS


# ---------------------------------------------------------------------------
# AI 스텁 점수 예측 (KAN-136)
# ---------------------------------------------------------------------------


def hashed_score(correlation_id: str) -> int:
    """추적 ID 하나를 0~100의 억양 원점수로 접는다.

    AI 스텁(``ai/app/engine.py``의 ``StubEngine.hashed_score``)과 **같은 식**이다.
    같은 값을 두 곳에 적는 것은 중복이 아니라 검산의 전제다 - 서버에서 가져와 쓰면
    BE가 추적 ID를 AI로 전파하지 않아 모든 문항이 같은 점수를 받는 회귀(KAN-136이
    경고한 바로 그 자리)를 이 스크립트가 눈치채지 못한다.

    실모델(KAN-22)이 붙은 환경에서는 성립하지 않으므로 ``--expect-stub-scores``로만 쓴다.
    """
    digest = hashlib.blake2b(correlation_id.encode("utf-8"), digest_size=8).digest()
    return int.from_bytes(digest, "big") % 101


def correlation_id_for_score(target: int) -> str:
    """해시가 ``target``이 되는 추적 ID를 찾는다 (``--pin-intonation``).

    한 세션의 음성 5문항에 이 ID 하나를 고정하면 다섯 점수가 같아지고, 억양 점수가 곧
    ``target``이 된다 - 고정 75점 시절에는 볼 수 없던 등급(외지인, 경남 토박이)까지
    실제 응답으로 재현하는 수단이다 (KAN-136이 명시한 KAN-138 용도).
    """
    prefix = "e2e-pin-" + uuid.uuid4().hex[:12] + "-"
    for suffix in range(100_000):
        candidate = "%s%d" % (prefix, suffix)
        if hashed_score(candidate) == target:
            return candidate
    # 101가지 값이 고르게 나오므로 여기 닿을 확률은 사실상 0이다.
    raise SmokeFailure("억양 %d점을 만드는 추적 ID를 찾지 못했다." % target)


# ---------------------------------------------------------------------------
# 업로드 페이로드
# ---------------------------------------------------------------------------


def wav_bytes(duration_ms: int = AUDIO_DURATION_MS) -> bytes:
    """WAV 16kHz mono 16-bit PCM 한 건 (§3.3).

    서버의 헤더 검사(``WavAudio``)가 까다롭다 - RIFF 크기가 실제 파일 크기와 맞아야 하고,
    ``blockAlign``과 ``byteRate``가 파생값과 어긋나면 415다. 그래서 헤더를 손으로 쓴다.
    """
    frames = SAMPLE_RATE * duration_ms // 1000
    block_align = CHANNELS * BITS_PER_SAMPLE // 8
    byte_rate = SAMPLE_RATE * block_align
    peak = int(AUDIO_AMPLITUDE * 32767)
    samples = bytearray()
    for n in range(frames):
        samples += struct.pack("<h", int(peak * math.sin(2 * math.pi * AUDIO_TONE_HZ * n / SAMPLE_RATE)))
    data = bytes(samples)
    header = b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVE"
    header += b"fmt " + struct.pack(
        "<IHHIIHH", 16, 1, CHANNELS, SAMPLE_RATE, byte_rate, block_align, BITS_PER_SAMPLE
    )
    header += b"data" + struct.pack("<I", len(data))
    return header + data


def upload_meta(duration_ms: int = AUDIO_DURATION_MS) -> Dict[str, Any]:
    """multipart의 meta 파트 (§3.3).

    ``durationMs``와 ``clientQuality`` 4개 필드가 모두 필수다 (2026-08-06 확정) - 하나라도
    빠지면 400 VALIDATION_FAILED다. 값은 위에서 만든 사인파의 실제 특성으로 채운다.
    """
    return {
        "durationMs": duration_ms,
        "clientQuality": {
            "rms": round(AUDIO_AMPLITUDE / math.sqrt(2), 4),
            "peak": AUDIO_AMPLITUDE,
            "silenceRatio": 0.0,
            "clipped": False,
        },
    }


def multipart_body(audio: bytes, meta: Dict[str, Any], filename: str) -> Tuple[str, bytes]:
    """audio + meta 두 파트를 앱(UploadClient)과 같은 모양으로 조립한다.

    meta 파트에 filename을 붙이지 않는 것까지 앱과 같다 - 서버는 이 파트를 파일이 아니라
    문자열로 읽는다(``@RequestPart String metaJson``).
    """
    boundary = "----accentury-e2e-" + uuid.uuid4().hex
    marker = ("--" + boundary + "\r\n").encode("utf-8")
    chunks: List[bytes] = [
        marker,
        ('Content-Disposition: form-data; name="audio"; filename="%s"\r\n' % filename).encode("utf-8"),
        b"Content-Type: audio/wav\r\n\r\n",
        audio,
        b"\r\n",
        marker,
        b'Content-Disposition: form-data; name="meta"\r\n',
        b"Content-Type: application/json\r\n\r\n",
        json.dumps(meta).encode("utf-8"),
        b"\r\n",
        ("--" + boundary + "--\r\n").encode("utf-8"),
    ]
    return "multipart/form-data; boundary=" + boundary, b"".join(chunks)


# ---------------------------------------------------------------------------
# 집계식 검산
# ---------------------------------------------------------------------------


def round_half_up(numerator: int, denominator: int) -> int:
    """음이 아닌 정수 나눗셈의 사사오입 - BE ``ScoreAggregator.roundHalfUp``과 같은 식이다."""
    return (2 * numerator + denominator) // (2 * denominator)


def tier_for(overall: int) -> Tuple[str, str, int]:
    """종합 점수의 등급 - minScore가 점수 이하인 가장 높은 등급이다."""
    for code, name, rank, min_score in reversed(TIERS):
        if overall >= min_score:
            return code, name, rank
    raise SmokeFailure("등급을 판정할 수 없는 종합 점수다: %d" % overall)


# ---------------------------------------------------------------------------
# 세션 상태
# ---------------------------------------------------------------------------


@dataclass
class Session:
    session_id: str
    token: str
    test_version: str
    score_version: str

    def auth(self) -> Dict[str, str]:
        return {"Authorization": "Bearer " + self.token}


@dataclass
class Definition:
    test_version: str
    score_version: str
    voice_items: List[Dict[str, Any]]
    vocab_items: List[Dict[str, Any]]

    def voice_ids(self) -> List[str]:
        return [item["itemId"] for item in self.voice_items]


@dataclass
class Scenario:
    """시나리오 하나가 실제로 보낸 것 - 검산이 이 기록을 본다."""

    name: str
    session: Session
    definition: Definition
    #: 어휘 문항에서 고를 선택지의 인덱스 - 단어 점수를 결정하는 유일한 입력이다.
    vocab_choice_index: int = 0
    #: 음성 itemId -> 채점 대상이 될 시도의 추적 ID. 재업로드하면 새 값으로 덮는다
    #: (채점은 문항당 최신 성공 시도 1건이다, §5.1).
    scored_correlation_ids: Dict[str, str] = field(default_factory=dict)


# ---------------------------------------------------------------------------
# API 호출 한 단계씩
# ---------------------------------------------------------------------------


def create_session(client: Client, synthetic_key: Optional[str]) -> Session:
    """익명 세션 하나 (§3.1).

    ``synthetic_key``가 있으면 이 세션을 합성 트래픽으로 표시한다 (KAN-138). 표시는 여기
    한 번뿐이고, 이 세션의 응시와 완주는 익명 집계(KAN-106)에서 실사용자와 다른 행에 쌓인다.
    표시 없이 staging이나 prod를 두드리면 스모크 1회가 완주율과 등급 분포를 영구히 흔든다 -
    일자 합계라 나중에 빼낼 수도 없다.
    """
    headers = {ADMIN_TOKEN_HEADER: synthetic_key} if synthetic_key else None
    response = client.request(
        "POST",
        "/v0/sessions",
        headers=headers,
        body=json.dumps({"client": {"platform": "WEB", "appVersion": "e2e"}}).encode("utf-8"),
        content_type="application/json",
    )
    expect(response.status == 201, "세션 생성이 201이 아니다: " + response.describe())
    body = response.json()
    for key in ("sessionId", "sessionToken", "testVersion", "scoreVersion", "expiresAt"):
        expect(key in body, "세션 응답에 %s가 없다 (§3.1): %s" % (key, body))
    session = Session(body["sessionId"], body["sessionToken"], body["testVersion"], body["scoreVersion"])
    log("세션 생성 sessionId=%s testVersion=%s scoreVersion=%s"
        % (session.session_id, session.test_version, session.score_version))
    return session


def fetch_definition(client: Client, session: Session) -> Definition:
    path = "/v0/tests/" + urllib.parse.quote(session.test_version)
    response = client.request("GET", path)
    expect(response.status == 200, "테스트 정의 조회가 200이 아니다: " + response.describe())
    etag = response.headers.get("etag")
    body = response.json()

    expect(body.get("testVersion") == session.test_version,
           "정의의 testVersion이 세션과 다르다: %s" % body.get("testVersion"))
    expect(body.get("scoreVersion") == session.score_version,
           "정의의 scoreVersion이 세션과 다르다: %s" % body.get("scoreVersion"))

    items = body.get("items")
    expect(isinstance(items, list) and items, "정의에 items가 없다 (§3.2)")

    seqs = [item.get("seq") for item in items]
    expect(all(isinstance(seq, int) for seq in seqs), "seq가 정수가 아닌 문항이 있다: %s" % seqs)
    expect(seqs == sorted(seqs) and len(set(seqs)) == len(seqs),
           "items가 seq 오름차순이 아니거나 seq가 중복이다 (§3.2): %s" % seqs)

    voice = [item for item in items if item.get("type") == "VOICE"]
    vocab = [item for item in items if item.get("type") == "VOCABULARY"]
    expect(len(voice) == 5 and len(vocab) == 5,
           "문항 구성이 음성 5 + 어휘 5가 아니다: 음성 %d, 어휘 %d" % (len(voice), len(vocab)))
    expect(len(voice) + len(vocab) == len(items),
           "VOICE도 VOCABULARY도 아닌 문항이 있다: %s" % [i.get("type") for i in items])

    for item in items:
        # 정답은 어떤 유형에서도 나가지 않는다 (KAN-10 AC, KAN-13 정오 미노출).
        expect("correctChoiceId" not in item,
               "정의 응답에 정답이 실렸다 (KAN-10 AC): %s" % item.get("itemId"))
    for item in voice:
        expect(item.get("maxDurationMs") == 10_000,
               "음성 문항의 maxDurationMs가 10000이 아니다: %s" % item)
        expect("choices" not in item, "음성 문항에 choices가 실렸다 (§3.2): %s" % item.get("itemId"))
        expect(isinstance(item.get("guideF0"), dict),
               "음성 문항에 guideF0이 없다 (§3.2): %s" % item.get("itemId"))
    for item in vocab:
        expect("maxDurationMs" not in item,
               "어휘 문항에 maxDurationMs가 실렸다 (§3.2): %s" % item.get("itemId"))
        choices = item.get("choices")
        # 선택지 개수는 정의(KAN-26)의 몫이라 스모크가 못 박지 않는다 - 고를 것이 있으면 된다.
        expect(isinstance(choices, list) and len(choices) >= 2,
               "어휘 문항의 choices가 부족하다 (§3.2): %s" % item.get("itemId"))

    log("테스트 정의 조회 items=%d (음성 %d, 어휘 %d) etag=%s"
        % (len(items), len(voice), len(vocab), etag))

    # 버전 경로는 불변이므로 같은 ETag로 다시 물으면 304다 (§3.2). ETag가 없으면 검사를
    # 건너뛰는 게 아니라 거기서 끊는다 - 서버나 프록시가 ETag를 떨어뜨린 것이 바로 그 계약이
    # 깨진 상태이고, 건너뛰면 회귀가 통과한다 (Codex sol 리뷰 P2).
    expect(isinstance(etag, str) and etag.strip(),
           "정의 응답에 ETag가 없다 (§3.2) - 캐시 계약이 깨졌다: %s" % response.headers)
    again = client.request("GET", path, headers={"If-None-Match": etag})
    expect(again.status == 304,
           "If-None-Match 재조회가 304가 아니다 (§3.2): " + again.describe())

    return Definition(body["testVersion"], body["scoreVersion"], voice, vocab)


def upload_voice(
    client: Client,
    scenario: Scenario,
    item_id: str,
    correlation_id: str,
) -> Dict[str, Any]:
    """음성 1건 업로드 (§3.3). 202 응답을 돌려준다."""
    audio = wav_bytes()
    content_type, body = multipart_body(audio, upload_meta(), item_id + ".wav")
    path = "/v0/sessions/%s/voice-items/%s/recording" % (
        urllib.parse.quote(scenario.session.session_id),
        urllib.parse.quote(item_id),
    )

    for attempt in range(MAX_UNAVAILABLE_RETRIES + 1):
        headers = dict(scenario.session.auth())
        # 재전송이 아니라 새 업로드이므로 키도 새로 만든다 (§3.3) - 503으로 끊긴 시도는
        # 작업조차 만들어지지 않았으니 같은 키를 고집할 이유가 없다.
        headers["Idempotency-Key"] = "e2e-" + uuid.uuid4().hex
        response = client.request(
            "POST", path, headers=headers, body=body, content_type=content_type,
            correlation_id=correlation_id,
        )
        if response.status == 503 and response.error_code() == "ANALYSIS_UNAVAILABLE":
            if attempt == MAX_UNAVAILABLE_RETRIES:
                raise SmokeFailure("AI 분석 경로가 계속 닫혀 있다: " + response.describe())
            log("  503 ANALYSIS_UNAVAILABLE - %dms 뒤 새 키로 재시도" % UNAVAILABLE_BACKOFF_MS)
            time.sleep(UNAVAILABLE_BACKOFF_MS / 1000)
            continue

        expect(response.status == 202, "음성 업로드가 202가 아니다: " + response.describe())
        accepted = response.json()
        expect(accepted.get("itemId") == item_id,
               "업로드 응답의 itemId가 다르다: %s" % accepted)
        expect(accepted.get("status") == "PROCESSING",
               "접수 직후 상태가 PROCESSING이 아니다 (§3.3): %s" % accepted)
        expect(isinstance(accepted.get("analysisJobId"), str) and accepted["analysisJobId"],
               "업로드 응답에 analysisJobId가 없다: %s" % accepted)
        expect(isinstance(accepted.get("attempt"), int) and accepted["attempt"] >= 1,
               "업로드 응답의 attempt가 이상하다: %s" % accepted)
        expect(isinstance(accepted.get("pollAfterMs"), int) and accepted["pollAfterMs"] > 0,
               "업로드 응답에 양수 pollAfterMs가 없다 (§5.3): %s" % accepted)
        scenario.scored_correlation_ids[item_id] = correlation_id
        log("  음성 업로드 %s attempt=%d jobId=%s pollAfterMs=%d"
            % (item_id, accepted["attempt"], accepted["analysisJobId"], accepted["pollAfterMs"]))
        return accepted

    raise SmokeFailure("도달할 수 없는 경로")  # pragma: no cover


def submit_vocab(
    client: Client, scenario: Scenario, item: Dict[str, Any], expected_answered: int
) -> Dict[str, Any]:
    """어휘 1건 제출 (§3.5).

    선택지를 무작위가 아니라 **고정 인덱스**로 고른다. 무작위면 단어 점수가 실행마다 흔들려,
    점수가 달라진 이유가 서버 변경인지 스크립트의 주사위인지 구분할 수 없다. 고정하면 같은
    testVersion에서 단어 점수가 상수가 되고, 그 상수가 움직이는 것 자체가 신호다
    (:data:`EXPECTED_VOCABULARY`).

    인덱스를 바꾸면 단어 점수도 바뀐다 - 종합 점수의 도달 범위를 옮기는 유일한 손잡이라,
    최하위 등급을 재현할 때 쓴다 (``--vocab-choice-index``).
    """
    item_id = item["itemId"]
    choices = item["choices"]
    index = scenario.vocab_choice_index
    expect(index < len(choices),
           "--vocab-choice-index %d가 %s의 선택지 수(%d)를 넘는다" % (index, item_id, len(choices)))
    choice_id = choices[index]["choiceId"]
    path = "/v0/sessions/%s/vocab-items/%s/answer" % (
        urllib.parse.quote(scenario.session.session_id),
        urllib.parse.quote(item_id),
    )
    headers = dict(scenario.session.auth())
    headers["Idempotency-Key"] = "e2e-" + uuid.uuid4().hex
    response = client.request(
        "POST", path, headers=headers,
        body=json.dumps({"choiceId": choice_id}).encode("utf-8"),
        content_type="application/json",
    )
    expect(response.status == 200, "어휘 제출이 200이 아니다: " + response.describe())
    body = response.json()
    expect(body.get("accepted") is True, "어휘 제출이 수락되지 않았다: %s" % body)
    expect(body.get("totalCount") == 10, "totalCount가 10이 아니다 (§3.5): %s" % body)
    # 진행도는 전체 10문항 기준이고, 음성은 업로드 시도가 1건이라도 있으면 센다 (§3.5).
    # 음성 5건을 먼저 올린 뒤라 이 시점의 기대값은 5 + 지금까지 제출한 어휘 수다.
    expect(body.get("answeredCount") == expected_answered,
           "answeredCount가 기대와 다르다 (§3.5): 기대 %d, 응답 %s" % (expected_answered, body))
    # 정오를 유추할 수 있는 어떤 필드도 없어야 한다 (KAN-13).
    expect(set(body) == {"accepted", "answeredCount", "totalCount"},
           "어휘 응답에 계약 밖 필드가 있다 (§3.5, KAN-13): %s" % sorted(body))
    log("  어휘 제출 %s choiceId=%s answeredCount=%s"
        % (item_id, choice_id, body.get("answeredCount")))
    return body


def submit_all_vocab(client: Client, scenario: Scenario) -> None:
    """어휘 5문항을 순서대로 제출한다. 진행도 기대값은 음성 문항 수에서 이어진다 (§3.5)."""
    answered = len(scenario.definition.voice_items)
    for item in scenario.definition.vocab_items:
        answered += 1
        submit_vocab(client, scenario, item, answered)


def poll_analyses(
    client: Client,
    scenario: Scenario,
    first_wait_ms: int,
    analysis_timeout: float,
    allow_failed: Sequence[str] = (),
) -> Dict[str, Dict[str, Any]]:
    """일괄 상태 조회를 pollAfterMs에 맞춰 돌며 모든 음성 문항이 정착하기를 기다린다 (§3.4).

    ``allow_failed``에 적힌 문항만 RETRYABLE_FAILED로 끝나도 좋다 - 실패 갈래가 겨누는
    문항이다. 그 문항이 **정말로** 실패했는지를 강제하는 것은 호출부의 몫이다. 나머지 문항의
    실패와 복구 불가(FAILED)는 기다려도 달라지지 않으므로 즉시 끊는다.
    """
    path = "/v0/sessions/%s/analyses" % urllib.parse.quote(scenario.session.session_id)
    allowed_failed = set(allow_failed)
    voice_ids = scenario.definition.voice_ids()
    deadline = time.monotonic() + analysis_timeout

    wait_ms = first_wait_ms
    while True:
        time.sleep(wait_ms / 1000)
        response = client.request("GET", path, headers=scenario.session.auth())
        expect(response.status == 200, "상태 조회가 200이 아니다: " + response.describe())
        expect("no-store" in response.headers.get("cache-control", ""),
               "상태 응답에 no-store가 없다 (§3.4): %s" % response.headers.get("cache-control"))
        body = response.json()
        poll_after = body.get("pollAfterMs")
        expect(isinstance(poll_after, int) and poll_after > 0,
               "상태 응답에 양수 pollAfterMs가 없다 (§5.3): %s" % body)

        items = body.get("items")
        expect(isinstance(items, list), "상태 응답에 items가 없다 (§3.4): %s" % body)
        expect([item.get("itemId") for item in items] == voice_ids,
               "상태 응답의 문항이 정의의 음성 문항과 다르다 (§3.4): %s"
               % [item.get("itemId") for item in items])
        by_item = {item["itemId"]: item for item in items}

        for item_id, item in by_item.items():
            status = item.get("status")
            if status in ("PROCESSING", "NOT_SUBMITTED"):
                continue
            if status == "COMPLETED":
                expect(item.get("quality") == "OK",
                       "완료 문항의 quality가 OK가 아니다 (§3.4): %s" % item)
                expect("error" not in item, "완료 문항에 error가 실렸다 (§3.4): %s" % item)
                continue
            if status == "RETRYABLE_FAILED":
                expect(item_id in allowed_failed,
                       "예상하지 않은 문항이 실패했다: %s -> %s" % (item_id, item))
                error = item.get("error") or {}
                expect(error.get("retryable") is True,
                       "RETRYABLE_FAILED인데 error.retryable이 true가 아니다 (§3.4): %s" % item)
                expect(isinstance(error.get("code"), str) and error["code"],
                       "실패 문항에 error.code가 없다 (§3.4): %s" % item)
                continue
            raise SmokeFailure("문항이 복구 불가로 끝났다: %s -> %s" % (item_id, item))

        settled = {
            item_id: item
            for item_id, item in by_item.items()
            if item.get("status") in ("COMPLETED", "RETRYABLE_FAILED")
        }
        if len(settled) == len(voice_ids):
            summary = ", ".join("%s=%s" % (i, by_item[i]["status"]) for i in voice_ids)
            log("  분석 정착: " + summary)
            return by_item

        if time.monotonic() > deadline:
            raise SmokeFailure(
                "[%s] 분석이 %.0f초 안에 끝나지 않았다: %s"
                % (scenario.name, analysis_timeout,
                   {i: by_item[i].get("status") for i in voice_ids})
            )
        wait_ms = poll_after


def upload_until_completed(
    client: Client,
    scenario: Scenario,
    item_id: str,
    correlation_id: str,
    analysis_timeout: float,
    tries: int = 3,
) -> Dict[str, Any]:
    """문항 하나를 재업로드해 COMPLETED까지 되돌린다.

    실패 사유를 두 갈래로 나누는 것이 이 함수의 전부다.

    * 일시 장애(:data:`TRANSIENT_FAILURE_CODES`)는 다시 올리면 풀린다 (§3.3 - 새 키로 다시
      올린다). 복구 직후가 특히 그런데, AI 컨테이너가 방금 교체돼 BE의 커넥션 풀과 DNS 캐시가
      잠깐 사라진 주소를 가리킬 수 있기 때문이다. 이 실패들은 AI에 닿지 못한 것이라 문항당
      시도 상한(§2.5)도 깎지 않는다.
    * 판정 실패(AUDIO_TOO_QUIET 등)는 같은 오디오에 같은 답이 온다. 복구 뒤에 이것이 나오면
      스텁이 여전히 이 문항을 실패시키고 있다는 뜻이므로 - 복구 명령이 듣지 않았다 - 즉시 끊는다.
    """
    for attempt in range(1, tries + 1):
        accepted = upload_voice(client, scenario, item_id, correlation_id)
        statuses = poll_analyses(
            client, scenario, accepted["pollAfterMs"], analysis_timeout, allow_failed=[item_id]
        )
        item = statuses[item_id]
        if item["status"] == "COMPLETED":
            return accepted
        code = (item.get("error") or {}).get("code")
        expect(code in TRANSIENT_FAILURE_CODES,
               "재업로드한 %s가 %s로 실패했다 - 복구 명령이 ACCENTURY_AI_STUB_FAIL_ITEM을 "
               "지우지 못한 것으로 보인다: %s" % (item_id, code, item))
        expect(attempt < tries,
               "재업로드 %d회에도 %s가 완료되지 않았다: %s" % (tries, item_id, item))
        log("  일시 장애(%s) - %s를 다시 올린다 (%d/%d)" % (code, item_id, attempt + 1, tries))
    raise SmokeFailure("도달할 수 없는 경로")  # pragma: no cover


def complete(client: Client, scenario: Scenario, analysis_timeout: float) -> None:
    """완주를 확정한다 (§3.6). READY가 될 때까지 pollAfterMs에 맞춰 다시 부른다."""
    path = "/v0/sessions/%s/complete" % urllib.parse.quote(scenario.session.session_id)
    headers = dict(scenario.session.auth())
    # 완료는 자연 멱등이라 재시도에도 같은 키를 쓴다 (§3.6) - 같은 완료 요청의 재전송이다.
    headers["Idempotency-Key"] = "e2e-complete-" + uuid.uuid4().hex
    deadline = time.monotonic() + analysis_timeout

    while True:
        response = client.request("POST", path, headers=headers, body=b"")
        expect(response.status == 200, "완료 호출이 200이 아니다: " + response.describe())
        body = response.json()
        status = body.get("status")
        if status == "READY":
            log("  완료 확정 READY")
            return
        expect(status == "PROCESSING", "완료 응답의 status가 계약 밖이다 (§3.6): %s" % body)
        poll_after = body.get("pollAfterMs")
        expect(isinstance(poll_after, int) and poll_after > 0,
               "PROCESSING 응답에 양수 pollAfterMs가 없다 (§3.6): %s" % body)
        expect(isinstance(body.get("pendingItems"), list) and body["pendingItems"],
               "PROCESSING 응답에 pendingItems가 없다 (§3.6): %s" % body)
        if time.monotonic() > deadline:
            raise SmokeFailure("완료가 %.0f초 안에 READY가 되지 않았다: %s" % (analysis_timeout, body))
        time.sleep(poll_after / 1000)


def fetch_result(client: Client, scenario: Scenario) -> Dict[str, Any]:
    path = "/v0/sessions/%s/result" % urllib.parse.quote(scenario.session.session_id)
    response = client.request("GET", path, headers=scenario.session.auth())
    expect(response.status == 200, "결과 조회가 200이 아니다: " + response.describe())
    expect("no-store" in response.headers.get("cache-control", ""),
           "결과 응답에 no-store가 없다 (§3.7): %s" % response.headers.get("cache-control"))
    return response.json()


def verify_result(
    scenario: Scenario,
    result: Dict[str, Any],
    expected_intonation: Optional[int],
    expected_vocabulary: int,
) -> None:
    """결과 응답을 sv-0.3 집계식으로 검산한다 (§4.3, 티켓 AC).

    서버가 내려준 억양과 단어 점수만 입력으로 삼아 종합 점수와 등급을 다시 만들고, 응답과
    맞는지 본다. 셋이 서로 맞지 않으면 사용자가 보는 화면이 스스로 모순인 상태다.
    """
    expect(result.get("status") == "READY", "결과 status가 READY가 아니다 (§3.7): %s" % result)
    expect(result.get("testVersion") == scenario.session.test_version,
           "결과의 testVersion이 세션과 다르다: %s" % result.get("testVersion"))
    score_version = result.get("scoreVersion")
    expect(score_version == scenario.session.score_version,
           "결과의 scoreVersion이 세션과 다르다: %s" % score_version)
    expect(score_version == SUPPORTED_SCORE_VERSION,
           "이 스크립트는 %s만 검산한다. 서버가 %s를 쓰므로 검산표를 갱신해야 한다."
           % (SUPPORTED_SCORE_VERSION, score_version))

    scores = result.get("scores") or {}
    for key in ("intonation", "vocabulary", "overall"):
        value = scores.get(key)
        expect(isinstance(value, int) and not isinstance(value, bool),
               "scores.%s가 정수가 아니다 (§3.7): %s" % (key, scores))
        expect(0 <= value <= 100, "scores.%s가 0~100 밖이다: %s" % (key, scores))

    intonation = scores["intonation"]
    vocabulary = scores["vocabulary"]
    overall = scores["overall"]

    # 단어 점수 = 정답 수 x 100 / 문항 수 (§4.3). 먼저 형태를 본다 - 어떤 정답 수로도
    # 만들어지지 않는 값이면 집계 입력 자체가 어긋난 것이다. 실수 나눗셈 대신 가능한 정답
    # 수를 훑는다 - 문항 수가 100을 나누지 못하는 정의에서도 정확하다.
    vocab_count = len(scenario.definition.vocab_items)
    expect(any(vocabulary == round_half_up(correct * 100, vocab_count)
               for correct in range(vocab_count + 1)),
           "단어 점수를 어휘 %d문항의 정답 수로 되짚을 수 없다: %d (§4.3)" % (vocab_count, vocabulary))
    # 형태만으로는 부족하다 - 정답표 처리가 망가져 60이 40으로 나와도 40은 여전히 정상 형태다.
    # 이 답안 전략이 실제로 몇 점이어야 하는지와 맞춰야 그 회귀가 걸린다 (Codex sol 리뷰 P2).
    expect(vocabulary == expected_vocabulary,
           "단어 점수가 제출한 답안의 기대값과 다르다: 선택지 %d번 전략의 기대 %d, 응답 %d. "
           "정의(%s)의 정답표나 채점이 바뀌었다면 EXPECTED_VOCABULARY를 갱신한다."
           % (scenario.vocab_choice_index, expected_vocabulary, vocabulary,
              scenario.definition.test_version))

    expected_overall = round_half_up(
        intonation * INTONATION_WEIGHT + vocabulary * VOCABULARY_WEIGHT,
        INTONATION_WEIGHT + VOCABULARY_WEIGHT,
    )
    expect(overall == expected_overall,
           "종합 점수가 sv-0.3 집계식과 다르다: 억양 %d, 단어 %d -> 기대 %d, 응답 %d"
           % (intonation, vocabulary, expected_overall, overall))

    code, name, rank = tier_for(overall)
    tier = result.get("tier") or {}
    expect(tier.get("code") == code and tier.get("name") == name and tier.get("rank") == rank,
           "등급이 sv-0.3 경계와 다르다: 종합 %d -> 기대 %s(%s, rank %d), 응답 %s"
           % (overall, code, name, rank, tier))
    expect(tier.get("of") == len(TIERS),
           "tier.of가 %d가 아니다 (§3.7): %s" % (len(TIERS), tier))

    expect(isinstance(result.get("comment"), str) and result["comment"].strip(),
           "결과에 코멘트가 없다 (§3.7): %s" % result.get("comment"))
    share = result.get("share") or {}
    for key in ("imageUrl", "text", "webTestUrl"):
        expect(isinstance(share.get(key), str) and share[key].strip(),
               "share.%s가 비어 있다 (§3.7): %s" % (key, share))
    expect(isinstance(result.get("expiresAt"), str) and result["expiresAt"],
           "결과에 expiresAt이 없다 (§3.7): %s" % result.get("expiresAt"))

    if expected_intonation is not None:
        expect(intonation == expected_intonation,
               "억양 점수가 AI 스텁 해시 기대값과 다르다: 기대 %d, 응답 %d. "
               "BE가 X-Correlation-Id를 AI로 전파하지 않거나 스텁이 hashed 모드가 아니다 (KAN-136)."
               % (expected_intonation, intonation))

    log("  검산 통과: 억양 %d, 단어 %d, 종합 %d -> %s(%s)"
        % (intonation, vocabulary, overall, code, name))


def resolve_expected_intonation(scenario: Scenario, args: argparse.Namespace) -> Optional[int]:
    """검산할 억양 점수. 확인할 근거가 없으면 None이다.

    ``--pin-intonation``은 요청이 아니라 **기대**로 다룬다 (Codex sol 리뷰 P2). 고정해 놓고
    확인하지 않으면, 추적 ID 전파가 끊겨 고정이 아무 효력도 없는 서버에서도 통과한다 -
    특정 등급을 재현하려고 켠 옵션이 정작 그 등급이 나왔는지를 안 보는 셈이다.

    고정하지 않았다면 ``--expect-stub-scores``를 켠 경우에만 예측할 수 있다. 문항마다 다른
    추적 ID를 썼으므로 다섯 해시의 사사오입 평균이 억양 점수다 (§4.3).
    """
    if args.pin_intonation is not None:
        return args.pin_intonation
    if args.expect_stub_scores:
        return predicted_intonation(scenario)
    return None


def resolve_expected_vocabulary(scenario: Scenario, args: argparse.Namespace) -> int:
    """이 답안 전략이 받아야 할 단어 점수 (:data:`EXPECTED_VOCABULARY`).

    모르는 조합이면 경고하고 넘어가지 않고 멈춘다 - 모르는 점수 버전을 만났을 때와 같은
    태도다. 검산할 수 없는 회차를 통과로 적으면 그 회차는 스모크가 아니다.
    """
    if args.expect_vocabulary is not None:
        return args.expect_vocabulary
    key = (scenario.definition.test_version, scenario.vocab_choice_index)
    known = EXPECTED_VOCABULARY.get(key)
    expect(known is not None,
           "%s / 선택지 %d번 조합의 단어 점수 기대값을 모른다. 발행본의 정답표로 계산해 "
           "EXPECTED_VOCABULARY에 한 줄을 더하거나, --expect-vocabulary로 넘긴다." % key)
    return known


def predicted_intonation(scenario: Scenario) -> int:
    """스크립트가 보낸 추적 ID로 억양 점수를 미리 계산한다 (``--expect-stub-scores``).

    문항 점수는 AI 원점수를 20점 만점으로 환산한 값이고 억양 점수는 그 합이라, 결국
    원점수 다섯의 사사오입 평균과 같다 (§4.3).
    """
    voice_ids = scenario.definition.voice_ids()
    raw = [hashed_score(scenario.scored_correlation_ids[item_id]) for item_id in voice_ids]
    return round_half_up(sum(raw), len(raw))


# ---------------------------------------------------------------------------
# 합성 트래픽 분리 검증 (KAN-138)
# ---------------------------------------------------------------------------


def analytics_window(client: Client, synthetic_key: str) -> Tuple[str, str]:
    """앞뒤 스냅샷이 함께 볼 기간을 정한다 (명세서 §6).

    기간을 고정하지 않으면 두 스냅샷이 서로 다른 날을 본다 (Codex sol 리뷰 P2) - 실행이 서버
    타임존의 자정을 넘기면 앞은 어제, 뒤는 오늘을 읽고, 그 차를 빼는 순간 남의 날 숫자를
    비교하게 된다. 서버가 아는 오늘을 한 번 물어 그 날부터 다음 날까지를 창으로 잡는다.
    자정을 넘겨도 두 스냅샷이 같은 창을 덮는다.
    """
    response = client.request(
        "GET", "/admin/v0/analytics?traffic=ALL",
        headers={ADMIN_TOKEN_HEADER: synthetic_key},
    )
    expect(response.status == 200,
           "집계 조회가 200이 아니다 - --synthetic-key가 이 서버의 관리자 토큰과 같은가: "
           + response.describe())
    today = response.json().get("to")
    expect(isinstance(today, str) and today,
           "집계 응답에 기간이 없다 (§6): %s" % response.body[:200])
    tomorrow = (datetime.date.fromisoformat(today) + datetime.timedelta(days=1)).isoformat()
    return today, tomorrow


def analytics_snapshot(
    client: Client, synthetic_key: str, window: Tuple[str, str]
) -> Dict[str, Dict[str, int]]:
    """정해진 기간의 익명 집계를 트래픽 종류별로 접어 온다 (명세서 §6).

    조회에도 같은 관리자 시크릿을 쓴다 - 표시와 조회가 같은 권한이라 비밀을 하나 더
    만들 이유가 없다. ``traffic=ALL``이라야 두 종류가 함께 실린다.
    """
    response = client.request(
        "GET", "/admin/v0/analytics?traffic=ALL&from=%s&to=%s" % window,
        headers={ADMIN_TOKEN_HEADER: synthetic_key},
    )
    expect(response.status == 200,
           "집계 조회가 200이 아니다 - --synthetic-key가 이 서버의 관리자 토큰과 같은가: "
           + response.describe())
    body = response.json()
    totals = {"REAL": {"started": 0, "completed": 0}, "SYNTHETIC": {"started": 0, "completed": 0}}
    for row in body.get("rows") or []:
        bucket = totals.setdefault(row.get("traffic"), {"started": 0, "completed": 0})
        counts = row.get("counts") or {}
        bucket["started"] += counts.get("sessionsStarted", 0)
        bucket["completed"] += counts.get("sessionsCompleted", 0)
    return totals


def verify_traffic_separation(
    before: Dict[str, Dict[str, int]],
    after: Dict[str, Dict[str, int]],
    sessions: int,
    local: bool,
) -> None:
    """스모크가 만든 응시와 완주가 합성 쪽에만 쌓였는지 확인한다.

    실사용자 쪽을 등호로 못박는 것은 로컬뿐이다 - staging과 prod에는 진짜 사용자가 동시에
    들어올 수 있어, 그쪽 증가를 0이라고 우기면 남의 트래픽 때문에 스모크가 실패한다.
    로컬은 이 스크립트 말고 아무도 두드리지 않으므로 등호가 성립하고, 표시가 통째로
    듣지 않는 회귀는 거기서 걸린다.
    """
    for key, expected in (("started", sessions), ("completed", sessions)):
        moved = after["SYNTHETIC"][key] - before["SYNTHETIC"][key]
        expect(moved >= expected,
               "합성 %s 카운터가 %d만큼 늘지 않았다 (실제 %d) - 표시가 듣지 않았다 (KAN-138)."
               % (key, expected, moved))
    real_started = after["REAL"]["started"] - before["REAL"]["started"]
    real_completed = after["REAL"]["completed"] - before["REAL"]["completed"]
    if local:
        expect(real_started == 0 and real_completed == 0,
               "실사용자 카운터가 움직였다 (응시 +%d, 완주 +%d) - 스모크가 통계를 오염시켰다."
               % (real_started, real_completed))
    log("  집계 분리 확인: 합성 +%d응시/+%d완주, 실사용자 +%d응시/+%d완주"
        % (after["SYNTHETIC"]["started"] - before["SYNTHETIC"]["started"],
           after["SYNTHETIC"]["completed"] - before["SYNTHETIC"]["completed"],
           real_started, real_completed))


def is_local(base_url: str) -> bool:
    return (urllib.parse.urlparse(base_url).hostname or "") in LOCAL_HOSTS


# ---------------------------------------------------------------------------
# 시나리오
# ---------------------------------------------------------------------------


def start_scenario(client: Client, name: str, args: argparse.Namespace) -> Scenario:
    log("=== %s ===" % name)
    session = create_session(client, args.synthetic_key)
    definition = fetch_definition(client, session)
    return Scenario(name, session, definition, args.vocab_choice_index)


def finish_scenario(client: Client, scenario: Scenario, args: argparse.Namespace) -> Dict[str, Any]:
    """완료 -> 결과 조회 -> 검산 -> 재조회 동일성 확인."""
    complete(client, scenario, args.analysis_timeout)
    result = fetch_result(client, scenario)
    verify_result(scenario, result,
                  resolve_expected_intonation(scenario, args),
                  resolve_expected_vocabulary(scenario, args))

    # 결과 화면 재진입(새로고침, 앱 복귀)이 같은 답을 받는지 (KAN-25 AC).
    again = fetch_result(client, scenario)
    expect(again == result, "결과 재조회가 첫 응답과 다르다 (KAN-25 AC)")
    return result


def run_happy_path(client: Client, args: argparse.Namespace) -> Dict[str, Any]:
    """정상 시나리오 - 세션 생성부터 결과 조회까지 한 번도 끊기지 않는 길."""
    scenario = start_scenario(client, "정상 시나리오", args)

    pinned = correlation_id_for_score(args.pin_intonation) if args.pin_intonation is not None else None
    first_wait_ms = 0
    for item in scenario.definition.voice_items:
        # 고정하지 않으면 앱과 같이 업로드마다 새 추적 ID를 쓴다 - 스텁 점수 분산(KAN-136)이
        # 문항별로 실제로 갈리는 상태에서 등급까지 검증하기 위해서다 (티켓 Requirements).
        correlation_id = pinned or new_correlation_id()
        accepted = upload_voice(client, scenario, item["itemId"], correlation_id)
        first_wait_ms = max(first_wait_ms, accepted["pollAfterMs"])

    submit_all_vocab(client, scenario)

    poll_analyses(client, scenario, first_wait_ms, args.analysis_timeout)
    return finish_scenario(client, scenario, args)


def run_failure_branch(client: Client, args: argparse.Namespace) -> Dict[str, Any]:
    """실패 갈래 - RETRYABLE_FAILED를 만들고 재업로드로 복구해 완주한다 (티켓 Requirements).

    전제는 AI가 ``ACCENTURY_AI_STUB_FAIL_ITEM=<fail-item>``으로 떠 있는 것이다. 그 설정은
    기동 시 한 번만 읽히므로(``ai/app/config.py``의 ``Settings.from_env``를 ``main.py``의
    ``create_app``이 1회 호출한다) 그 문항은 프로세스가
    사는 동안 언제나 422로 떨어진다 - 같은 문항을 다시 올리는 것만으로는 복구가 성립하지
    않는다. 그래서 복구 지점에서 ``--recover-cmd``를 실행해 AI를 그 설정 없이 다시 띄운다.
    BE와 DB는 그대로 살아 있으므로 세션과 이미 성공한 문항은 유지된다.
    """
    scenario = start_scenario(client, "실패 갈래 (%s 실패 후 재업로드 복구)" % args.fail_item, args)
    fail_item = args.fail_item
    expect(fail_item in scenario.definition.voice_ids(),
           "--fail-item %s가 이 테스트 버전의 음성 문항이 아니다: %s"
           % (fail_item, scenario.definition.voice_ids()))

    pinned = correlation_id_for_score(args.pin_intonation) if args.pin_intonation is not None else None
    first_wait_ms = 0
    for item in scenario.definition.voice_items:
        correlation_id = pinned or new_correlation_id()
        accepted = upload_voice(client, scenario, item["itemId"], correlation_id)
        first_wait_ms = max(first_wait_ms, accepted["pollAfterMs"])

    submit_all_vocab(client, scenario)

    statuses = poll_analyses(
        client, scenario, first_wait_ms, args.analysis_timeout, allow_failed=[fail_item]
    )
    failed = statuses[fail_item]
    expect(failed["status"] == "RETRYABLE_FAILED",
           "%s가 RETRYABLE_FAILED로 끝나지 않았다 (%s) - AI가 "
           "ACCENTURY_AI_STUB_FAIL_ITEM=%s로 떠 있는지 확인한다."
           % (fail_item, failed.get("status"), fail_item))
    log("  실패 확인 %s error=%s" % (fail_item, failed.get("error")))

    # 실패 문항이 남아 있는 동안 완료는 409 RESULT_RETAKE_REQUIRED다 (§3.6) - 앱이 재녹음
    # 화면으로 안내하는 근거이고, 이 갈래가 실제로 그 응답을 내는지 여기서 확인한다.
    complete_path = "/v0/sessions/%s/complete" % urllib.parse.quote(scenario.session.session_id)
    headers = dict(scenario.session.auth())
    headers["Idempotency-Key"] = "e2e-complete-" + uuid.uuid4().hex
    response = client.request("POST", complete_path, headers=headers, body=b"")
    expect(response.status == 409, "실패 문항이 있는데 완료가 409가 아니다: " + response.describe())
    envelope = response.json()
    expect(envelope.get("code") == "RESULT_RETAKE_REQUIRED",
           "완료 오류 코드가 RESULT_RETAKE_REQUIRED가 아니다 (§3.6): %s" % envelope)
    expect(envelope.get("retakeItems") == [fail_item],
           "retakeItems가 실패 문항과 다르다 (§3.6): %s" % envelope)
    log("  완료 409 RESULT_RETAKE_REQUIRED retakeItems=%s" % envelope.get("retakeItems"))

    run_recover_command(args.recover_cmd)
    time.sleep(RECOVER_SETTLE_MS / 1000)

    # 재업로드는 새 시도다 (§3.3) - 키를 새로 만드는 것이 upload_voice의 기본 동작이다.
    correlation_id = pinned or new_correlation_id()
    accepted = upload_until_completed(
        client, scenario, fail_item, correlation_id, args.analysis_timeout
    )
    expect(accepted["attempt"] >= 2,
           "재업로드인데 attempt가 2 이상이 아니다 (§3.3): %s" % accepted)
    log("  복구 확인 %s -> COMPLETED (attempt=%d)" % (fail_item, accepted["attempt"]))

    return finish_scenario(client, scenario, args)


def run_recover_command(command: str) -> None:
    """AI를 STUB_FAIL_ITEM 없이 다시 띄운다.

    레포 루트에서 돌린다 - ``docker compose ...``가 compose 파일을 찾는 자리이고,
    ``scripts/push-images.sh``도 같은 규칙이다. 셸을 거치므로 파이프와 여러 명령을 그대로
    쓸 수 있다.
    """
    repo_root = Path(__file__).resolve().parent.parent
    log("  복구 명령 실행: %s (cwd=%s)" % (command, repo_root))
    completed = subprocess.run(command, shell=True, cwd=str(repo_root))
    expect(completed.returncode == 0,
           "복구 명령이 실패했다 (exit %d): %s" % (completed.returncode, command))


# ---------------------------------------------------------------------------
# 진입점
# ---------------------------------------------------------------------------


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Accentury 전 구간 E2E 스모크 (KAN-138)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--base-url", required=True,
                        help="대상 BE 주소 (예: http://127.0.0.1:8080, https://api.staging.accentury.app)")
    parser.add_argument("--timeout", type=float, default=DEFAULT_REQUEST_TIMEOUT,
                        help="요청 하나의 상한(초). 기본 %.0f" % DEFAULT_REQUEST_TIMEOUT)
    parser.add_argument("--analysis-timeout", type=float, default=DEFAULT_ANALYSIS_TIMEOUT,
                        help="분석과 완료를 기다릴 상한(초). 기본 %.0f" % DEFAULT_ANALYSIS_TIMEOUT)
    parser.add_argument("--fail-item", metavar="ITEM_ID",
                        help="실패 갈래를 돌린다. AI가 ACCENTURY_AI_STUB_FAIL_ITEM=<ITEM_ID>로 떠 있어야 한다. "
                             "--recover-cmd와 함께 쓴다.")
    parser.add_argument("--recover-cmd", metavar="COMMAND",
                        help="실패 갈래의 복구 지점에서 실행할 명령. AI를 STUB_FAIL_ITEM 없이 다시 띄운다. "
                             "예: 'docker compose up -d --no-deps --wait ai'")
    parser.add_argument("--expect-stub-scores", action="store_true",
                        help="억양 점수를 AI 스텁 해시(KAN-136)로 예측해 응답과 대조한다. "
                             "실모델(KAN-22)이 붙은 환경에서는 쓰지 않는다.")
    parser.add_argument("--pin-intonation", type=int, metavar="SCORE",
                        help="음성 5문항에 같은 추적 ID를 고정해 억양 점수를 이 값(0~100)으로 만든다. "
                             "이 값은 결과에서 그대로 검산한다. 도달 등급은 단어 점수와 함께 정해지므로 "
                             "--vocab-choice-index를 같이 쓴다.")
    parser.add_argument("--vocab-choice-index", type=int, default=0, metavar="N",
                        help="어휘 문항에서 고를 선택지의 인덱스(0부터). 단어 점수를 옮기는 손잡이다. "
                             "gn-2026.08.1에서는 0=60점, 1=40점, 2와 3=0점이다.")
    parser.add_argument("--expect-vocabulary", type=int, metavar="SCORE",
                        help="단어 점수 기대값을 직접 준다. EXPECTED_VOCABULARY에 없는 새 testVersion을 "
                             "코드 수정 없이 한 번 돌릴 때 쓴다.")
    parser.add_argument("--synthetic-key", metavar="TOKEN",
                        help="이 스모크의 세션을 합성 트래픽으로 표시할 관리자 시크릿 (KAN-138). "
                             "서버의 accentury.admin.token과 같아야 하고, 표시된 세션의 집계는 "
                             "실사용자와 다른 행에 쌓인다. 로컬이 아닌 대상에는 필수다.")
    parser.add_argument("--allow-real-traffic", action="store_true",
                        help="--synthetic-key 없이 원격 대상을 두드리는 것을 허용한다. "
                             "이 실행은 그 환경의 완주율과 등급 분포에 영구히 남는다.")
    parser.add_argument("--verbose", action="store_true", help="요청과 응답 상태를 한 줄씩 찍는다.")
    args = parser.parse_args(argv)

    if args.pin_intonation is not None and not 0 <= args.pin_intonation <= 100:
        parser.error("--pin-intonation은 0~100이어야 합니다.")
    if args.vocab_choice_index < 0:
        parser.error("--vocab-choice-index는 0 이상이어야 합니다.")
    if args.expect_vocabulary is not None and not 0 <= args.expect_vocabulary <= 100:
        parser.error("--expect-vocabulary는 0~100이어야 합니다.")
    if args.fail_item and not args.recover_cmd:
        parser.error(
            "--fail-item에는 --recover-cmd가 필요합니다. "
            "ACCENTURY_AI_STUB_FAIL_ITEM은 AI 기동 시 한 번만 읽히므로, "
            "복구하려면 그 설정 없이 AI를 다시 띄워야 합니다."
        )
    if args.recover_cmd and not args.fail_item:
        parser.error("--recover-cmd는 --fail-item과 함께 씁니다.")
    if not args.synthetic_key and not is_local(args.base_url) and not args.allow_real_traffic:
        parser.error(
            "원격 대상에는 --synthetic-key가 필요합니다. 표시가 없으면 이 스모크의 응시와 완주가 "
            "익명 집계(KAN-106)에 실사용자로 영구히 남고, 일자 합계라 나중에 빼낼 수도 없습니다. "
            "그래도 돌리려면 --allow-real-traffic을 명시하세요."
        )
    return args


def main(argv: Sequence[str]) -> int:
    args = parse_args(argv)
    client = Client(args.base_url, args.timeout, args.verbose)
    log("대상 %s" % client.base_url)

    if not args.synthetic_key:
        log("경고: 합성 트래픽 표시 없이 돈다 - 이 실행은 대상 환경의 익명 집계에 그대로 남는다.")

    try:
        # 분리가 실제로 듣는지는 집계를 앞뒤로 읽어 확인한다 (KAN-138) - 표시가 조용히
        # 무시되면 여기서만 드러난다.
        window = analytics_window(client, args.synthetic_key) if args.synthetic_key else None
        before = analytics_snapshot(client, args.synthetic_key, window) if window else None

        sessions = 0
        if args.fail_item:
            # 실패 갈래를 먼저 돈다. AI가 아직 STUB_FAIL_ITEM을 물고 있어 정상 시나리오가
            # 그 문항에서 걸리기 때문이다. 복구 명령이 AI를 깨끗하게 되돌린 뒤에야 정상
            # 시나리오가 성립한다.
            run_failure_branch(client, args)
            sessions += 1
        run_happy_path(client, args)
        sessions += 1

        if before is not None:
            after = analytics_snapshot(client, args.synthetic_key, window)
            verify_traffic_separation(before, after, sessions, is_local(client.base_url))
    except SmokeFailure as failure:
        log("실패: %s" % failure)
        return 1

    log("스모크 통과")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
