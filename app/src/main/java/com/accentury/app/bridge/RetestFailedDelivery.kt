package com.accentury.app.bridge

/**
 * 재응시 실패를 웹으로 회신하는 주입 JS (KAN-34 2단계).
 *
 * [itemResultDeliveryJs]와 같은 모양이고 수신 지점 이름만 다르다 — 이스케이프·반환값 규칙은
 * [webDeliveryJs]에 모여 있다.
 *
 * **성공은 회신하지 않는다.** 재응시가 성공하면 네이티브가 WebView를 인트로 URL로 다시 로드하므로
 * 회신을 받을 페이지 자체가 사라진다. 웹에 알릴 것이 남는 쪽은 실패뿐이다 — 결과 화면이 그대로
 * 살아 있고, 눌러 둔 [다시 테스트하기]가 왜 아무 일도 하지 않았는지 사용자에게 말해야 한다.
 *
 * 돌려주는 boolean(수신 지점이 있었는가)을 호출자가 쓰지 않는 이유: 없다면 결과 화면이 아직
 * 수신자를 설치하기 전이라는 뜻인데, 네이티브가 할 수 있는 일이 없다. 세션은 이전 것 그대로라
 * 화면이 깨지지도 않는다 — 사용자가 버튼을 다시 누르면 같은 요청이 다시 나간다.
 */
fun retestFailedDeliveryJs(failure: RetestFailure): String =
    webDeliveryJs(method = "onRetestFailed", payloadJson = failure.toJson())
