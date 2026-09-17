import XCTest
@testable import AccenturyCore

/// 안드로이드 `recording/CurvePathTest.kt`의 1:1 이식본 (10개).
final class CurvePathTests: XCTestCase {

    private let width: Float = 100
    private let height: Float = 40

    /// x는 고르게, y는 오르내리게 - 중간점이 원래 점과 겹치지 않아야 검사가 의미를 갖는다.
    private func points(_ n: Int) -> [CurvePoint] {
        (0..<n).map { i in CurvePoint(x: Float(i) / 10, y: i % 2 == 0 ? 0.2 : 0.8) }
    }

    private func commands(_ n: Int) -> [PathCommand] {
        smoothPathCommands(points(n), width: width, height: height)
    }

    private func px(_ i: Int) -> Float { points(20)[i].x * width }
    private func py(_ i: Int) -> Float { points(20)[i].y * height }

    /// QuadTo만 골라 낸다 (코틀린 `filterIsInstance<PathCommand.QuadTo>()` 자리).
    private func quads(_ commands: [PathCommand]) -> [(cx: Float, cy: Float, x: Float, y: Float)] {
        commands.compactMap {
            guard case let .quadTo(cx, cy, x, y) = $0 else { return nil }
            return (cx, cy, x, y)
        }
    }

    /// `점이 2개면 중간점을 거치는 직선 두 도막이다`
    func testTwoPointsAreTwoStraightSegmentsViaTheMidpoint() {
        XCTAssertEqual(
            [
                .moveTo(x: px(0), y: py(0)),
                .lineTo(x: (px(0) + px(1)) / 2, y: (py(0) + py(1)) / 2),
                .lineTo(x: px(1), y: py(1)),
            ],
            commands(2)
        )
    }

    /// `점이 2개 미만이면 명령이 없다 - 원 그리기는 CurveLane이 한다`
    func testFewerThanTwoPointsEmitNoCommands() {
        XCTAssertEqual([], smoothPathCommands([], width: width, height: height))
        XCTAssertEqual([], smoothPathCommands(points(1), width: width, height: height))
    }

    /// `점이 붙어도 이미 그린 곡선은 다시 계산되지 않는다 - 인과성`
    func testAlreadyDrawnCommandsNeverChange() {
        // n개 명령에서 꼬리 LineTo 하나를 뺀 나머지 == n+1개 명령의 접두사.
        // 다시 그려지는 곳은 마지막 반 구간(직전 중간점 -> 마지막 점, 16ms)뿐이다.
        for n in 2...8 {
            let settled = Array(commands(n).dropLast())
            let next = commands(n + 1)

            XCTAssertLessThanOrEqual(
                settled.count,
                next.count,
                "n=\(n): 명령이 줄었다 (settled=\(settled.count), next=\(next.count))"
            )
            XCTAssertEqual(settled, Array(next.prefix(settled.count)), "n=\(n) 에서 이미 그린 구간이 바뀌었다")
        }
    }

    /// `점 하나가 늘 때 명령도 하나만 는다`
    func testOneMorePointAddsExactlyOneCommand() {
        // 접두사만 보면 "새 점이 아무것도 안 그렸다"도 통과한다 - 자라기는 자라야 한다.
        for n in 2...8 {
            XCTAssertEqual(commands(n).count + 1, commands(n + 1).count, "n=\(n)")
        }
    }

    /// `모든 QuadTo는 제어점이 원래 점이고 끝점이 이웃과의 중간점이다`
    func testEveryQuadUsesThePointAsControlAndTheMidpointAsEnd() {
        let n = 6
        let commands = commands(n)
        let quads = quads(commands)

        // i = 1..n-2 각각 하나씩.
        XCTAssertEqual(n - 2, quads.count)
        for (index, quad) in quads.enumerated() {
            let i = index + 1
            XCTAssertEqual(px(i), quad.cx, "제어점 x (i=\(i))")
            XCTAssertEqual(py(i), quad.cy, "제어점 y (i=\(i))")
            XCTAssertEqual((px(i) + px(i + 1)) / 2, quad.x, "끝점 x (i=\(i))")
            XCTAssertEqual((py(i) + py(i + 1)) / 2, quad.y, "끝점 y (i=\(i))")
        }
        // 곡선은 첫 중간점에서 시작해 마지막 점으로 닫힌다.
        XCTAssertEqual(PathCommand.moveTo(x: px(0), y: py(0)), commands.first)
        XCTAssertEqual(PathCommand.lineTo(x: (px(0) + px(1)) / 2, y: (py(0) + py(1)) / 2), commands[1])
        XCTAssertEqual(PathCommand.lineTo(x: px(n - 1), y: py(n - 1)), commands.last)
    }

    /// `비율 좌표에 캔버스 크기를 곱한다`
    func testRatioCoordinatesAreScaledByCanvasSize() {
        let scaled = smoothPathCommands(points(3), width: width * 2, height: height * 2)

        XCTAssertEqual(PathCommand.moveTo(x: px(0) * 2, y: py(0) * 2), scaled.first)
        XCTAssertEqual(PathCommand.lineTo(x: px(2) * 2, y: py(2) * 2), scaled.last)
    }

    // MARK: - KAN-218 천장·바닥 인셋. 웹 CurveLane.test.tsx의 인셋 케이스와 같은 값이다.

    /// `천장과 바닥은 선 굵기 절반만큼 안으로 들어온다 - 가운데는 그대로`
    func testCeilingAndFloorAreInsetByHalfTheStrokeAndTheMiddleStays() {
        let stroke: Float = 3
        let inset = insetY(
            [CurvePoint(x: 0, y: 0), CurvePoint(x: 0.5, y: 0.5), CurvePoint(x: 1, y: 1)],
            strokeWidth: stroke,
            height: height
        )

        // 픽셀로 옮겨 보면 y=0 → 1.5, y=0.5 → 20(불변), y=1 → 38.5다.
        XCTAssertEqual(stroke / 2, inset[0].y * height, accuracy: 1e-4)
        XCTAssertEqual(height / 2, inset[1].y * height, accuracy: 1e-4)
        XCTAssertEqual(height - stroke / 2, inset[2].y * height, accuracy: 1e-4)
    }

    /// `고립점은 굵기의 2배를 넘겨 반지름만큼 들인다 - 경계 점의 중심이 반지름 자리에 온다`
    func testIsolatedDotIsInsetByTheRadiusWhenGivenTwiceTheStroke() throws {
        // CurvePathBuilder가 점에 넘기는 값은 2·stroke다. 굵기 3 → 천장 점의 중심 y_px = 3, 바닥은 height − 3.
        let stroke: Float = 3
        let top = try XCTUnwrap(insetY([CurvePoint(x: 0.5, y: 0)], strokeWidth: 2 * stroke, height: height).first)
        let bottom = try XCTUnwrap(insetY([CurvePoint(x: 0.5, y: 1)], strokeWidth: 2 * stroke, height: height).first)

        XCTAssertEqual(stroke, top.y * height, accuracy: 1e-4)
        XCTAssertEqual(height - stroke, bottom.y * height, accuracy: 1e-4)
    }

    /// `인셋은 x를 건드리지 않는다`
    func testInsetLeavesXUntouched() {
        let original = points(6)
        let inset = insetY(original, strokeWidth: 2, height: height)

        XCTAssertEqual(original.count, inset.count)
        XCTAssertEqual(original.map(\.x), inset.map(\.x))
    }

    /// `인셋한 점을 그대로 명령으로 옮기면 천장 선의 중심이 캔버스 안에 놓인다`
    func testInsetPointsPutTheCeilingLineInsideTheCanvas() throws {
        // 실제 호출 순서(insetY → smoothPathCommands)를 그대로 따라간다.
        let stroke: Float = 3
        let ceiling = [CurvePoint(x: 0, y: 0), CurvePoint(x: 1, y: 0)]
        let commands = smoothPathCommands(insetY(ceiling, strokeWidth: stroke, height: height), width: width, height: height)

        guard case let .moveTo(firstX, firstY) = try XCTUnwrap(commands.first),
              case let .lineTo(lastX, lastY) = try XCTUnwrap(commands.last) else {
            return XCTFail("moveTo로 시작해 lineTo로 끝나야 한다")
        }
        XCTAssertEqual(0, firstX)
        XCTAssertEqual(stroke / 2, firstY, accuracy: 1e-4)
        XCTAssertEqual(width, lastX)
        XCTAssertEqual(stroke / 2, lastY, accuracy: 1e-4)
    }
}
