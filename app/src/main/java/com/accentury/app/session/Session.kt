package com.accentury.app.session

import kotlinx.serialization.Serializable

/**
 * 서버가 발급한 익명 테스트 세션 (KAN-9, API 명세서 §3.1).
 *
 * `POST /v0/sessions`의 201 응답 5필드를 그대로 담는다. 앱이 지금 읽는 것은 앞의 셋이지만
 * (진입 URL·업로드·브리지) 계약이 다섯을 함께 주므로 손실 없이 들고 있는다 — 응답의 일부만
 * 담으면 나중에 만료 안내나 점수 버전 표기를 붙일 때 세션을 다시 만들어야 한다.
 *
 * [sessionToken]은 이 응답에서 딱 한 번 노출되고 서버에는 해시만 남는다. 잃어버리면 재발급이
 * 아니라 새 세션이고, 새 세션은 지금까지의 응시를 버리는 것이라 회전·프로세스 복원을 넘기는
 * 저장이 필수다 — @Serializable인 이유가 그것이다 ([SessionGateController.saver]가 JSON 한 줄로
 * 접어 Bundle에 싣는다, TestFlowController와 같은 방식).
 */
@Serializable
data class Session(
    /** `s_...` — 업로드·결과 조회의 경로 파라미터이자 웹 진입 URL의 sessionId */
    val sessionId: String,
    /** `st_...` — `Authorization: Bearer`로 보낼 불투명 토큰. 웹의 어휘 답안 제출(KAN-13)도 이 값을 쓴다 */
    val sessionToken: String,
    /** 이 세션에 고정된 문항 정의 버전. 웹이 `GET /v0/tests/{testVersion}`으로 정의를 받는다 (§5.4) */
    val testVersion: String,
    /** 이 세션에 고정된 점수 산정 버전 */
    val scoreVersion: String,
    /** 토큰 만료 시각 (ISO-8601 UTC, 기본 30분) */
    val expiresAt: String,
)
