import AccenturyCore
import SwiftUI
import XCTest
@testable import Accentury

/// 명령 목록 → `Path` 재생이 1:1인가 (KAN-108 §7b).
///
/// 기하가 맞는지는 Core의 `CurvePathTests`가 이미 묻는다. 여기가 묻는 것은 그 다음 한 칸,
/// **옮겨 담는 손이 미끄러지지 않는가**다 — quadTo의 제어점과 끝점을 바꿔 넣거나, 명령
/// 순서를 흐트러뜨리거나, 채움 면을 엉뚱한 x에서 닫는 종류의 실수다. 화면에서는 "곡선이
/// 좀 이상하다"로만 드러나 눈으로는 원인을 짚을 수 없다.
///
/// 되읽기는 `Path.forEach`가 해 준다 — Compose `Path`에는 없어서 안드로이드가 포기했던
/// 통로이고, 그래서 이 테스트는 안드로이드에 대응물이 없는 iOS 쪽 추가다.
@MainActor
final class CurvePathBuilderTests: XCTestCase {

    /// 세 명령이 순서 그대로, 좌표 그대로 재생된다.
    func testCommandsReplayInOrder() {
        let path = curvePath([
            .moveTo(x: 1, y: 2),
            .lineTo(x: 3, y: 4),
            .quadTo(cx: 5, cy: 6, x: 7, y: 8),
        ])

        XCTAssertEqual(
            [
                .move(to: CGPoint(x: 1, y: 2)),
                .line(to: CGPoint(x: 3, y: 4)),
                .quad(to: CGPoint(x: 7, y: 8), control: CGPoint(x: 5, y: 6)),
            ],
            elements(of: path)
        )
    }

    /// 빈 목록은 빈 `Path`다 — 점이 2개 미만인 선분이 이 경로로 온다.
    func testEmptyCommandsMakeAnEmptyPath() {
        XCTAssertTrue(curvePath([]).isEmpty)
        XCTAssertTrue(elements(of: curvePath([])).isEmpty)
    }

    /// `closeAtY`는 **마지막 점의 x에서 내려가 첫 점의 x까지** 간 뒤 닫는다. 두 x를 뒤바꾸면
    /// 채운 면이 곡선 아래가 아니라 대각선으로 접힌 도형이 된다.
    func testCloseAtYDropsFromLastXBackToFirstX() {
        let path = curvePath(
            [
                .moveTo(x: 10, y: 5),
                .lineTo(x: 20, y: 7),
                .quadTo(cx: 25, cy: 9, x: 30, y: 11),
            ],
            closeAtY: 100
        )

        XCTAssertEqual(
            [
                .move(to: CGPoint(x: 10, y: 5)),
                .line(to: CGPoint(x: 20, y: 7)),
                .quad(to: CGPoint(x: 30, y: 11), control: CGPoint(x: 25, y: 9)),
                .line(to: CGPoint(x: 30, y: 100)),
                .line(to: CGPoint(x: 10, y: 100)),
                .close,
            ],
            elements(of: path)
        )
    }

    /// 명령이 하나뿐이면 첫 x와 끝 x가 같은 점이다 — 닫아도 면적 0이라 망점이 안 찍힌다.
    func testCloseAtYOnASingleCommandKeepsTheSameX() {
        let path = curvePath([.moveTo(x: 8, y: 1)], closeAtY: 50)
        XCTAssertEqual(
            [
                .move(to: CGPoint(x: 8, y: 1)),
                .line(to: CGPoint(x: 8, y: 50)),
                .line(to: CGPoint(x: 8, y: 50)),
                .close,
            ],
            elements(of: path)
        )
    }

    /// 빈 목록에는 닫을 것이 없다 — `closeAtY`를 줘도 (0,0)에서 시작하는 유령 도형을
    /// 만들지 않는다.
    func testCloseAtYOnEmptyCommandsStaysEmpty() {
        XCTAssertTrue(curvePath([], closeAtY: 40).isEmpty)
    }

    /// 곡선 좌표 → 명령 → `Path`가 통째로 이어진다. 실제 호출 순서와 같은 경로다.
    func testSmoothedSegmentBecomesAPath() {
        let points = [
            CurvePoint(x: 0, y: 1),
            CurvePoint(x: 0.5, y: 0),
            CurvePoint(x: 1, y: 1),
        ]
        let commands = smoothPathCommands(points, width: 100, height: 40)
        let elements = elements(of: curvePath(commands))

        XCTAssertEqual(commands.count, elements.count)
        XCTAssertEqual(.move(to: CGPoint(x: 0, y: 40)), elements.first)
        // 마지막 점(1, 1) × (100, 40)에서 끝난다 — 곡선이 선분의 끝까지 간다.
        XCTAssertEqual(.line(to: CGPoint(x: 100, y: 40)), elements.last)
    }

    // MARK: - 형상 캐시

    /// 같은 입력이면 다시 만들지 않는다. 32ms마다 도는 경로에서 이 적중이 곧 없어진 할당이다.
    func testShapeCacheReusesPathsForUnchangedInput() {
        let cache = CurveShapeCache()
        let segments = [[CurvePoint(x: 0, y: 0.2), CurvePoint(x: 0.5, y: 0.8), CurvePoint(x: 1, y: 0.4)]]
        let size = CGSize(width: 200, height: 100)

        let first = cache.shapes(for: segments, size: size, filled: true, dotRadius: 3)
        _ = cache.shapes(for: segments, size: size, filled: true, dotRadius: 3)
        _ = cache.shapes(for: segments, size: size, filled: true, dotRadius: 3)

        XCTAssertEqual(1, first.outlines.count)
        XCTAssertNotNil(first.fill)
        XCTAssertEqual(1, cache.rebuildCount)
    }

    /// 레인 크기가 바뀌면 다시 만든다 — 크기를 키에서 빼면 회전 뒤에도 옛 픽셀 좌표가 남는다.
    func testShapeCacheRebuildsWhenSizeChanges() {
        let cache = CurveShapeCache()
        let segments = [[CurvePoint(x: 0, y: 0), CurvePoint(x: 1, y: 1)]]

        let first = cache.shapes(for: segments, size: CGSize(width: 100, height: 50), filled: false, dotRadius: 2)
        let second = cache.shapes(for: segments, size: CGSize(width: 300, height: 50), filled: false, dotRadius: 2)

        XCTAssertEqual(CGPoint(x: 100, y: 50), elementEnd(first.outlines[0]))
        XCTAssertEqual(CGPoint(x: 300, y: 50), elementEnd(second.outlines[0]))
        XCTAssertEqual(2, cache.rebuildCount)
    }

    /// 채움 여부가 바뀌어도 다시 만든다 — 키에서 빼면 한 캐시를 두 레인이 나눠 쓸 때
    /// 가이드에 망점 면이 딸려 간다.
    func testShapeCacheRebuildsWhenFillFlagChanges() {
        let cache = CurveShapeCache()
        let segments = [[CurvePoint(x: 0, y: 0), CurvePoint(x: 1, y: 1)]]
        let size = CGSize(width: 100, height: 100)

        XCTAssertNil(cache.shapes(for: segments, size: size, filled: false, dotRadius: 3).fill)
        XCTAssertNotNil(cache.shapes(for: segments, size: size, filled: true, dotRadius: 3).fill)
        XCTAssertEqual(2, cache.rebuildCount)
    }

    /// 고립점 반지름도 키다. 빼 두면 한 캐시를 두 레인이 나눠 쓸 때 점 크기가 샌다 —
    /// 가이드는 2, 사용자는 3이다.
    func testShapeCacheRebuildsWhenDotRadiusChanges() {
        let cache = CurveShapeCache()
        let segments = [[CurvePoint(x: 0.5, y: 0.5)]]
        let size = CGSize(width: 100, height: 100)

        let thin = cache.shapes(for: segments, size: size, filled: false, dotRadius: 2)
        let thick = cache.shapes(for: segments, size: size, filled: false, dotRadius: 3)

        XCTAssertEqual(2, cache.rebuildCount)
        XCTAssertEqual(CGSize(width: 4, height: 4), thin.dots?.boundingRect.size)
        XCTAssertEqual(CGSize(width: 6, height: 6), thick.dots?.boundingRect.size)
    }

    /// 좌표가 한 점이라도 다르면 다시 만든다. 곡선이 자라는 매 청크가 이 경로다.
    func testShapeCacheRebuildsWhenPointsChange() {
        let cache = CurveShapeCache()
        let size = CGSize(width: 100, height: 100)
        let grown = [[CurvePoint(x: 0, y: 0), CurvePoint(x: 0.5, y: 0.5), CurvePoint(x: 1, y: 0.2)]]

        let first = cache.shapes(for: [[CurvePoint(x: 0, y: 0), CurvePoint(x: 0.5, y: 0.5)]], size: size, filled: false, dotRadius: 3)
        let second = cache.shapes(for: grown, size: size, filled: false, dotRadius: 3)

        XCTAssertEqual(2, cache.rebuildCount)
        // 점이 하나 붙으면 명령도 하나 는다 (Core `smoothPathCommands`의 인과성).
        XCTAssertEqual(elements(of: first.outlines[0]).count + 1, elements(of: second.outlines[0]).count)
    }

    /// 가이드 레인은 채우지 않는다 — 망점은 내 억양 곡선만 갖는 표식이다.
    func testShapeCacheSkipsFillForGuideLane() {
        let cache = CurveShapeCache()
        let shapes = cache.shapes(
            for: [[CurvePoint(x: 0, y: 0), CurvePoint(x: 1, y: 1)]],
            size: CGSize(width: 100, height: 100),
            filled: false,
            dotRadius: 2
        )
        XCTAssertNil(shapes.fill)
    }

    /// 점이 하나뿐인 선분은 선이 아니라 점으로 남는다 — 좌표는 캔버스 크기를 곱한 값이다.
    /// 반환은 좌표가 아니라 **원을 미리 그려 둔 `Path`**다: 그리기는 32ms마다 도는 경로라
    /// 거기서 점마다 `Path(ellipseIn:)`을 만들면 프레임마다 점 수만큼 할당이 생긴다.
    func testSinglePointSegmentBecomesADot() {
        let cache = CurveShapeCache()
        let shapes = cache.shapes(
            for: [[CurvePoint(x: 0.25, y: 0.5)]],
            size: CGSize(width: 200, height: 80),
            filled: true,
            dotRadius: 3
        )
        XCTAssertTrue(shapes.outlines.isEmpty)
        // 중심 (50, 40)에 반지름 3짜리 원 하나.
        XCTAssertEqual(CGRect(x: 47, y: 37, width: 6, height: 6), shapes.dots?.boundingRect)
    }

    /// 그 점 `Path`도 캐시된다. 세그먼트가 그대로면 다시 만들지 않는다 (Codex 지적, AC5).
    func testDotsPathIsCachedForUnchangedSegments() {
        let cache = CurveShapeCache()
        let segments = [[CurvePoint(x: 0.25, y: 0.5)], [CurvePoint(x: 0.75, y: 0.25)]]
        let size = CGSize(width: 200, height: 80)

        let first = cache.shapes(for: segments, size: size, filled: true, dotRadius: 3)
        let second = cache.shapes(for: segments, size: size, filled: true, dotRadius: 3)

        XCTAssertEqual(1, cache.rebuildCount)
        XCTAssertTrue(first.outlines.isEmpty)
        // 점 둘이 한 `Path`에 합쳐진다 — 그리기는 `fill` 한 번이다.
        XCTAssertEqual(2, elements(of: first.dots ?? Path()).filter { $0 == .close }.count)
        XCTAssertEqual(first.dots?.boundingRect, second.dots?.boundingRect)
        // 점만 있는 선분은 채울 면이 없다 — 닫을 곡선이 없어서다.
        XCTAssertTrue(first.fill?.isEmpty ?? true)
    }

    /// 반지름이 0이면 찍을 점이 없다 — 넓이 0짜리 원을 그리는 호출을 남기지 않는다.
    func testZeroDotRadiusLeavesNoDots() {
        let cache = CurveShapeCache()
        let shapes = cache.shapes(
            for: [[CurvePoint(x: 0.25, y: 0.5)]],
            size: CGSize(width: 200, height: 80),
            filled: true,
            dotRadius: 0
        )
        XCTAssertNil(shapes.dots)
    }

    /// 유성 선분 여럿의 채움 면은 **하나로** 합쳐진다 — 프레임당 clip 한 번, 점 찍기 한 번.
    func testFillMergesEverySegmentIntoOnePath() {
        let cache = CurveShapeCache()
        let shapes = cache.shapes(
            for: [
                [CurvePoint(x: 0, y: 0.5), CurvePoint(x: 0.2, y: 0.2)],
                [CurvePoint(x: 0.6, y: 0.3), CurvePoint(x: 1, y: 0.7)],
            ],
            size: CGSize(width: 100, height: 100),
            filled: true,
            dotRadius: 3
        )
        XCTAssertEqual(2, shapes.outlines.count)
        // 면은 하나이고 그 안에 하위 경로 둘이 담긴다 (`close` 둘).
        let closes = elements(of: shapes.fill ?? Path()).filter { $0 == .close }
        XCTAssertEqual(2, closes.count)
    }

    // MARK: - 헬퍼

    /// `Path`를 되읽어 비교 가능한 값으로 만든다.
    private func elements(of path: Path) -> [PathElement] {
        var collected: [PathElement] = []
        path.forEach { element in
            switch element {
            case let .move(to): collected.append(.move(to: to))
            case let .line(to): collected.append(.line(to: to))
            case let .quadCurve(to, control): collected.append(.quad(to: to, control: control))
            case let .curve(to, control1, control2):
                collected.append(.cubic(to: to, control1: control1, control2: control2))
            case .closeSubpath: collected.append(.close)
            }
        }
        return collected
    }

    /// 마지막 명령의 끝점.
    private func elementEnd(_ path: Path) -> CGPoint? {
        switch elements(of: path).last {
        case let .move(to): return to
        case let .line(to): return to
        case let .quad(to, _): return to
        case let .cubic(to, _, _): return to
        default: return nil
        }
    }

    /// `Path.Element`는 `Equatable`이 아니라 비교할 수 없다. 같은 모양의 값으로 옮겨 담는다.
    private enum PathElement: Equatable {
        case move(to: CGPoint)
        case line(to: CGPoint)
        case quad(to: CGPoint, control: CGPoint)
        case cubic(to: CGPoint, control1: CGPoint, control2: CGPoint)
        case close
    }
}
