import SwiftUI

/// 대사 카드 (KAN-148, 형태는 KAN-161 2단계). 안드로이드 `ui/components/PromptCard.kt`의
/// 이식본이고 웹 `.prompt-card`와 같은 규격이다 — 어휘 문항(웹)과 녹음 화면(네이티브)이 번갈아
/// 나오므로 카드 크기·모서리·그림자가 다르면 전환마다 화면이 들썩인다.
///
/// 오려 낸 종이 카드다: 크림 면에 잉크 테두리 1.5를 두르고 그늘 한 겹을 오른쪽·아래에 깐다.
/// 카드와 배경이 같은 크림이라 카드를 세우는 것은 색이 아니라 테두리와 그늘이다.
///
/// 최소 높이를 ``Papercut/promptCardMinHeight``로 잡는 이유: 문항마다 글자 수가 달라도 카드가
/// 같은 크기여야 아래 요소가 제자리에 있는 것처럼 읽힌다.
///
/// ``caption``은 알약 배지가 아니라 카드 맨 위 캡션 한 줄이다 — 배지는 면을 하나 더 만드는데,
/// 카드가 이미 배경과 같은 크림이라 그 면이 카드 안에 또 카드를 그린 것처럼 보였다.
struct PromptCard: View {

    let caption: String
    let prompt: String
    /// 아트보드에는 없다. 목소리 점검처럼 아트보드가 없는 화면이 아직 쓴다.
    var supporting: String?

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Papercut.radiusXL, style: .continuous)

        return VStack(alignment: .leading, spacing: Papercut.space2) {
            Text(caption)
                .papercutType(.caption)
                .foregroundColor(Papercut.muted)
            Text(prompt)
                .papercutType(.headline)
                .foregroundColor(Papercut.ink)
                .fixedSize(horizontal: false, vertical: true)
            if let supporting {
                Text(supporting)
                    .papercutType(.label)
                    .foregroundColor(Papercut.muted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Papercut.promptCardPadding)
        .frame(minHeight: Papercut.promptCardMinHeight, alignment: .center)
        .background(shape.fill(Papercut.cream))
        .overlay(shape.stroke(Papercut.ink, lineWidth: Papercut.borderRegular))
        .paperShadow(cornerRadius: Papercut.radiusXL)
    }
}
