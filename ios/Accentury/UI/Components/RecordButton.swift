import SwiftUI

/// 원형 녹음 버튼 (KAN-148, 형태는 KAN-161 2단계). 안드로이드 `ui/components/RecordButton.kt`의
/// 이식본이다. 시안이 이 화면에만 주는 모양이다 — 다른 버튼은 모두 알약꼴인데 녹음만
/// 원형이라, 화면에서 눌러야 할 것이 무엇인지 모양만으로 읽힌다.
///
/// 오려 낸 크림 동그라미에 잉크 테두리 2를 두르고 그늘을 깐다. 누르면 다른 버튼과 같은 거리만큼
/// 내려가 그늘을 덮는다.
///
/// 색으로는 상태를 말하지 않는다 (정본 §7). 녹음 중이라는 것은 **안쪽 도형**이 말한다 —
/// 대기는 마이크 선화, 녹음 중은 잉크 정사각형(정지의 관용 기호)이다. 여기에 둘레의 파문과
/// 접근성 라벨이 겹쳐 세 신호가 같은 것을 알린다.
///
/// 파문은 모션 축소에서 멈춘다 — 끝없이 반복하는 애니메이션은 전정 장애가 있는 사용자에게
/// 실제로 불편을 준다 (WCAG 2.3.3). 대신 도형과 라벨은 그대로라 상태 정보는 잃지 않는다.
/// 안드로이드는 `ANIMATOR_DURATION_SCALE`을 읽고 iOS는 `accessibilityReduceMotion`을 읽는다.
struct RecordButton: View {

    let accessibilityLabel: String
    var recording: Bool = false
    let action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            ZStack {
                if recording {
                    /*
                     * 정지는 아이콘 자산 없이 사각형 하나로 그린다 — 재생/정지의 관용 기호라
                     * 따로 설명하지 않아도 읽히고, 마이크에 사선을 그은 그림보다 "지금 누르면
                     * 멈춘다"가 분명하다. 시안 값(28·모서리 6) 그대로다.
                     */
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(Papercut.ink)
                        .frame(width: 28, height: 28)
                } else {
                    Image(systemName: "mic")
                        .font(.system(size: 34, weight: .light))
                        .foregroundColor(Papercut.ink)
                }
            }
        }
        .buttonStyle(RecordButtonStyle(recording: recording, animatesRipple: recording && !reduceMotion))
        .accessibilityLabel(accessibilityLabel)
    }
}

/// 녹음 버튼의 면·테두리·눌림·파문.
private struct RecordButtonStyle: ButtonStyle {

    let recording: Bool
    let animatesRipple: Bool

    func makeBody(configuration: Configuration) -> some View {
        let sink: CGFloat = configuration.isPressed ? 1 : 0

        return configuration.label
            .frame(width: Papercut.recordButtonSize, height: Papercut.recordButtonSize)
            .background(Circle().fill(Papercut.cream))
            .overlay(Circle().stroke(Papercut.ink, lineWidth: Papercut.borderStrong))
            // 파문은 본체 뒤에 둔다 — 앞에 두면 커지는 테두리가 아이콘 위를 지나간다.
            // 그늘(paperCircleShadow)보다 먼저 붙어야 본체와 같은 지름의 원으로 잡힌다.
            .background { if animatesRipple { RecordingRipple() } }
            .contentShape(Circle())
            .offset(x: sink * Papercut.paperShadowX, y: sink * Papercut.paperShadowY)
            .paperCircleShadow()
            .animation(.easeOut(duration: Papercut.Motion.press), value: configuration.isPressed)
    }
}

/// 녹음 중 둘레로 퍼지는 파문. 잉크 테두리 하나가 커지면서 옅어지고 처음부터 다시 시작한다 —
/// 면이 아니라 선인 이유는 반투명한 면이 크림 위에서 회색 얼룩으로 보이기 때문이다.
/// 종이 오리기 그림에는 비쳐 보이는 면이 없다 (정본 §7).
private struct RecordingRipple: View {

    @State private var progress: CGFloat = 0

    var body: some View {
        Circle()
            .stroke(Papercut.paperShadow, lineWidth: Papercut.borderStrong)
            .frame(width: Papercut.recordButtonSize, height: Papercut.recordButtonSize)
            .scaleEffect(1 + progress * 0.6)
            .opacity(1 - progress)
            .onAppear {
                withAnimation(.easeOut(duration: 1.4).repeatForever(autoreverses: false)) {
                    progress = 1
                }
            }
            .accessibilityHidden(true)
    }
}
