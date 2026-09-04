"""내부 호출 인증 - 공유 시크릿 헤더 (KAN-36).

AI 서버가 backend와 다른 EC2로 갈라지면서(KAN-36 A단계) "같은 compose 내부 네트워크에만
붙어 있어 backend 말고는 아무도 부를 수 없다"는 전제가 사라졌다. 보안 그룹이 backend 호스트
밖에서 오는 8000을 막지만 그 한 겹이 전부이면 안 된다 - 잘못 붙은 SG나 뒤에 추가되는 VPC 안
호스트가 분석 요청을 밀어 넣을 수 있고, 이 서버는 신뢰하지 않는 오디오를 받아 추론 슬롯을
태우는 곳이다. 그래서 backend와 AI가 SSM에서 같은 값을 받는 공유 시크릿을 요청마다 헤더로
대조한다 (infra/modules/config가 한 난수를 두 이름으로 싣는다).

미들웨어로 거는 이유는 본문 상한(:mod:`app.limits`)과 같다 - 엔드포인트 의존성으로 걸면
FastAPI가 multipart 본문을 전부 읽어 파트로 만든 뒤에야 검사가 돈다. 인증되지 않은 호출은
오디오를 임시파일로 내려놓기 전에 끊어야 한다. 본문은 읽어서 버린다 - 응답을 먼저 보내고
연결을 닫으면 아직 본문을 보내던 backend가 응답 대신 연결 오류를 보고, 그것을 일시 장애
(재전송)로 오판한다. 읽는 양은 바깥의 본문 상한 미들웨어가 막으므로 유한하다.

``/internal/v0/health``는 예외다. compose healthcheck와 호스트의 상태 지표 프로브가 토큰
없이 두드리고, 새는 정보는 "떠 있다 / 아직이다"뿐이다.

토큰이 설정되지 않은 서버는 검사를 건너뛴다 - uvicorn을 직접 띄우는 로컬 개발의 편의이고,
배포에서는 Terraform이 언제나 값을 넣는다. 그 상태는 기동 로그에 경고로 남는다
(:func:`app.main.create_app`).
"""

from __future__ import annotations

import hmac
import json
import logging

log = logging.getLogger(__name__)

#: backend(``RestAiAnalysisClient``)가 붙이는 헤더. 이름은 양쪽 코드에 상수로만 있고 설정이 아니다.
INTERNAL_TOKEN_HEADER = "X-Accentury-Internal-Token"

#: 토큰 없이 열어 두는 경로 - 생존 신호만 준다.
HEALTH_PATH = "/internal/v0/health"

_REJECTED_BODY = json.dumps(
    {"status": "FAILED", "detail": "내부 호출 토큰이 없거나 다르다"}, ensure_ascii=False
).encode("utf-8")


class InternalTokenMiddleware:
    """``X-Accentury-Internal-Token``이 설정값과 같지 않은 요청을 401로 끊는다."""

    def __init__(self, app, token: str | None, open_paths: tuple[str, ...] = (HEALTH_PATH,)) -> None:
        self.app = app
        self._token = token.encode("utf-8") if token else None
        self._open_paths = open_paths

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http" or self._token is None or scope["path"] in self._open_paths:
            await self.app(scope, receive, send)
            return

        if _matches(scope, self._token):
            await self.app(scope, receive, send)
            return

        # 토큰 값은 어느 쪽도 로그에 남기지 않는다 - 경로와 사실만 적는다
        log.warning("내부 호출 토큰 불일치 - 401로 끊는다 path=%s", scope["path"])
        await _drain(receive)
        await send(
            {
                "type": "http.response.start",
                "status": 401,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(_REJECTED_BODY)).encode("ascii")),
                ],
            }
        )
        await send({"type": "http.response.body", "body": _REJECTED_BODY})


def _matches(scope, expected: bytes) -> bool:
    header = INTERNAL_TOKEN_HEADER.lower().encode("ascii")
    for name, value in scope.get("headers", []):
        if name == header:
            # 길이가 달라도 시간이 새지 않는 비교 - 값 자체를 알아내는 경로를 남기지 않는다
            return hmac.compare_digest(value, expected)
    return False


async def _drain(receive) -> None:
    """요청 본문을 끝까지 읽어 버린다 - 바깥 상한 미들웨어가 조각 수를 유한하게 만든다."""
    while True:
        message = await receive()
        if message["type"] != "http.request" or not message.get("more_body", False):
            return
