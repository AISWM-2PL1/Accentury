import XCTest
@testable import Accentury

/// 진행 도트의 세 상태 (KAN-161 2단계). 안드로이드 `ui/ProgressDotStateTest.kt`의 이식본이다.
///
/// 화면에서는 "진행이 한 칸 밀렸다"로만 드러나 원인을 찾기 어려운 종류라 — 부등호 하나
/// 차이다 — 경계를 여기서 고정한다.
final class ProgressDotStateTests: XCTestCase {

    /// 현재 칸보다 앞은 완료다.
    func testPositionsBeforeCurrentAreDone() {
        XCTAssertEqual(.done, progressDotState(position: 1, current: 3))
        XCTAssertEqual(.done, progressDotState(position: 2, current: 3))
    }

    /// 현재 칸은 하나뿐이다 — 반만 찬 칸이 둘이면 어디까지 왔는지 알 수 없다.
    func testExactlyOnePositionIsCurrent() {
        XCTAssertEqual(.current, progressDotState(position: 3, current: 3))
    }

    /// 현재 칸보다 뒤는 미완료다.
    func testPositionsAfterCurrentAreTodo() {
        XCTAssertEqual(.todo, progressDotState(position: 4, current: 3))
        XCTAssertEqual(.todo, progressDotState(position: 10, current: 3))
    }

    /// 첫 문항은 첫 칸이 현재다 — 시작도 안 한 화면으로 보이지 않는다.
    func testFirstQuestionMarksTheFirstDotCurrent() {
        XCTAssertEqual(.current, progressDotState(position: 1, current: 1))
        XCTAssertEqual(.todo, progressDotState(position: 2, current: 1))
    }

    /// 마지막 문항을 끝내면 모든 칸이 완료다.
    func testFinishingTheLastQuestionFillsEveryDot() {
        for position in 1...10 {
            XCTAssertEqual(.done, progressDotState(position: position, current: 11))
        }
    }
}
