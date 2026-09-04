import XCTest

/// 코틀린 테스트의 `advanceUntilIdle()` 자리.
///
/// 가상 시간 스케줄러가 없는 Swift에서는 "다 돌 때까지"를 선언적으로 기다릴 방법이 없어,
/// 조건이 참이 될 때까지 짧게 양보하며 기다린다. 실패 판정은 시간이 아니라 **조건**이다.
///
/// 상한은 영영 멈추는 것을 막는 안전선일 뿐, 성능 단언이 아니다. 그래서 넉넉히 잡는다 —
/// 5초로 뒀더니 녹음 두 번(디버그 빌드에서 YIN이 프레임당 ~28ms라 회당 3.4초)을 기다리는
/// 테스트가 머신이 바쁠 때 상한에 걸려 한 번 튀었다. 진짜 회귀는 조건이 **영영** 참이 되지
/// 않는 것이라 상한을 늘려도 검출력은 그대로고, 대기 시간만 실제 소요만큼으로 줄어든다.
@discardableResult
func waitUntil(
    _ message: @autoclosure () -> String = "조건이 만족되지 않았다",
    timeout: TimeInterval = 30,
    file: StaticString = #filePath,
    line: UInt = #line,
    _ condition: () async -> Bool
) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if await condition() { return true }
        try? await Task.sleep(nanoseconds: 200_000) // 0.2ms
    }
    XCTFail(message(), file: file, line: line)
    return false
}
