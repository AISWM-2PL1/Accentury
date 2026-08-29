import Foundation

/// 문항 결과를 웹으로 넘기는 주입 JS (KAN-100). 실제 `evaluateJavaScript` 결선은 화면
/// 오케스트레이션 몫이고, 이 파일은 문자열 생성만 한다 — 그래야 시뮬레이터 없이 단위 테스트로
/// 이스케이프 규칙을 직접 검증할 수 있다.
///
/// 이스케이프·반환값 규칙은 ``webDeliveryJs(method:payloadJson:)``에 모여 있다. 여기서 정하는
/// 것은 "어느 수신 지점으로 가는가" 하나뿐이다.
///
/// 호출자는 돌려받은 JS의 평가 결과(`true`/`false`)로 녹음 화면을 놓을 때를 정한다 (KAN-146) —
/// 못 넘긴 것을 넘긴 것으로 읽으면 화면이 앞 문항의 대기 화면 위로 걷힌다.
public func itemResultDeliveryJs(_ result: ItemResult) -> String {
    webDeliveryJs(method: "onItemResult", payloadJson: result.toJson())
}
