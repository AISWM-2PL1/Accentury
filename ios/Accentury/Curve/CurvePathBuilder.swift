import AccenturyCore
import SwiftUI

/// 곡선 명령(``AccenturyCore/PathCommand``)을 SwiftUI `Path`로 재생한다.
/// 안드로이드 `ui/components/CurveLane.kt`의 `List<PathCommand>.toPath()` 이식본이다.
///
/// 기하 계산은 ``AccenturyCore/smoothPathCommands(_:width:height:)``가 하고 여기는 옮겨 담기만
/// 한다 — 명령 목록이 값이라 `swift test`로 못박을 수 있는 반면 `Path`는 그리기 백엔드의
/// 산물이라, 검사할 것은 전부 명령 목록 쪽에 둔다는 §7a의 판단을 그대로 잇는다.
/// 그래서 이 파일의 테스트가 묻는 것은 기하가 아니라 **재생이 1:1인가** 하나뿐이다
/// (`CurvePathBuilderTests`가 `Path.forEach`로 되읽는다 — Compose `Path`에는 없던 통로다).
///
/// 좌표를 `Float`에서 `CGFloat`로 넓히는 자리이기도 하다. Core가 `Float`인 것은 안드로이드
/// (`Float` 좌표계)를 1:1로 따른 결과이고, SwiftUI는 `CGFloat`을 받는다 — 넓히는 변환이라
/// 값이 상하지 않는다.
///
/// - Parameters:
///   - commands: 재생할 명령. 빈 목록이면 빈 `Path`다 (점이 2개 미만인 선분).
///   - closeAtY: 주면 곡선 끝에서 이 높이로 내려가고 시작점 아래까지 간 뒤 닫는다 —
///     망점을 채울 면이다. 선분이 레인 폭 전체를 쓰지 않아도(녹음이 짧으면 왼쪽만 차 있다)
///     채운 면이 곡선 밑에만 남는다.
func curvePath(_ commands: [PathCommand], closeAtY: CGFloat? = nil) -> Path {
    var path = Path()
    var firstX: CGFloat = 0
    var lastX: CGFloat = 0

    for (index, command) in commands.enumerated() {
        // 끝점의 x는 명령마다 자리가 달라(quadTo는 제어점이 앞에 온다) 분기 안에서 꺼낸다.
        let x: CGFloat
        switch command {
        case let .moveTo(px, py):
            path.move(to: CGPoint(x: CGFloat(px), y: CGFloat(py)))
            x = CGFloat(px)
        case let .lineTo(px, py):
            path.addLine(to: CGPoint(x: CGFloat(px), y: CGFloat(py)))
            x = CGFloat(px)
        case let .quadTo(cx, cy, px, py):
            path.addQuadCurve(
                to: CGPoint(x: CGFloat(px), y: CGFloat(py)),
                control: CGPoint(x: CGFloat(cx), y: CGFloat(cy))
            )
            x = CGFloat(px)
        }
        if index == 0 { firstX = x }
        lastX = x
    }

    if let closeAtY, !commands.isEmpty {
        path.addLine(to: CGPoint(x: lastX, y: closeAtY))
        path.addLine(to: CGPoint(x: firstX, y: closeAtY))
        path.closeSubpath()
    }
    return path
}

/// 한 레인이 그릴 도형 한 벌. ``CurveShapeCache``가 만들고 캔버스가 받아 그리기만 한다.
struct CurveShapes {
    /// 선분마다 하나씩. **선분을 하나로 잇지 않는다** — 긴 무성 구간에서 곡선이 끊기므로
    /// 이으면 쉼 구간을 가로지르는 가짜 사선이 생긴다 (가이드는 선분 하나짜리 목록이다).
    var outlines: [Path] = []
    /// 망점을 채울 면. 유성 선분 전부를 **하나로 합친** 면이라 프레임당 clip도 한 번,
    /// 점 찍기도 한 번이다 — 선분마다 부르면 무성 구간이 많은 녹음일수록 매 프레임 비용이
    /// 선분 수에 비례해 는다. 사용자 레인에만 있다(가이드는 nil).
    var fill: Path?
    /// 점이 하나뿐인 선분들. 선은 못 그리니 그 시각에 점 하나로 남긴다.
    ///
    /// 좌표 목록이 아니라 원들을 **미리 합쳐 둔 `Path`**다. 그리기는 32ms마다 도는 경로라
    /// 거기서 점마다 `Path(ellipseIn:)`을 만들면 프레임마다 점 수만큼 할당이 생긴다 —
    /// 모양이 세그먼트에만 달려 있으니 세그먼트가 바뀔 때 한 번 만들면 된다.
    /// 점이 없으면 nil이다(빈 `Path`를 채우는 호출도 하지 않는다).
    var dots: Path?
}

/// 좌표 목록 → 도형 한 벌 변환의 결과를 들고 있는 캐시.
///
/// 안드로이드는 매 프레임 `smoothPathCommands` → `toPath()`를 다시 돌린다. Compose는 그래도
/// 되는데, 여기서는 `Canvas` 렌더 클로저가 뷰가 다시 평가될 때마다 불려서 — 좌표가 하나도
/// 안 바뀐 가이드 레인까지 사용자 곡선이 자랄 때마다 함께 다시 그려진다 — 같은 입력에 같은
/// 답을 되풀이해 만들게 된다. 입력(좌표·크기·채움 여부)이 그대로면 지난 답을 그대로 돌려주어
/// 32ms마다 도는 경로에서 `Path` 할당을 없앤다.
///
/// 캐시 적중 판정을 좌표 **값** 비교로 하는 이유는 `[[CurvePoint]]`가 값 타입이라 참조로는
/// 가릴 수 없어서다. 비교 비용은 최대 313점짜리 O(n)이고, 어긋났을 때 하는 일(같은 점을 돌며
/// 베지어 명령을 만들고 `Path`에 넣는 것)도 같은 O(n)이라 헛수고가 아니다 — 적중하면 그
/// O(n) 두 벌이 통째로 빠진다.
///
/// 뷰가 아니라 클래스인 것은 `@State`가 값 타입을 렌더 클로저 안에서 바꾸지 못하게 하기
/// 때문이다. 참조 타입 하나를 `@State`로 들고 있으면 뷰 값이 다시 만들어져도 캐시는 같은
/// 인스턴스로 남는다.
///
/// **`@MainActor`인 것은 락 없는 가변 상태이기 때문이다.** 이 캐시가 안전한 근거는
/// "`Canvas` 렌더 클로저가 메인에서만 돈다"는 사실 하나인데, 그것을 주석으로만 적어 두면
/// 나중에 누가 백그라운드에서 곡선을 미리 계산하려 할 때 아무도 못 막는다. 격리를 타입에
/// 박아 두면 그 시도가 컴파일 오류가 된다 — 렌더 클로저는 `@MainActor`인 `body` 안에서
/// 만들어지는 비-`Sendable` 클로저라 같은 격리를 물려받고, 그래서 이 호출이 성립한다.
/// ``init()``만 `nonisolated`인데, `@State` 기본값이 뷰 구조체의 격리 없는 초기화에서
/// 만들어지기 때문이다(저장 프로퍼티가 전부 기본값 있는 값 타입이라 안전하다).
@MainActor
final class CurveShapeCache {

    nonisolated init() {}

    private var cachedSegments: [[CurvePoint]]?
    private var cachedSize: CGSize = .zero
    private var cachedFilled = false
    private var cachedDotRadius: CGFloat = 0
    private var cachedShapes = CurveShapes()

    /// 도형을 실제로 다시 만든 횟수. **테스트용 창구다** — 캐시가 통째로 죽어 있어도 화면은
    /// 똑같아서, 이 숫자 말고는 적중 여부를 밖에서 확인할 방법이 없다.
    private(set) var rebuildCount = 0

    /// 이 좌표·이 크기로 그릴 도형. 지난 호출과 입력이 같으면 만들지 않고 그대로 돌려준다.
    ///
    /// - Parameter dotRadius: 고립점 원의 반지름. 키에 넣는 이유는 이 값이 레인 성격에 따라
    ///   달라서다(가이드 2 · 사용자 3) — 빼 두면 한 캐시를 두 레인이 나눠 쓸 때 점 크기가 샌다.
    func shapes(
        for segments: [[CurvePoint]],
        size: CGSize,
        filled: Bool,
        dotRadius: CGFloat
    ) -> CurveShapes {
        if let cachedSegments,
           cachedSize == size,
           cachedFilled == filled,
           cachedDotRadius == dotRadius,
           cachedSegments == segments {
            return cachedShapes
        }

        rebuildCount += 1
        var shapes = CurveShapes()
        shapes.outlines.reserveCapacity(segments.count)
        var dots: Path?
        // 유성 선분끼리는 x 구간이 겹치지 않으므로 addPath로 이어 붙이면 그대로 합집합이 된다
        // (SwiftUI `fill`의 기본이 안드로이드와 같은 non-zero 규칙이다).
        var fill = filled ? Path() : nil

        for points in segments {
            if points.count >= 2 {
                // 명령은 한 번만 만든다 — 선과 채움이 같은 목록을 나눠 쓴다.
                let commands = smoothPathCommands(points, width: Float(size.width), height: Float(size.height))
                shapes.outlines.append(curvePath(commands))
                if fill != nil {
                    fill?.addPath(curvePath(commands, closeAtY: size.height))
                }
            } else if let point = points.first, dotRadius > 0 {
                let centre = CGPoint(x: CGFloat(point.x) * size.width, y: CGFloat(point.y) * size.height)
                if dots == nil { dots = Path() }
                dots?.addEllipse(
                    in: CGRect(
                        x: centre.x - dotRadius,
                        y: centre.y - dotRadius,
                        width: dotRadius * 2,
                        height: dotRadius * 2
                    )
                )
            }
        }
        shapes.fill = fill
        shapes.dots = dots

        cachedSegments = segments
        cachedSize = size
        cachedFilled = filled
        cachedDotRadius = dotRadius
        cachedShapes = shapes
        return shapes
    }
}
