"""요청 본문 상한을 multipart 파싱 <b>전에</b> 강제하는 ASGI 미들웨어 (Codex sol 리뷰 P2).

엔드포인트 코드가 도는 시점에는 웹 프레임워크가 이미 본문 전체를 읽어 파트로 만든 뒤다 -
그때 크기를 재면 "받아 놓고 거절"이라 디스크가 먼저 찬다. 그래서 상한은 본문을 읽는
길목에서 건다. 사설망 전용 서버라도(§1.1) BE 버그나 잘못된 호출 하나로 디스크가 차면
전 사용자의 분석이 멈추기 때문이다.

BE의 ``spring.servlet.multipart.max-request-size``(2MB)와 같은 자리의 방어선이다.
운영에서는 리버스 프록시에도 같은 상한을 두는 것이 정석이다 (KAN-36).

거절은 ``receive``와 ``send`` 양쪽에서 이뤄진다. 흘러온 바이트를 세다 상한을 넘기면
``receive`` 쪽에서 끊지만, 그 예외가 호출부까지 올라온다는 보장이 없기 때문이다 -
폼 파싱 도중이면 FastAPI가 삼켜서 400 "There was an error parsing the body"로 바꾼다
(Codex 리뷰). 그래서 상한을 넘긴 뒤 앱이 만든 응답은 ``send`` 쪽에서 413으로 갈아끼운다.
"""

from __future__ import annotations

import json
import logging

log = logging.getLogger(__name__)

_REJECTED_BODY = json.dumps(
    {"status": "FAILED", "detail": "요청 본문 상한 초과"}, ensure_ascii=False
).encode("utf-8")


class _BodyTooLarge(Exception):
    """본문이 상한을 넘겼다 - 파싱을 계속하지 않고 빠져나오기 위한 신호."""


class MaxBodySizeMiddleware:
    """선언된 Content-Length와 실제로 흘러온 바이트 수 양쪽을 본다."""

    def __init__(self, app, max_bytes: int) -> None:
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        # 선언값이 이미 넘으면 본문을 한 조각도 읽지 않고 끊는다
        if _declared_length(scope) > self.max_bytes:
            await _reject(send)
            return

        received = 0
        exceeded = False
        responded = False

        async def limited_receive():
            # Content-Length 없이 chunked로 밀어 넣는 경우 - 흘러온 만큼 세다가 끊는다
            nonlocal received, exceeded
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_bytes:
                    exceeded = True
                    log.warning("요청 본문 상한 초과 - 파싱 전에 끊었다")
                    raise _BodyTooLarge
            return message

        async def guarded_send(message):
            # 상한을 넘긴 뒤에 앱이 만든 응답은 내보내지 않는다 (Codex 리뷰). 폼 파싱
            # 도중에 올린 _BodyTooLarge는 FastAPI가 삼키고 400으로 바꿔 버려서, 아래
            # except 절만으로는 스트리밍 경로의 413이 영영 나가지 않는다
            nonlocal responded
            if responded:
                return
            if exceeded and message["type"] == "http.response.start":
                responded = True
                await _reject(send)
                return
            await send(message)

        try:
            await self.app(scope, limited_receive, guarded_send)
        except _BodyTooLarge:
            # 앱이 예외를 그대로 올려보낸 경우 - 아직 응답이 시작되지 않았다
            if not responded:
                responded = True
                await _reject(send)


def _declared_length(scope) -> int:
    for name, value in scope.get("headers", []):
        if name == b"content-length":
            try:
                return int(value)
            except ValueError:
                return 0
    return 0


async def _reject(send) -> None:
    await send(
        {
            "type": "http.response.start",
            "status": 413,
            "headers": [
                (b"content-type", b"application/json"),
                (b"content-length", str(len(_REJECTED_BODY)).encode("ascii")),
            ],
        }
    )
    await send({"type": "http.response.body", "body": _REJECTED_BODY})
