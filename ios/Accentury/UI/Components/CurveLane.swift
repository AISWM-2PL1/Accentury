import AccenturyCore
import SwiftUI

/// 레인의 성격 (KAN-161 2단계). 선 굵기·점선·망점이 함께 움직이므로 하나로 묶는다 —
/// 셋을 따로 받으면 호출자마다 조합이 달라져 "가이드처럼 생긴 내 곡선"이 만들어진다.
enum CurveLaneVariant {
    case guide
    case user
}

/// 곡선 레인을 담는 상자 (KAN-161 2단계). 안드로이드 `CurveLaneGroup`의 이식본이다.
/// 레인 하나든 둘이든 테두리와 모서리는 이 상자가 갖고 레인 자신은 갖지 않는다 — 레인마다
/// 테두리를 두르면 상자 안에 상자가 겹쳐 선이 두 겹으로 보인다. 웹 `.curve-card`와 같은 규격.
struct CurveLaneGroup<Content: View>: View {

    @ViewBuilder let content: () -> Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Papercut.radiusMD, style: .continuous)
        return VStack(spacing: 0) { content() }
            .frame(maxWidth: .infinity)
            .background(shape.fill(Papercut.cream))
            .overlay(shape.stroke(Papercut.ink, lineWidth: Papercut.borderRegular))
            .clipShape(shape)
    }
}

/// 곡선 캔버스의 레인 하나 (`ux-ui.md` §D — 위/아래 2단, 같은 가로폭).
/// 안드로이드 `ui/components/CurveLane.kt`의 이식본이다.
///
/// 좌표는 ``AccenturyCore/guideCurveDisplayPoints(_:)``와
/// ``AccenturyCore/userCurveDisplayPoints(_:windowMs:centerHz:)``가 만든 0..1 비율의 선분
/// 목록이고 여기서는 캔버스 크기만 곱한다 — 곡선 처리 규칙은 전부 저쪽(시뮬레이터 없이
/// `swift test`로 덮인다)에, 여기는 픽셀 변환만 남긴다.
/// 점이 없으면 빈 레인이다: 전부 무성이거나 구버전 웹이 곡선을 안 실어 보낸 경우고,
/// 곡선은 없어도 녹음은 성립하므로 오류 표시 없이 조용히 비워 둔다.
///
/// ## 두 곡선을 무엇이 가르는가
///
/// 팔레트가 잉크 한 색이라 색으로는 아무것도 못 가른다 (정본 §7). 대신 셋이 함께 가른다 —
/// 가이드는 얇은 점선, 내 억양은 굵은 실선에 곡선 아래가 망점으로 차 있다. 망점(halftone)은
/// 종이 오리기 인쇄물의 회색 표현이고, 이 앱에서 **화면당 한 곳**만 쓰기로 한 무늬다:
/// 곡선 레인이 그 한 곳이라 다른 컴포넌트에는 망점이 없다.
///
/// ## `Canvas`를 고른 이유
///
/// 후보는 셋이었다. `Shape` 여럿(선분마다 하나)은 선분 수가 녹음 중에 변해 뷰 트리가
/// 프레임마다 재구성되고, 망점 clip을 얹을 자리가 없다. `CALayer`(`CAShapeLayer` +
/// `UIViewRepresentable`)는 60fps를 확실히 잡아 주지만 레이어 트리를 손으로 동기화해야 하고
/// — 선분이 늘고 줄 때마다 레이어를 붙였다 떼는 코드가 통째로 생긴다 — SwiftUI 색·크기와
/// 두 벌로 갈린다. `Canvas`는 그리기 명령이 곧 코드라 안드로이드 `Canvas` 블록과 1:1로
/// 대응하고, 한 번의 `body` 평가가 한 번의 그리기라 상태 동기화라는 것이 아예 없다.
///
/// 대신 `Canvas`는 뷰가 다시 평가될 때마다 렌더 클로저를 다시 부르므로, 32ms마다 도는 경로에
/// 할당을 남기지 않는 것이 여기서 해야 할 일이 된다. 둘을 캐시한다 —
/// 좌표→`Path` 변환(``CurveShapeCache``)과 망점 격자(``HalftoneGrid``)다. 앞의 것은 좌표가
/// 그대로면(가이드 레인은 문항 내내 그렇다) 지난 `Path`를 그대로 쓰고, 뒤의 것은 레인 크기가
/// 그대로면 — 회전이나 동적 글꼴 변경뿐이다 — 점 1,300여 개짜리 `Path`를 다시 만들지 않는다.
/// 비트맵으로 굽는 방법(`ImageRenderer`)은 쓰지 않는다: 곡선이 매 프레임 바뀌어서 캐시가
/// 한 번도 적중하지 않는다.
///
/// - Parameter topDivider: 위 레인과 나를 가르는 줄을 그릴지. 웹이 `.curve-lane + .curve-lane`로
///   자동으로 하는 일을 여기서는 첫 레인이 아닌 쪽이 스스로 말한다.
struct CurveLaneView: View {

    let label: String
    let variant: CurveLaneVariant

    /// 그릴 선분들. 선분마다 따로 그린다 — 긴 무성 구간에서 곡선이 끊기므로(KAN-105) 하나로
    /// 이으면 쉼 구간을 가로지르는 가짜 사선이 생긴다. 가이드는 선분 하나짜리 목록이다.
    var segments: [[CurvePoint]] = []

    var topDivider: Bool = false

    /// 이 곡선에 담긴 F0 프레임 수. **계측 전용이다** (``CurveLatencyProbe``) — 그리는 데는
    /// 쓰지 않는다. 릴리스에서는 읽는 쪽이 통째로 없어지지만 인자 자체는 남겨 둔다: 구성마다
    /// 호출부 시그니처가 갈리면 릴리스 빌드만 깨지는 종류의 실수가 생긴다.
    var renderedFrameCount: Int = 0

    /// 곡선이 자라도 다시 만들지 않는 것들. 뷰 값이 다시 만들어져도 같은 인스턴스로 남는다.
    @State private var shapeCache = CurveShapeCache()
    @State private var halftone = HalftoneGrid()

    var body: some View {
        ZStack(alignment: .topLeading) {
            /*
             * 곡선. 위아래 여백은 안드로이드와 같은 값이다 — 위 16은 좌상단 라벨이 곡선 위에
             * 겹치지 않게 비워 두는 자리이고, 아래 4는 굵은 선의 둥근 끝이 레인 경계에 잘리지
             * 않게 하는 자리다.
             */
            Canvas(opaque: false, rendersAsynchronously: false) { context, size in
                draw(&context, size: size)
            }
            .padding(.top, Papercut.space4)
            .padding(.bottom, Papercut.space1)

            // 레인 사이 구분선. 상자 테두리보다 얇고 흐리다 — 나누는 선이 두르는 선만큼
            // 진하면 레인 둘이 따로 놓인 상자로 보인다.
            if topDivider {
                Papercut.muted
                    .frame(height: Papercut.borderHairline)
                    .frame(maxWidth: .infinity, alignment: .top)
            }

            // 라벨은 레인 좌상단에 얹는다(시안). 곡선 상단 여백 10% 안쪽이라 겹치지 않는다.
            Text(label)
                .papercutType(.caption)
                .foregroundColor(Papercut.muted)
                .padding(.leading, Papercut.space3)
                .padding(.top, Papercut.space1)
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .frame(height: Papercut.curveLaneHeight)
        // 곡선이 시시각각 바뀌는 그림이라 값을 읽어 주지 않는다 — 뜻만 남긴다.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label) 곡선")
    }

    /// 한 프레임을 그린다. 안드로이드 `Canvas { ... }` 블록과 같은 순서다.
    ///
    /// **이 함수는 아무것도 새로 만들지 않는다.** 32ms마다 도는 경로라, 여기 남은 것은
    /// 캐시에서 꺼낸 `Path`를 그리는 호출 넷뿐이다 — 선 모양(``StrokeStyle``)은 파일 수준
    /// 상수, 고립점 원들은 ``CurveShapes/dots``에 합쳐 둔 `Path` 하나, 망점 격자는
    /// ``HalftoneGrid``가 레인 크기별로 들고 있는 `Path` 하나다.
    private func draw(_ context: inout GraphicsContext, size: CGSize) {
        let isUser = variant == .user
        let shapes = shapeCache.shapes(
            for: segments,
            size: size,
            filled: isUser,
            dotRadius: isUser ? userCurveStroke : guideCurveStroke
        )

        // 채움을 먼저 그리고 선을 나중에 그린다 — 순서가 바뀌면 망점이 곡선 위를 덮어
        // 선이 점무늬에 잠긴다.
        if let fill = shapes.fill, !fill.isEmpty {
            // `GraphicsContext`는 값이라 복사본에 clip을 걸면 원본은 그대로다.
            var clipped = context
            clipped.clip(to: fill)
            clipped.fill(
                halftone.dotsPath(size: size, step: halftoneStep, radius: halftoneDotRadius),
                with: .color(Papercut.ink.opacity(halftoneAlpha))
            )
        }

        // 점선은 가이드에만 쓴다 — 색이 아니라 선 모양으로 두 곡선을 가르므로 색각 이상에서도
        // 어느 쪽이 내 곡선인지 알 수 있다 (WCAG 1.4.1).
        let style = isUser ? userCurveStrokeStyle : guideCurveStrokeStyle
        for outline in shapes.outlines {
            context.stroke(outline, with: .color(Papercut.ink), style: style)
        }

        // 점이 하나뿐인 선분들. 안드로이드 `drawCircle(radius = stroke)`과 같은 크기다.
        if let dots = shapes.dots {
            context.fill(dots, with: .color(Papercut.ink))
        }

        #if DEBUG
        // 곡선이 실제로 화면에 나가는 유일한 지점이라, 지연 계측의 끝점이 여기다.
        // 사용자 레인만 잰다 — 가이드는 정적이라 잴 지연이 없다.
        if isUser { CurveLatencyProbe.shared.canvasRendered(frameCount: renderedFrameCount) }
        #endif
    }
}

/// 곡선 굵기. 가이드는 얇고 내 억양은 굵다 — 웹의 2px/3px, 안드로이드의 `2.dp`/`3.dp`와 같다.
/// 고립점 원의 반지름이기도 하다(안드로이드 `drawCircle(radius = stroke)`).
private let guideCurveStroke: CGFloat = 2
private let userCurveStroke: CGFloat = 3

/// 점선 패턴. 가이드에만 쓴다. 웹의 `stroke-dasharray="6 5"`와 같다.
private let guideDashOn: CGFloat = 6
private let guideDashOff: CGFloat = 5

/*
 * 선 모양 둘. **파일 수준 상수인 것이 요점이다** — `StrokeStyle`은 dash 배열을 들고 있어서
 * 렌더 클로저 안에서 만들면 프레임마다 배열 할당이 하나씩 생긴다. 값이 레인 성격에만 달려
 * 있고 크기·좌표와 무관하므로 앱이 사는 동안 둘이면 족하다.
 */
private let guideCurveStrokeStyle = StrokeStyle(
    lineWidth: guideCurveStroke,
    lineCap: .round,
    lineJoin: .round,
    dash: [guideDashOn, guideDashOff]
)
private let userCurveStrokeStyle = StrokeStyle(
    lineWidth: userCurveStroke,
    lineCap: .round,
    lineJoin: .round
)
