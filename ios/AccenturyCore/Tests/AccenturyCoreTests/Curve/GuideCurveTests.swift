import XCTest
@testable import AccenturyCore

/// 안드로이드 `recording/GuideCurveTest.kt`의 1:1 이식본 (11개).
final class GuideCurveTests: XCTestCase {

    /// `전부 무성이면 그릴 점이 없다`
    func testAllUnvoicedDrawsNothing() {
        XCTAssertEqual([], guideCurveDisplayPoints([nil, nil, nil]))
        XCTAssertEqual([], guideCurveDisplayPoints([]))
    }

    /// `NaN도 무성으로 취급한다`
    func testNaNCountsAsUnvoiced() {
        XCTAssertEqual([], guideCurveDisplayPoints([Double.nan]))
    }

    /// `높은 음이 위로 간다 - 값이 클수록 y가 작다`
    func testHigherPitchGoesUp() {
        let points = guideCurveDisplayPoints([-1.0, 0.0, 1.0, 2.0])
        for k in 0..<(points.count - 1) {
            XCTAssertGreaterThan(points[k].y, points[k + 1].y, "y는 단조 감소해야 한다: \(points)")
        }
    }

    /// `0은 무성이 아니라 유효한 semitone 값이다`
    func testZeroIsAValidSemitoneNotUnvoiced() {
        // 0을 무성으로 잘못 취급하면 세 점이 아니라 두 점이 나온다
        let points = guideCurveDisplayPoints([-1.0, 0.0, 1.0])
        XCTAssertEqual(3, points.count)
    }

    /// `중간 무성 구간은 양옆 값의 선형 보간으로 이어진다`
    func testInnerUnvoicedIsLinearlyInterpolated() {
        let points = guideCurveDisplayPoints([0.0, nil, nil, 3.0])
        XCTAssertEqual(4, points.count)
        // 0→3 사이 두 무성 프레임은 1, 2로 채워진다. y 간격이 균일한지로 확인한다.
        let gaps = (0..<3).map { points[$0].y - points[$0 + 1].y }
        for gap in gaps {
            XCTAssertEqual(gaps[0], gap, accuracy: 1e-5)
        }
    }

    /// `앞뒤 무성 구간은 그리지 않되 x 위치는 원래 시각을 유지한다`
    func testEdgeUnvoicedIsDroppedButXKeepsItsTime() {
        let points = guideCurveDisplayPoints([nil, 1.0, 2.0, 1.0, nil, nil])
        XCTAssertEqual(3, points.count)
        // 배열 길이 6 → x 간격은 1/5. 첫 유성 프레임은 index 1이므로 x = 0.2에서 시작한다.
        XCTAssertEqual(0.2, points.first!.x, accuracy: 1e-5)
        XCTAssertEqual(0.6, points.last!.x, accuracy: 1e-5)
    }

    /// `x는 시간축 전체를 0에서 1로 나눈 위치다`
    func testXSpansTheWholeTimeAxis() {
        let points = guideCurveDisplayPoints([1.0, 2.0, 3.0])
        XCTAssertEqual([0.0, 0.5, 1.0], points.map { $0.x })
    }

    /// `표시 스케일 여백 - 최고점과 최저점이 레인 가장자리에 붙지 않는다`
    func testDisplayScalePadding() {
        let points = guideCurveDisplayPoints([-2.0, 5.0])
        for point in points {
            XCTAssertTrue(point.y > 0.05 && point.y < 0.95, "y가 가장자리를 벗어났다: \(point)")
        }
        // 여백 10% 기준 최고점 y = 1 - 1.1/1.2 ≈ 0.0833
        XCTAssertEqual(0.0833, points.map { $0.y }.min()!, accuracy: 1e-3)
        XCTAssertEqual(0.9167, points.map { $0.y }.max()!, accuracy: 1e-3)
    }

    /// `평평한 곡선은 레인 중앙에 그린다`
    func testFlatCurveSitsInTheMiddle() {
        let points = guideCurveDisplayPoints([1.5, 1.5, 1.5])
        for point in points {
            XCTAssertEqual(0.5, point.y, accuracy: 1e-5)
        }
    }

    /// `거의 평평한 곡선의 미세 잡음은 레인 전체로 증폭되지 않는다`
    func testNearFlatNoiseIsNotAmplified() {
        // 부동소수 잡음 수준(1e-9 semitone)의 등락. 자기 스케일만 있으면 이게 전폭으로 튄다 —
        // 표시 범위 바닥값(0.5 semitone)이 잡음을 중앙 부근에 눌러 둔다.
        let points = guideCurveDisplayPoints([1.0, 1.0 + 1e-9, 1.0])
        for point in points {
            XCTAssertEqual(0.5, point.y, accuracy: 1e-3)
        }
    }

    /// `유성 프레임이 하나뿐이면 그 시각에 점 하나다`
    func testSingleVoicedFrameIsOneDot() {
        let points = guideCurveDisplayPoints([nil, 2.0, nil])
        XCTAssertEqual(1, points.count)
        XCTAssertEqual(0.5, points.first!.x, accuracy: 1e-5)
        XCTAssertEqual(0.5, points.first!.y, accuracy: 1e-5) // 값 하나는 range 0 - 중앙
    }
}
