import SwiftUI
import XCTest
@testable import Accentury

/// 망점 격자 (KAN-108 §7b, 안드로이드 `HalftoneGrid`의 이식).
///
/// 격자 자체는 눈으로 보면 맞았는지 알 수 있지만 **캐시가 도는지는 안 보인다** — 캐시가
/// 통째로 죽어 있어도 화면은 똑같고, 32ms마다 점 1,300여 개짜리 `Path`를 다시 만드는 비용만
/// 조용히 는다. 그것이 이 파일이 있는 이유다 (KAN-161에서 안드로이드가 같은 지적을 받았다).
@MainActor
final class HalftoneGridTests: XCTestCase {

    /// 첫 점은 칸의 **한가운데**다. 0에서 시작하면 레인 왼쪽·위 모서리에 반쪽 점이 붙는다.
    func testFirstDotSitsAtTheCentreOfTheFirstCell() {
        let points = halftoneGridPoints(size: CGSize(width: 20, height: 20), step: 5)
        XCTAssertEqual(CGPoint(x: 2.5, y: 2.5), points.first)
    }

    /// 칸 크기만큼 띄워 격자를 채운다. 20×10에 5칸이면 가로 4 × 세로 2다.
    func testGridFillsTheLaneAtStepSpacing() {
        let points = halftoneGridPoints(size: CGSize(width: 20, height: 10), step: 5)
        XCTAssertEqual(8, points.count)
        XCTAssertEqual(
            [
                CGPoint(x: 2.5, y: 2.5), CGPoint(x: 7.5, y: 2.5),
                CGPoint(x: 12.5, y: 2.5), CGPoint(x: 17.5, y: 2.5),
                CGPoint(x: 2.5, y: 7.5), CGPoint(x: 7.5, y: 7.5),
                CGPoint(x: 12.5, y: 7.5), CGPoint(x: 17.5, y: 7.5),
            ],
            points
        )
    }

    /// 점이 레인 밖으로 나가지 않는다 — 경계 바로 안쪽까지만이다.
    func testGridStaysInsideTheLane() {
        let size = CGSize(width: 33, height: 17)
        for point in halftoneGridPoints(size: size, step: 5) {
            XCTAssertLessThan(point.x, size.width)
            XCTAssertLessThan(point.y, size.height)
        }
    }

    /// 칸이 0 이하면 빈 격자다. 상수라 실제로는 안 걸리지만, 무한 루프를 그리기 경로에
    /// 남겨 둘 이유가 없다.
    func testNonPositiveStepMakesNoDots() {
        XCTAssertTrue(halftoneGridPoints(size: CGSize(width: 50, height: 50), step: 0).isEmpty)
        XCTAssertTrue(halftoneGridPoints(size: CGSize(width: 50, height: 50), step: -5).isEmpty)
    }

    /// 레인이 칸보다 작으면 점이 하나도 안 들어간다.
    func testLaneSmallerThanOneCellMakesNoDots() {
        XCTAssertTrue(halftoneGridPoints(size: CGSize(width: 2, height: 2), step: 5).isEmpty)
        XCTAssertTrue(halftoneGridPoints(size: .zero, step: 5).isEmpty)
    }

    /// 같은 크기·같은 칸이면 **같은 저장소**를 그대로 돌려준다. 값만 같은 새 배열이면
    /// 캐시가 아니라 매번 다시 만든 것이다 — 배열이 값 타입이라 그 차이가 눈에 안 보인다.
    func testCacheReturnsTheSameStorageOnAHit() {
        let grid = HalftoneGrid()
        let size = CGSize(width: 320, height: 100)

        let first = grid.pointsFor(size: size, step: halftoneStep)
        let second = grid.pointsFor(size: size, step: halftoneStep)

        XCTAssertFalse(first.isEmpty)
        XCTAssertEqual(first, second)
        XCTAssertEqual(1, grid.rebuildCount)
        XCTAssertEqual(
            first.withUnsafeBufferPointer { $0.baseAddress },
            second.withUnsafeBufferPointer { $0.baseAddress }
        )
    }

    /// 레인 크기가 바뀌면 다시 만든다 — 회전이나 동적 글꼴 변경 때다.
    func testCacheRebuildsWhenSizeChanges() {
        let grid = HalftoneGrid()
        let narrow = grid.pointsFor(size: CGSize(width: 20, height: 10), step: 5)
        let wide = grid.pointsFor(size: CGSize(width: 40, height: 10), step: 5)

        XCTAssertEqual(8, narrow.count)
        XCTAssertEqual(16, wide.count)
    }

    /// 점 `Path`도 같이 캐시된다. SwiftUI에는 점 목록을 통째로 받는 그리기가 없어서
    /// 점 전부를 담은 `Path` 하나를 미리 만들어 두고 프레임마다 그것만 채운다.
    func testDotsPathIsCachedAndCoversEveryGridPoint() {
        let grid = HalftoneGrid()
        let size = CGSize(width: 20, height: 10)

        let first = grid.dotsPath(size: size, step: 5, radius: halftoneDotRadius)
        _ = grid.dotsPath(size: size, step: 5, radius: halftoneDotRadius)

        XCTAssertEqual(1, grid.rebuildCount)
        // 점 8개를 감싸는 경계 상자. 반지름 1이라 첫 점(2.5, 2.5)의 왼쪽 위가 (1.5, 1.5)다.
        XCTAssertEqual(CGRect(x: 1.5, y: 1.5, width: 17, height: 7), first.boundingRect)
    }

    /// 반지름이 0이면 그릴 원이 없다 — 알파 0짜리 면을 1,300번 그리는 일을 막는다.
    func testZeroRadiusMakesAnEmptyDotsPath() {
        let grid = HalftoneGrid()
        XCTAssertTrue(grid.dotsPath(size: CGSize(width: 20, height: 10), step: 5, radius: 0).isEmpty)
    }

    /// 상수는 웹 `<pattern>`의 5×5·r=1, 안드로이드의 `5.dp`·`1.dp`와 같은 값이어야 한다 —
    /// 세 런타임의 망점이 갈리면 같은 화면이 런타임마다 다른 질감이 된다.
    func testConstantsMatchTheOtherRuntimes() {
        XCTAssertEqual(5, halftoneStep)
        XCTAssertEqual(1, halftoneDotRadius)
        XCTAssertEqual(0.5, halftoneAlpha)
    }
}
