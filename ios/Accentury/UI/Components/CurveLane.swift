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
/// 안드로이드 `ui/components/CurveLane.kt`의 **틀만** 옮긴 것이다.
///
/// ## 곡선 그리기는 아직 없다 (KAN-108 §7b)
///
/// 이 단계에서 세우는 것은 레인의 **자리**다 — 높이, 라벨, 레인 사이 구분선, 상자 안에서의
/// 위치. 곡선 자체(`guideCurveDisplayPoints`·`userCurveDisplayPoints`가 만든 0..1 좌표를
/// 픽셀로 옮기고, 사용자 곡선 아래를 망점으로 채우고, 가이드를 점선으로 긋는 것)는 §7이
/// Core에 `Curve/` 계층을 들여온 뒤에 채운다. 좌표 계산을 여기서 흉내 내면 두 벌이 생기고,
/// 그중 하나는 반드시 틀린다.
///
/// 자리를 먼저 세우는 이유는 레이아웃이다. 녹음 화면의 세로 배분(대사 카드 → 곡선 상자 →
/// 하단 고정 컨트롤)은 레인이 제 높이를 차지해야 확인할 수 있고, 그 확인이 §6의 일이다.
/// 점이 없는 빈 레인은 어차피 정상 상태이기도 하다 — 전부 무성이거나 구버전 웹이 곡선을 안
/// 실어 보낸 경우이고, 곡선은 없어도 녹음은 성립하므로 오류 표시 없이 조용히 비워 둔다.
///
/// - Parameter topDivider: 위 레인과 나를 가르는 줄을 그릴지. 웹이 `.curve-lane + .curve-lane`로
///   자동으로 하는 일을 여기서는 첫 레인이 아닌 쪽이 스스로 말한다.
struct CurveLaneView: View {

    let label: String
    let variant: CurveLaneVariant
    var topDivider: Bool = false

    var body: some View {
        ZStack(alignment: .topLeading) {
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
}
