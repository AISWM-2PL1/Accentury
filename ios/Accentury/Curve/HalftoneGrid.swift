import SwiftUI

/// 망점 한 칸의 크기와 점 반지름. 웹 `<pattern>`의 5×5·r=1, 안드로이드의 `5.dp`·`1.dp`와
/// 같은 값이다 (dp와 pt는 둘 다 논리 픽셀이라 숫자가 그대로 옮겨진다).
let halftoneStep: CGFloat = 5
let halftoneDotRadius: CGFloat = 1

/// 망점의 진하기. 1이면 곡선 아래가 잉크 면이 되어 곡선 자체가 안 보인다.
let halftoneAlpha: Double = 0.5

/// 격자 점 목록. 안드로이드 `HalftoneGrid.pointsFor`의 순수 함수 부분이다.
///
/// 점을 **레인 좌표 격자**에 찍는 것이 요점이다(웹의 `userSpaceOnUse`와 같다). 곡선을 기준으로
/// 찍으면 곡선이 자랄 때 이미 찍힌 점이 함께 움직여 무늬가 살아 있는 것처럼 보인다.
///
/// `step`이 0 이하면 while이 안 끝난다. 상수라 실제로는 안 걸리지만 무한 루프를 그리기 경로에
/// 남겨 둘 이유가 없다.
func halftoneGridPoints(size: CGSize, step: CGFloat) -> [CGPoint] {
    guard step > 0 else { return [] }
    var points: [CGPoint] = []
    var y = step / 2
    while y < size.height {
        var x = step / 2
        while x < size.width {
            points.append(CGPoint(x: x, y: y))
            x += step
        }
        y += step
    }
    return points
}

/// 망점 격자 캐시 (KAN-161 Codex 지적의 이식 — "망점을 프레임당 한 번만 찍고 격자를 캐시").
///
/// 격자는 곡선이 아니라 레인 좌표에 붙어 있어서 곡선이 자라도 점 자리는 안 바뀌는데, 녹음
/// 중에는 32ms마다 다시 그린다. 레인 크기가 바뀌는 때는 회전이나 동적 글꼴 변경뿐이라,
/// 크기와 칸 크기가 그대로면 지난 목록을 그대로 돌려준다.
///
/// 안드로이드보다 하나 더 캐시하는 것이 ``dotsPath(size:step:radius:)``다. Compose에는 점
/// 목록을 통째로 받는 `drawPoints`가 있어 draw call이 1이지만, SwiftUI `GraphicsContext`에는
/// 그 자리가 없다 — 점마다 `fill`을 부르면 1,300여 번의 draw call이 되므로, 점 전부를 담은
/// `Path` 하나를 미리 만들어 두고 프레임마다 그것만 채운다. 결과는 같고 호출은 1회다.
///
/// `@MainActor`인 근거는 ``CurveShapeCache``와 같다 — 락 없는 가변 상태의 안전 근거가
/// "렌더 클로저는 메인에서만 돈다"는 사실뿐이므로, 그 불변식을 주석이 아니라 타입에 박아
/// 컴파일러가 검사하게 한다. ``init()``만 `nonisolated`인 이유도 같다(`@State` 기본값이
/// 뷰 구조체의 격리 없는 초기화에서 만들어진다).
@MainActor
final class HalftoneGrid {

    nonisolated init() {}

    private var size: CGSize = .zero
    private var step: CGFloat = 0
    private var radius: CGFloat = 0
    private var points: [CGPoint] = []
    private var path = Path()

    /// 격자를 실제로 다시 만든 횟수. **테스트용 창구다** — 캐시가 죽어 있어도 화면은 똑같아서,
    /// 이 숫자 말고는 적중 여부를 밖에서 확인할 방법이 없다.
    private(set) var rebuildCount = 0

    /// 이 크기·이 칸 크기의 격자 점. 지난 호출과 같으면 같은 저장소를 그대로 돌려준다.
    func pointsFor(size: CGSize, step: CGFloat) -> [CGPoint] {
        refresh(size: size, step: step, radius: radius)
        return points
    }

    /// 격자 점 전부를 원으로 담은 `Path`. 채우면 곧 망점 한 판이다.
    func dotsPath(size: CGSize, step: CGFloat, radius: CGFloat) -> Path {
        refresh(size: size, step: step, radius: radius)
        return path
    }

    private func refresh(size: CGSize, step: CGFloat, radius: CGFloat) {
        if size == self.size, step == self.step, radius == self.radius { return }
        rebuildCount += 1
        let next = halftoneGridPoints(size: size, step: step)
        var dots = Path()
        if radius > 0 {
            for point in next {
                dots.addEllipse(
                    in: CGRect(
                        x: point.x - radius,
                        y: point.y - radius,
                        width: radius * 2,
                        height: radius * 2
                    )
                )
            }
        }
        self.size = size
        self.step = step
        self.radius = radius
        points = next
        path = dots
    }
}
