import SwiftUI

/// 버튼 무게 (KAN-148). 안드로이드 `ButtonVariant`와 같은 셋이다.
///
/// - ``primary`` 주동작 (녹음 시작·다음·허용). 화면에서 유일하게 잉크로 꽉 찬 면이다
/// - ``secondary`` 보조 (재녹음·재시도). 테두리만 두르고 그림자를 두지 않는다
/// - ``text`` 이탈·복귀. 눌리면 안 되는 쪽이라 무게를 가장 뺀다
enum ButtonVariant {
    case primary
    case secondary
    case text
}

/// 공통 버튼 (KAN-148, 형태는 KAN-161 2단계). 안드로이드 `ui/components/AccenturyButton.kt`의
/// 이식본이고 웹의 `.btn`과 같은 모양·같은 값이다 — 두 런타임이 한 테스트 안에서 번갈아
/// 나오므로 버튼이 서로 다르게 생기면 바로 보인다.
///
/// 오려 낸 종이다: 오른쪽·아래로 어긋난 자리에 ``View/paperShadow(cornerRadius:visible:)``가
/// 단색 면 한 겹을 깔아 종이가 떠 있는 것처럼 보이고, 누르면 본체가 정확히 그만큼 내려가
/// 그림자를 덮는다 — 종이가 바닥에 닿는 순간이다. 총 차지 높이는 눌림 전후로 같아서 옆
/// 요소가 밀리지 않는다.
///
/// 그림자는 주 버튼에만 있다. 그림자는 "떠 있다"는 뜻이라 화면에 떠 있는 종이가 둘이면 어느
/// 쪽을 눌러야 하는지가 흐려진다. 보조 버튼은 자리만 같게 비우고(눌림 거리가 같다) 그림자를
/// 그리지 않는다.
///
/// 눌림을 제스처가 아니라 ``PapercutButtonStyle``로 받는다 — `Button`의 의미론(스크린 리더의
/// "버튼", 활성화)을 그대로 두면서 `isPressed`만 빌려 쓰는 자리가 그것이다. 제스처로 직접
/// 받으면 VoiceOver가 이 요소를 눌러 줄 방법이 사라진다.
struct AccenturyButton: View {

    let text: String
    var variant: ButtonVariant = .primary
    var enabled: Bool = true
    /// 폭을 늘려 잡을지. 안드로이드가 호출부에서 `Modifier.weight(1f)`/`fillMaxWidth()`로
    /// 정하던 자리다 — 검토 화면의 [재녹음]·[다음]이 같은 폭을 갖는 규칙이 여기 걸린다.
    var fillsWidth: Bool = false
    let action: () -> Void

    var body: some View {
        switch variant {
        case .text:
            Button(action: action) {
                Text(text)
                    .papercutType(.label)
                    .foregroundColor(Papercut.ink)
                    .frame(maxWidth: fillsWidth ? .infinity : nil)
                    .frame(minHeight: Papercut.touchTargetMin)
                    .padding(.horizontal, Papercut.space4)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
            .opacity(enabled ? 1 : Papercut.opacityDisabled)

        case .primary, .secondary:
            Button(action: action) {
                // 주 CTA 라벨은 Jua 20(`title`)에 자간 0.4다 — 이 화면에서 눌러야 할 것이
                // 제목만큼 크고, 굵기를 못 올리는 폰트라 자간이 무게를 대신한다 (KAN-178).
                // 보조는 본문 글꼴 15(`bodySmall`)로, 안드로이드 `bodyMedium` 자리다.
                Text(text)
                    .papercutType(
                        variant == .primary ? .title : .bodySmall,
                        tracking: variant == .primary ? Papercut.primaryLabelTracking : nil
                    )
                    .foregroundColor(variant == .primary ? Papercut.cream : Papercut.ink)
            }
            .buttonStyle(PapercutButtonStyle(isPrimary: variant == .primary, fillsWidth: fillsWidth))
            .disabled(!enabled)
            // 비활성은 불투명도만 낮춘다 — 회색으로 칠하면 색이 하나 더 늘고 배경에 따라 대비를
            // 잃는다. 원래 색을 흐리게 하면 대비가 함께 줄어 예측 가능하다.
            .opacity(enabled ? 1 : Papercut.opacityDisabled)
        }
    }
}

/// 종이 버튼의 면·테두리·눌림. 라벨은 호출자가 그리고 여기는 그 라벨을 감싸는 종이만 그린다.
struct PapercutButtonStyle: ButtonStyle {

    let isPrimary: Bool
    var fillsWidth: Bool = false

    func makeBody(configuration: Configuration) -> some View {
        let shape = RoundedRectangle(cornerRadius: Papercut.radiusMD, style: .continuous)
        // 눌림은 0..1 한 값이다 — 본체가 내려가는 거리를 x·y 따로 애니메이션하면 두 축이
        // 미세하게 어긋나 종이가 비스듬히 미끄러진다.
        let sink: CGFloat = configuration.isPressed ? 1 : 0

        return configuration.label
            .padding(.horizontal, Papercut.space6)
            .frame(maxWidth: fillsWidth ? .infinity : nil)
            // 라벨이 짧아도 손가락이 닿을 만큼은 넓게 잡는다. 주 버튼이 56, 보조가 48이다 —
            // 둘의 무게 차이가 크기로도 읽힌다.
            .frame(minWidth: 120, minHeight: isPrimary ? Papercut.controlHeightLarge : Papercut.touchTargetMin)
            .background(shape.fill(isPrimary ? Papercut.ink : Papercut.cream))
            // 주 버튼도 테두리를 두른다. 면과 같은 색이라 낭비 같지만, 크림 배경 위에서 잉크
            // 면의 가장자리가 종이를 오린 자리처럼 또렷해진다.
            .overlay(shape.stroke(Papercut.ink, lineWidth: isPrimary ? Papercut.borderStrong : Papercut.borderRegular))
            .contentShape(shape)
            .offset(x: sink * Papercut.paperShadowX, y: sink * Papercut.paperShadowY)
            .paperShadow(cornerRadius: Papercut.radiusMD, visible: isPrimary)
            .animation(.easeOut(duration: Papercut.Motion.press), value: configuration.isPressed)
    }
}
