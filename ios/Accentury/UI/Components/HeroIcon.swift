import SwiftUI

/// 화면을 여는 원형 아이콘 (KAN-148, 형태는 KAN-161 2단계). 안드로이드
/// `ui/components/HeroIcon.kt`의 이식본이고 웹 `.hero-icon`과 같은 규격이다 — 오려 낸 크림
/// 동그라미에 잉크 테두리를 두르고 오프셋 그림자로 띄운다.
///
/// 잉크로 꽉 찬 원이 아니라 크림 면이다: 잉크 면은 화면에서 주 버튼과 무게가 같아져 어느 쪽을
/// 눌러야 하는지가 흐려진다. 아이콘은 누르는 것이 아니다.
///
/// 안에 들어가는 것은 **잉크 선화**다 (KAN-161 4단계). 이모지를 담고 있었는데, 잉크 한 색
/// 화면에서 이모지는 색을 가진 유일한 물건이라 종이에 붙인 스티커처럼 그림 밖으로 튄다.
/// 안드로이드는 벡터 자산(`R.drawable.outline_mic_24`)을 쓰고 iOS는 같은 그림의 SF Symbol을
/// 쓴다 — 잉크 선화 자산 번들은 §7·§8 다듬기 몫이다.
///
/// - Parameter accessibilityLabel: 아이콘이 정보를 나를 때만 준다. 옆 제목이 같은 말을 하면
///   `nil`로 두어 스크린 리더가 같은 문장을 두 번 읽지 않게 한다.
struct HeroIcon: View {

    let systemName: String
    var accessibilityLabel: String?

    var body: some View {
        Circle()
            .fill(Papercut.cream)
            .overlay(Circle().stroke(Papercut.ink, lineWidth: Papercut.borderRegular))
            .overlay {
                Image(systemName: systemName)
                    // 원 지름(112)의 절반쯤. 선화가 테두리에 닿지 않고 종이 가운데에 앉는 크기다.
                    .font(.system(size: 56, weight: .light))
                    .foregroundColor(Papercut.ink)
            }
            .frame(width: Papercut.heroIconSize, height: Papercut.heroIconSize)
            .paperCircleShadow()
            .accessibilityHidden(accessibilityLabel == nil)
            .accessibilityLabel(accessibilityLabel ?? "")
    }
}
